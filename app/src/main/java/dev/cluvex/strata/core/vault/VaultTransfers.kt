package dev.cluvex.strata.core.vault

import dev.cluvex.strata.R
import dev.cluvex.strata.core.fs.Conflict
import dev.cluvex.strata.core.fs.FsUtil
import dev.cluvex.strata.core.fs.LocalFileAccess
import dev.cluvex.strata.core.fs.OpProgress
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.model.FileItem
import dev.cluvex.strata.core.remote.RemotePaths
import dev.cluvex.strata.core.remote.RemoteUri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

object VaultTransfers {

    fun moveIn(items: List<FileItem>, store: VaultStore, dir: String, conflict: Conflict): Flow<OpProgress> = channelFlow {
        val sources = items.map { File(it.path) }
        var totalBytes = 0L
        var totalFiles = 0
        for (src in sources) {
            if (LocalFileAccess.isSymlink(src)) continue
            if (src.isDirectory) {
                walk(src) { f ->
                    totalBytes += f.length()
                    totalFiles++
                }
            } else {
                totalBytes += src.length()
                totalFiles++
            }
        }
        val progress = Progress(totalBytes, totalFiles) { trySend(it) }
        var ok = 0
        val failed = ArrayList<String>()
        val skipped = ArrayList<String>()
        val created = ArrayList<String>()
        for (src in sources) {
            ensureActive()
            try {
                if (LocalFileAccess.isSymlink(src)) {
                    failed += tr(R.string.vault_err_link, src.name)
                    continue
                }
                val target = target(store, dir, src.name, src.isDirectory, conflict)
                if (target == null) {
                    skipped += src.name
                    continue
                }
                val clean = copyIn(store, src, target, progress)
                created += RemoteUri.of(Vault.ID, target)
                if (!clean) {
                    failed += tr(R.string.vault_err_kept_links, src.name)
                } else if (!FsUtil.deleteRecursively(src)) {
                    failed += tr(R.string.vault_err_kept_original, src.name)
                } else {
                    ok++
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed += "${src.name}: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        send(OpProgress.Finished(ok, failed, skipped, created))
    }.flowOn(Dispatchers.IO)

    fun restore(paths: List<String>, store: VaultStore, fallback: File, conflict: Conflict): Flow<OpProgress> = channelFlow {
        val plans = paths.map { p ->
            val top = RemotePaths.normalize(p)
            val base = RemotePaths.parent(top)
            top to store.tree(top).filter { !it.dir }.map { e ->
                val relative = if (base == "/") e.path.removePrefix("/") else e.path.removePrefix("$base/")
                e to (e.origin?.let(::File) ?: File(fallback, relative))
            }
        }
        val totalBytes = plans.sumOf { (_, files) -> files.sumOf { it.first.size } }
        val totalFiles = plans.sumOf { (_, files) -> files.size }
        val progress = Progress(totalBytes, totalFiles) { trySend(it) }
        var ok = 0
        val failed = ArrayList<String>()
        val skipped = ArrayList<String>()
        val created = ArrayList<String>()
        for ((top, files) in plans) {
            ensureActive()
            var clean = true
            for ((entry, wanted) in files) {
                ensureActive()
                val name = RemotePaths.name(entry.path)
                try {
                    val target = localTarget(wanted, conflict)
                    if (target == null) {
                        skipped += name
                        clean = false
                        continue
                    }
                    target.parentFile?.mkdirs()
                    val part = target.resolveSibling(".${target.name}.part")
                    try {
                        FileOutputStream(part).use { out ->
                            store.read(entry.path, out) { n -> progress.bytes(name, n) }
                            out.fd.sync()
                        }
                        if (!part.renameTo(target)) throw IOException(tr(R.string.files_rename_failed))
                    } catch (e: Throwable) {
                        part.delete()
                        throw e
                    }
                    if (entry.modified > 0) target.setLastModified(entry.modified)
                    store.delete(entry.path, recursive = false)
                    created += target.path
                    progress.fileDone(name, entry.size)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    clean = false
                    failed += "$name: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            if (clean) {
                runCatching { if (store.stat(top) != null) store.delete(top, recursive = true) }
                ok++
            }
        }
        send(OpProgress.Finished(ok, failed, skipped, created))
    }.flowOn(Dispatchers.IO)

    private fun walk(dir: File, onFile: (File) -> Unit) {
        dir.listFiles()?.forEach { f ->
            if (LocalFileAccess.isSymlink(f)) return@forEach
            if (f.isDirectory) walk(f, onFile) else onFile(f)
        }
    }

    private fun copyIn(store: VaultStore, src: File, target: String, progress: Progress): Boolean {
        if (src.isDirectory) {
            if (store.stat(target)?.dir != true) store.mkdir(target)
            val children = src.listFiles() ?: throw IOException(tr(R.string.vault_err_read, src.name))
            var clean = true
            for (child in children.sortedBy { it.name }) {
                if (LocalFileAccess.isSymlink(child)) {
                    clean = false
                    continue
                }
                if (!copyIn(store, child, RemotePaths.join(target, child.name), progress)) clean = false
            }
            return clean
        }
        if (store.stat(target)?.dir == true) throw IOException(tr(R.string.vault_err_is_folder, RemotePaths.name(target)))
        src.inputStream().use { input ->
            store.write(target, input, origin = src.absolutePath, modified = src.lastModified()) { n -> progress.bytes(src.name, n) }
        }
        progress.fileDone(src.name, src.length())
        return true
    }

    private fun target(store: VaultStore, dir: String, name: String, isDirectory: Boolean, conflict: Conflict): String? {
        val taken = store.list(dir).mapTo(HashSet()) { RemotePaths.name(it.path) }
        if (name !in taken) return RemotePaths.join(dir, name)
        return when (conflict) {
            Conflict.SKIP -> null
            Conflict.OVERWRITE -> RemotePaths.join(dir, name)
            Conflict.KEEP_BOTH -> RemotePaths.join(dir, FsUtil.uniqueName(name, isDirectory) { it in taken })
        }
    }

    private fun localTarget(wanted: File, conflict: Conflict): File? {
        if (!wanted.exists()) return wanted
        return when (conflict) {
            Conflict.SKIP -> null
            Conflict.OVERWRITE -> wanted.takeIf { it.isFile }?.also { it.delete() } ?: FsUtil.uniqueSibling(wanted, false)
            Conflict.KEEP_BOTH -> FsUtil.uniqueSibling(wanted, false)
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
    }
}
