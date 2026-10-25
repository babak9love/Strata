package dev.cluvex.strata.core.trash

import dev.cluvex.strata.R
import dev.cluvex.strata.core.fs.Conflict
import dev.cluvex.strata.core.fs.FsUtil
import dev.cluvex.strata.core.fs.OpProgress
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.model.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class TrashInfo(
    val id: String,
    val originalPath: String,
    val deletedAt: Long,
    val isDirectory: Boolean,
    val size: Long,
)

data class TrashEntry(val info: TrashInfo, val stored: File) {
    val name: String get() = File(info.originalPath).name
    val originalParent: String get() = File(info.originalPath).parent ?: "/"
}

class RecycleBin(
    val root: File,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val filesDir = File(root, "files")
    private val infoDir = File(root, "info")
    private val json = Json { ignoreUnknownKeys = true }

    fun contains(path: String): Boolean = FsUtil.isSameOrDescendant(root, File(path))

    fun list(): List<TrashEntry> {
        val infoFiles = infoDir.listFiles { f -> f.name.endsWith(".json") } ?: return emptyList()
        val entries = ArrayList<TrashEntry>(infoFiles.size)
        for (f in infoFiles) {
            val info = runCatching { json.decodeFromString(TrashInfo.serializer(), f.readText()) }.getOrNull()
                ?: continue
            val stored = File(filesDir, info.id)
            if (!FsUtil.exists(stored)) {
                f.delete()
                continue
            }
            entries += TrashEntry(info, stored)
        }
        return entries.sortedByDescending { it.info.deletedAt }
    }

    fun totalSize(): Long = list().sumOf { it.info.size }

    fun moveToTrash(items: List<FileItem>): Flow<OpProgress> = flow {
        ensureDirs()
        val total = items.size
        var done = 0
        val failed = mutableListOf<String>()
        for (item in items) {
            currentCoroutineContext().ensureActive()
            emit(OpProgress.Running(item.name, done.toLong(), total.toLong(), done, total))
            val src = File(item.path)
            if (contains(src.absolutePath) || FsUtil.isSameOrDescendant(src, root)) {
                failed += tr(R.string.trash_into_itself, item.name)
                continue
            }
            if (!FsUtil.exists(src)) {
                failed += tr(R.string.trash_gone, item.name)
                continue
            }
            val id = newId()
            val info = TrashInfo(
                id = id,
                originalPath = src.absolutePath,
                deletedAt = clock(),
                isDirectory = src.isDirectory,
                size = FsUtil.sizeOf(src),
            )
            val stored = File(filesDir, id)
            if (!FsUtil.move(src, stored)) {
                failed += item.name
                continue
            }
            if (!writeInfo(info)) {
                FsUtil.move(stored, src)
                failed += tr(R.string.trash_no_metadata, item.name)
                continue
            }
            done++
        }
        emit(OpProgress.Finished(done, failed))
    }.flowOn(Dispatchers.IO)

    /**
     * Puts one file or folder into the bin right away, for callers that are replacing it (an overwrite) and want to be able to
     * bring it back. Returns null when it could not be stored, in which case [src] is left where it was.
     */
    fun trash(src: File): TrashInfo? {
        ensureDirs()
        if (contains(src.absolutePath) || FsUtil.isSameOrDescendant(src, root) || !FsUtil.exists(src)) return null
        val id = newId()
        val info = TrashInfo(id, src.absolutePath, clock(), src.isDirectory, FsUtil.sizeOf(src))
        val stored = File(filesDir, id)
        if (!FsUtil.move(src, stored)) return null
        if (!writeInfo(info)) {
            FsUtil.move(stored, src)
            return null
        }
        return info
    }

    fun restore(entries: List<TrashEntry>, conflict: Conflict = Conflict.KEEP_BOTH): Flow<OpProgress> = flow {
        val total = entries.size
        var done = 0
        val failed = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        for (entry in entries) {
            currentCoroutineContext().ensureActive()
            emit(OpProgress.Running(entry.name, done.toLong(), total.toLong(), done, total))
            val original = File(entry.info.originalPath)
            original.parentFile?.mkdirs()
            var target = original
            if (FsUtil.exists(target)) {
                when (conflict) {
                    Conflict.SKIP -> {
                        skipped += entry.name
                        continue
                    }
                    Conflict.KEEP_BOTH -> target = FsUtil.uniqueSibling(target, entry.info.isDirectory)
                    Conflict.OVERWRITE -> if (!FsUtil.deleteRecursively(target)) {
                        failed += tr(R.string.files_cannot_replace, entry.name)
                        continue
                    }
                }
            }
            if (!FsUtil.move(entry.stored, target)) {
                failed += entry.name
                continue
            }
            infoFile(entry.info.id).delete()
            done++
        }
        emit(OpProgress.Finished(done, failed, skipped))
    }.flowOn(Dispatchers.IO)

    fun purge(entries: List<TrashEntry>): Flow<OpProgress> = flow {
        val total = entries.size
        var done = 0
        val failed = mutableListOf<String>()
        for (entry in entries) {
            currentCoroutineContext().ensureActive()
            emit(OpProgress.Running(entry.name, done.toLong(), total.toLong(), done, total))
            if (FsUtil.deleteRecursively(entry.stored)) {
                infoFile(entry.info.id).delete()
                done++
            } else {
                failed += entry.name
            }
        }
        emit(OpProgress.Finished(done, failed))
    }.flowOn(Dispatchers.IO)

    fun empty(): Flow<OpProgress> = purge(list())

    fun purgeOlderThan(maxAgeMillis: Long): Int {
        if (maxAgeMillis <= 0) return 0
        val cutoff = clock() - maxAgeMillis
        var purged = 0
        for (entry in list()) {
            if (entry.info.deletedAt < cutoff && FsUtil.deleteRecursively(entry.stored)) {
                infoFile(entry.info.id).delete()
                purged++
            }
        }
        return purged
    }

    private fun ensureDirs() {
        filesDir.mkdirs()
        infoDir.mkdirs()
        val noMedia = File(root, ".nomedia")
        if (!noMedia.exists()) runCatching { noMedia.createNewFile() }
    }

    private fun newId(): String {
        while (true) {
            val id = "${clock()}-${UUID.randomUUID().toString().substring(0, 8)}"
            if (!FsUtil.exists(File(filesDir, id)) && !infoFile(id).exists()) return id
        }
    }

    private fun infoFile(id: String) = File(infoDir, "$id.json")

    private fun writeInfo(info: TrashInfo): Boolean = runCatching {
        val tmp = File(infoDir, "${info.id}.json.tmp")
        tmp.writeText(json.encodeToString(TrashInfo.serializer(), info))
        tmp.renameTo(infoFile(info.id))
    }.getOrDefault(false)

    companion object {
        const val DIR_NAME = ".strata/recycle"

        fun forStorage(storageRoot: String) = RecycleBin(File(storageRoot, DIR_NAME))
    }
}
