package dev.cluvex.strata.core.remote

import dev.cluvex.strata.R
import dev.cluvex.strata.core.fs.Conflict
import dev.cluvex.strata.core.fs.FsUtil
import dev.cluvex.strata.core.fs.OpProgress
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.model.FileItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.IOException

sealed interface Place {
    data class Local(val dir: String) : Place
    data class Remote(val connectionId: String, val dir: String) : Place

    companion object {
        fun of(path: String): Place = RemoteUri.parse(path)?.let { (id, p) -> Remote(id, p) } ?: Local(path)
    }
}

object RemoteTransfers {

    fun transfer(
        items: List<FileItem>,
        to: Place,
        move: Boolean,
        conflict: Conflict,
        sessions: RemoteSessions,
        tempDir: File,
    ): Flow<OpProgress> = channelFlow {
        val sources = items.map(Source::of)
        var totalBytes = 0L
        var totalFiles = 0
        for (s in sources) {
            val (b, f) = measure(s, sessions)
            totalBytes += b
            totalFiles += f
        }
        val progress = Progress(totalBytes, totalFiles) { trySend(it) }
        val created = ArrayList<String>()
        val failed = ArrayList<String>()
        val skipped = ArrayList<String>()
        var ok = 0
        for (s in sources) {
            ensureActive()
            try {
                val result = transferOne(s, to, move, conflict, sessions, tempDir, progress)
                if (result == null) skipped += s.name else {
                    created += result
                    ok++
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed += "${s.name}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        send(OpProgress.Finished(ok, failed, skipped, created))
    }.flowOn(Dispatchers.IO)

    fun delete(items: List<FileItem>, sessions: RemoteSessions): Flow<OpProgress> = channelFlow {
        var ok = 0
        val failed = ArrayList<String>()
        items.forEachIndexed { i, item ->
            ensureActive()
            val location = RemoteUri.parse(item.path)
            if (location == null) {
                failed += tr(R.string.remote_not_on_storage_item, item.name)
                return@forEachIndexed
            }
            trySend(OpProgress.Running(item.name, 0, 0, i, items.size))
            try {
                sessions.use(location.first) { fs -> fs.delete(location.second, recursive = item.isDirectory) }
                ok++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed += "${item.name}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        send(OpProgress.Finished(ok, failed))
    }.flowOn(Dispatchers.IO)

    private suspend fun transferOne(
        s: Source,
        to: Place,
        move: Boolean,
        conflict: Conflict,
        sessions: RemoteSessions,
        tempDir: File,
        progress: Progress,
    ): String? = when (s) {
        is Source.Local -> when (to) {
            is Place.Local -> throw IllegalArgumentException("Local copies go through FileOperations")
            is Place.Remote -> sessions.use(to.connectionId) { fs ->
                val target = remoteTarget(fs, to.dir, s.name, s.isDirectory, conflict) ?: return@use null
                upload(fs, s.file, target, progress)
                RemoteUri.of(to.connectionId, target)
            }?.also { if (move) deleteLocal(s.file) }
        }
        is Source.Remote -> when (to) {
            is Place.Local -> {
                val target = localTarget(File(to.dir), s.name, s.isDirectory, conflict)
                if (target == null) null else {
                    sessions.use(s.id) { fs -> download(fs, s.path, s.isDirectory, s.size, target, progress) }
                    if (move) sessions.use(s.id) { fs -> fs.delete(s.path, recursive = s.isDirectory) }
                    target.path
                }
            }
            is Place.Remote -> when {
                move && s.id == to.connectionId && RemotePaths.parent(s.path) == RemotePaths.normalize(to.dir) -> null
                move && s.id == to.connectionId -> sessions.use(s.id) { fs ->
                    val target = remoteTarget(fs, to.dir, s.name, s.isDirectory, conflict) ?: return@use null
                    fs.rename(s.path, target)
                    progress.fileDone(s.name, s.size)
                    RemoteUri.of(to.connectionId, target)
                }
                else -> viaTemp(s, to, move, conflict, sessions, tempDir, progress)
            }
        }
    }

    private suspend fun viaTemp(
        s: Source.Remote,
        to: Place.Remote,
        move: Boolean,
        conflict: Conflict,
        sessions: RemoteSessions,
        tempDir: File,
        progress: Progress,
    ): String? {
        val tmp = File(tempDir, "transfer-${System.nanoTime()}").apply { mkdirs() }
        try {
            val local = File(tmp, s.name)
            sessions.use(s.id) { fs -> download(fs, s.path, s.isDirectory, s.size, local, Progress.SILENT) }
            val created = sessions.use(to.connectionId) { fs ->
                val target = remoteTarget(fs, to.dir, s.name, s.isDirectory, conflict) ?: return@use null
                upload(fs, local, target, progress)
                RemoteUri.of(to.connectionId, target)
            }
            if (move && created != null) sessions.use(s.id) { fs -> fs.delete(s.path, recursive = s.isDirectory) }
            return created
        } finally {
            FsUtil.deleteRecursively(tmp)
        }
    }

    private fun remoteTarget(fs: RemoteFileSystem, dir: String, name: String, isDirectory: Boolean, conflict: Conflict): String? {
        val taken = fs.list(dir).mapTo(HashSet()) { it.name }
        if (name !in taken) return RemotePaths.join(dir, name)
        return when (conflict) {
            Conflict.SKIP -> null
            Conflict.OVERWRITE -> RemotePaths.join(dir, name)
            Conflict.KEEP_BOTH -> RemotePaths.join(dir, FsUtil.uniqueName(name, isDirectory) { it in taken })
        }
    }

    private fun localTarget(dir: File, name: String, isDirectory: Boolean, conflict: Conflict): File? {
        val target = File(dir, name)
        if (!target.exists()) return target
        return when (conflict) {
            Conflict.SKIP -> null
            Conflict.OVERWRITE -> target.also { if (!isDirectory && it.isFile) it.delete() }
            Conflict.KEEP_BOTH -> FsUtil.uniqueSibling(target, isDirectory)
        }
    }

    private fun upload(fs: RemoteFileSystem, src: File, target: String, progress: Progress) {
        if (src.isDirectory) {
            if (fs.stat(target)?.isDirectory != true) fs.mkdir(target)
            src.listFiles()?.sortedBy { it.name }?.forEach { upload(fs, it, RemotePaths.join(target, it.name), progress) }
        } else {
            src.inputStream().use { input -> fs.upload(input, target) { n -> progress.bytes(src.name, n) } }
            progress.fileDone(src.name, src.length())
        }
    }

    private fun download(fs: RemoteFileSystem, path: String, isDirectory: Boolean, size: Long, target: File, progress: Progress) {
        if (isDirectory) {
            target.mkdirs()
            fs.list(path).forEach { e -> download(fs, e.path, e.isDirectory, e.size, File(target, e.name), progress) }
        } else {
            target.parentFile?.mkdirs()
            try {
                target.outputStream().use { out -> fs.download(path, out) { n -> progress.bytes(target.name, n) } }
            } catch (e: Exception) {
                target.delete()
                throw e
            }
            progress.fileDone(target.name, size)
        }
    }

    private fun deleteLocal(file: File) {
        if (!FsUtil.deleteRecursively(file)) throw IOException(tr(R.string.remote_copied_not_removed))
    }

    private suspend fun measure(s: Source, sessions: RemoteSessions): Pair<Long, Int> = when (s) {
        is Source.Local -> if (s.file.isDirectory) {
            s.file.walkTopDown().filter { it.isFile }.fold(0L to 0) { (b, f), file -> (b + file.length()) to (f + 1) }
        } else {
            s.file.length() to 1
        }
        is Source.Remote -> if (s.isDirectory) sessions.use(s.id) { fs -> measureRemote(fs, s.path) } else s.size.coerceAtLeast(0) to 1
    }

    private fun measureRemote(fs: RemoteFileSystem, path: String): Pair<Long, Int> {
        var bytes = 0L
        var files = 0
        for (e in fs.list(path)) {
            if (e.isDirectory) {
                val (b, f) = measureRemote(fs, e.path)
                bytes += b
                files += f
            } else {
                bytes += e.size.coerceAtLeast(0)
                files++
            }
        }
        return bytes to files
    }

    private sealed interface Source {
        val name: String
        val isDirectory: Boolean

        data class Local(val file: File) : Source {
            override val name: String get() = file.name
            override val isDirectory: Boolean get() = file.isDirectory
        }

        data class Remote(val id: String, val path: String, override val isDirectory: Boolean, val size: Long) : Source {
            override val name: String get() = RemotePaths.name(path)
        }

        companion object {
            fun of(item: FileItem): Source =
                RemoteUri.parse(item.path)?.let { (id, p) -> Remote(id, p, item.isDirectory, item.size) } ?: Local(File(item.path))
        }
    }

    private class Progress(private val totalBytes: Long, private val totalFiles: Int, private val emit: (OpProgress) -> Unit) {
        private var doneBytes = 0L
        private var doneFiles = 0
        private var lastReported = 0L

        fun bytes(name: String, inFile: Long) {
            if (inFile - lastReported < 256 * 1024) return
            lastReported = inFile
            emit(OpProgress.Running(name, doneBytes + inFile, totalBytes, doneFiles, totalFiles))
        }

        fun fileDone(name: String, size: Long) {
            doneBytes += size.coerceAtLeast(0)
            doneFiles++
            lastReported = 0
            emit(OpProgress.Running(name, doneBytes, totalBytes, doneFiles, totalFiles))
        }

        companion object {
            val SILENT = Progress(0, 0) { }
        }
    }
}
