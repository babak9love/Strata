package dev.cluvex.strata.core.fs

import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.model.FileItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

sealed interface OpProgress {
    data class Running(
        val currentName: String,
        val doneBytes: Long,
        val totalBytes: Long,
        val doneFiles: Int,
        val totalFiles: Int,
    ) : OpProgress

    data class Finished(
        val okCount: Int,
        val failed: List<String>,
        val skipped: List<String> = emptyList(),
        val created: List<String> = emptyList(),
        /** Old and new path of every item that was moved or renamed, so things tied to a path can follow it. */
        val moves: List<Pair<String, String>> = emptyList(),
        /** The moves among [moves] where the item was saved under another name because the destination already had one of that name. */
        val keptBoth: List<Pair<String, String>> = emptyList(),
    ) : OpProgress
}

enum class Conflict { OVERWRITE, SKIP, KEEP_BOTH }

data class LinkRequest(val name: String, val target: String)

object FileOperations {
    fun validateName(name: String): String? = when {
        name.isBlank() -> tr(R.string.files_name_empty)
        name == "." || name == ".." -> tr(R.string.common_invalid_name)
        name.contains('/') -> tr(R.string.files_name_slash)
        name.contains('\u0000') -> tr(R.string.files_name_bad_char)
        name.toByteArray(Charsets.UTF_8).size > 255 -> tr(R.string.files_name_too_long)
        else -> null
    }

    suspend fun mkdir(parent: String, name: String, access: FileAccess = LocalFileAccess): Result<FileItem> = io {
        validateName(name)?.let { return@io Result.failure(IllegalArgumentException(it)) }
        val f = access.child(access.file(parent), name)
        when {
            FsUtil.exists(f, access) -> Result.failure(IllegalStateException(tr(R.string.files_item_exists)))
            f.mkdirs() -> Result.success(itemOf(f, access))
            else -> Result.failure(IllegalStateException(tr(R.string.files_mkdir_failed)))
        }
    }

    suspend fun newFile(parent: String, name: String, access: FileAccess = LocalFileAccess): Result<FileItem> = io {
        validateName(name)?.let { return@io Result.failure(IllegalArgumentException(it)) }
        val f = access.child(access.file(parent), name)
        when {
            FsUtil.exists(f, access) -> Result.failure(IllegalStateException(tr(R.string.files_item_exists)))
            runCatching { f.createNewFile() }.getOrDefault(false) -> Result.success(itemOf(f, access))
            else -> Result.failure(IllegalStateException(tr(R.string.files_create_failed)))
        }
    }

    suspend fun rename(item: FileItem, newName: String, access: FileAccess = LocalFileAccess): Result<FileItem> = io {
        validateName(newName)?.let { return@io Result.failure(IllegalArgumentException(it)) }
        val src = access.file(item.path)
        val parent = src.parentFile ?: return@io Result.failure(IllegalStateException(tr(R.string.files_rename_root)))
        val dst = access.child(parent, newName)
        when {
            newName == src.name -> Result.success(item)
            FsUtil.exists(dst, access) && !dst.name.equals(src.name, ignoreCase = true) ->
                Result.failure(IllegalStateException(tr(R.string.files_name_exists)))
            src.renameTo(dst) -> Result.success(itemOf(dst, access))
            else -> Result.failure(IllegalStateException(tr(R.string.files_rename_failed)))
        }
    }

    suspend fun setModified(paths: List<String>, millis: Long, access: FileAccess = LocalFileAccess): Int = io {
        paths.count { access.file(it).setLastModified(millis) }
    }

    fun delete(items: List<FileItem>, access: FileAccess = LocalFileAccess): Flow<OpProgress> = flow {
        val total = items.size
        var done = 0
        val failed = mutableListOf<String>()
        for (item in items) {
            currentCoroutineContext().ensureActive()
            emit(OpProgress.Running(item.name, done.toLong(), total.toLong(), done, total))
            if (runCatching { FsUtil.deleteRecursively(access.file(item.path), access) }.getOrDefault(false)) done++
            else failed += item.name
        }
        emit(OpProgress.Finished(done, failed))
    }.flowOn(Dispatchers.IO)

    fun copy(
        items: List<FileItem>,
        destDir: String,
        conflict: Conflict = Conflict.KEEP_BOTH,
        from: FileAccess = LocalFileAccess,
        to: FileAccess = from,
        keepTime: Boolean = true,
    ): Flow<OpProgress> = transfer(items, destDir, move = false, conflict, from, to, keepTime)

    fun move(
        items: List<FileItem>,
        destDir: String,
        conflict: Conflict = Conflict.OVERWRITE,
        from: FileAccess = LocalFileAccess,
        to: FileAccess = from,
    ): Flow<OpProgress> = transfer(items, destDir, move = true, conflict, from, to, keepTime = true)

    fun createLinks(
        destDir: String,
        requests: List<LinkRequest>,
        access: FileAccess = LocalFileAccess,
        keepBoth: Boolean = true,
    ): Flow<OpProgress> = flow {
        val dest = access.file(destDir)
        val total = requests.size
        val failed = mutableListOf<String>()
        val created = mutableListOf<String>()
        requests.forEachIndexed { i, r ->
            currentCoroutineContext().ensureActive()
            emit(OpProgress.Running(r.name, i.toLong(), total.toLong(), i, total))
            validateName(r.name)?.let {
                failed += tr(R.string.files_item_error, r.name, it)
                return@forEachIndexed
            }
            var link = access.child(dest, r.name)
            if (FsUtil.exists(link, access)) {
                if (!keepBoth) {
                    failed += tr(R.string.files_link_exists, r.name)
                    return@forEachIndexed
                }
                link = FsUtil.uniqueSibling(link, isDirectory = false, access = access)
            }
            try {
                access.createSymlink(link, r.target)
                created += link.absolutePath
            } catch (e: IOException) {
                failed += tr(R.string.files_item_error, r.name, e.message)
            }
        }
        emit(OpProgress.Finished(created.size, failed, created = created))
    }.flowOn(Dispatchers.IO)

    fun itemOf(f: File, access: FileAccess = LocalFileAccess): FileItem =
        if (!access.isRoot) {
            FileItem.of(f)
        } else {
            val dir = f.isDirectory
            FileItem(
                name = f.name,
                path = f.absolutePath,
                isDirectory = dir,
                size = if (dir) 0L else f.length(),
                lastModified = f.lastModified(),
                isHidden = f.name.startsWith('.'),
                canRead = f.canRead(),
                canWrite = f.canWrite(),
                isSymlink = access.isSymlink(f),
            )
        }

    private fun transfer(
        items: List<FileItem>,
        destDir: String,
        move: Boolean,
        conflict: Conflict,
        from: FileAccess,
        to: FileAccess,
        keepTime: Boolean,
    ): Flow<OpProgress> = flow {
        val dest = to.file(destDir)
        val totalBytes = items.sumOf { FsUtil.sizeOf(from.file(it.path), from) }
        val totalFiles = items.size
        var doneBytes = 0L
        var done = 0
        val failed = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val created = mutableListOf<String>()
        val moves = mutableListOf<Pair<String, String>>()
        val keptBoth = mutableListOf<Pair<String, String>>()

        for (item in items) {
            currentCoroutineContext().ensureActive()
            emit(OpProgress.Running(item.name, doneBytes, totalBytes, done, totalFiles))
            val src = from.file(item.path)

            if (src.isDirectory && !from.isSymlink(src) && FsUtil.isSameOrDescendant(src, dest)) {
                failed += tr(if (move) R.string.files_move_into_itself else R.string.files_copy_into_itself, item.name)
                continue
            }
            val sameDir = src.parentFile?.let { parent ->
                runCatching { parent.canonicalPath == dest.canonicalPath }.getOrDefault(false)
            } ?: false
            if (move && sameDir) {
                skipped += item.name
                continue
            }

            var target = to.child(dest, src.name)
            var merge = false
            if (FsUtil.exists(target, to)) {
                when (if (sameDir) Conflict.KEEP_BOTH else conflict) {
                    Conflict.SKIP -> {
                        skipped += item.name
                        continue
                    }
                    Conflict.KEEP_BOTH -> target = FsUtil.uniqueSibling(target, src.isDirectory, to)
                    Conflict.OVERWRITE -> {
                        if (src.isDirectory && target.isDirectory && !to.isSymlink(target)) {
                            merge = true
                        } else if (!FsUtil.deleteRecursively(target, to)) {
                            failed += tr(R.string.files_cannot_replace, item.name)
                            continue
                        }
                    }
                }
            }

            val ok = runCatching { transferOne(src, target, move, merge, from, to, keepTime) }.getOrDefault(false)
            if (ok) {
                done++
                doneBytes += FsUtil.sizeOf(target, to)
                created += target.absolutePath
                if (move) {
                    moves += src.absolutePath to target.absolutePath
                    if (target.name != src.name) keptBoth += src.absolutePath to target.absolutePath
                }
            } else {
                failed += item.name
            }
        }
        emit(OpProgress.Finished(done, failed, skipped, created, moves, keptBoth))
    }.flowOn(Dispatchers.IO)

    private fun transferOne(src: File, target: File, move: Boolean, merge: Boolean, from: FileAccess, to: FileAccess, keepTime: Boolean): Boolean {
        if (merge) {
            val copied = FsUtil.copyTree(src, target, from, overwrite = true, to = to, keepTime = move || keepTime)
            return if (move) copied && FsUtil.deleteRecursively(src, from) else copied
        }
        return if (move) FsUtil.move(src, target, from, to) else FsUtil.copyTree(src, target, from, overwrite = false, to = to, keepTime = keepTime)
    }

    private suspend fun <T> io(block: suspend CoroutineScope.() -> T): T =
        withContext(Dispatchers.IO) { block() }
}
