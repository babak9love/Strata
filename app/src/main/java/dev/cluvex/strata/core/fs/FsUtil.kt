package dev.cluvex.strata.core.fs

import android.annotation.SuppressLint
import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr
import java.io.File
import java.io.IOException

object FsUtil {
    fun isSymlink(file: File): Boolean = nioIsSymlink(file) ?: canonicalIsSymlink(file)

    @SuppressLint("NewApi")
    private fun nioIsSymlink(file: File): Boolean? = try {
        java.nio.file.Files.isSymbolicLink(file.toPath())
    } catch (_: Throwable) {
        null
    }

    private fun canonicalIsSymlink(file: File): Boolean = try {
        val parent = file.absoluteFile.parentFile
        if (parent == null) {
            false
        } else {
            val candidate = File(parent.canonicalFile, file.name)
            candidate.canonicalFile != candidate.absoluteFile
        }
    } catch (_: IOException) {
        false
    } catch (_: SecurityException) {
        false
    }

    fun createSymlink(link: File, target: String) {
        if (exists(link)) throw IOException(tr(R.string.files_name_taken))
        if (target.isEmpty() || target.contains('\u0000')) throw IOException(tr(R.string.files_link_invalid_target))
        val created = try {
            nioSymlink(link, target)
            true
        } catch (_: LinkageError) {
            false
        }
        if (!created) {
            try {
                android.system.Os.symlink(target, link.path)
            } catch (e: android.system.ErrnoException) {
                throw IOException(linkError(e.message))
            }
        }
    }

    @SuppressLint("NewApi")
    private fun nioSymlink(link: File, target: String) {
        try {
            java.nio.file.Files.createSymbolicLink(link.toPath(), java.nio.file.Paths.get(target))
        } catch (e: java.nio.file.FileSystemException) {
            throw IOException(linkError(e.reason ?: e.message))
        }
    }

    private fun linkError(reason: String?): String = when {
        reason == null -> tr(R.string.files_link_failed)
        reason.contains("not permitted", ignoreCase = true) || reason.contains("EPERM") ||
            reason.contains("not supported", ignoreCase = true) -> tr(R.string.files_link_unsupported, reason)
        else -> reason
    }

    fun deleteRecursively(file: File, access: FileAccess = LocalFileAccess): Boolean {
        if (access.isSymlink(file)) return file.delete()
        var ok = true
        if (file.isDirectory) {
            file.listFiles()?.forEach { child -> if (!deleteRecursively(child, access)) ok = false }
        }
        val deleted = file.delete() || !file.exists()
        return deleted && ok
    }

    fun isSameOrDescendant(parent: File, child: File): Boolean {
        val p = runCatching { parent.canonicalPath }.getOrDefault(parent.absolutePath)
        val c = runCatching { child.canonicalPath }.getOrDefault(child.absolutePath)
        return c == p || c.startsWith(p.trimEnd(File.separatorChar) + File.separator)
    }

    fun uniqueSibling(target: File, isDirectory: Boolean, access: FileAccess = LocalFileAccess): File {
        if (!exists(target, access)) return target
        val parent = target.parentFile ?: return target
        return access.child(parent, uniqueName(target.name, isDirectory) { exists(access.child(parent, it), access) })
    }

    fun uniqueName(name: String, isDirectory: Boolean, taken: (String) -> Boolean): String {
        if (!taken(name)) return name
        val dot = name.lastIndexOf('.')
        val (base, ext) = if (!isDirectory && dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
        var i = 1
        while (true) {
            val candidate = "$base ($i)$ext"
            if (!taken(candidate)) return candidate
            i++
        }
    }

    fun copyTree(
        src: File,
        dst: File,
        from: FileAccess = LocalFileAccess,
        overwrite: Boolean = false,
        to: FileAccess = from,
        keepTime: Boolean = true,
    ): Boolean {
        if (src.isDirectory) {
            if (!dst.isDirectory && !dst.mkdirs()) return false
            val children = src.listFiles() ?: return false
            var ok = true
            for (child in children) {
                if (!copyTree(child, to.child(dst, child.name), from, overwrite, to, keepTime)) ok = false
            }
            if (keepTime) dst.setLastModified(src.lastModified())
            return ok
        }
        if (dst.exists()) {
            if (!overwrite || dst.isDirectory) return false
        }
        dst.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
        from.openInput(src).use { input ->
            to.openOutput(dst).use { output -> input.copyTo(output, 64 * 1024) }
        }
        if (keepTime) dst.setLastModified(src.lastModified())
        return true
    }

    fun move(src: File, dst: File, from: FileAccess = LocalFileAccess, to: FileAccess = from): Boolean {
        if (from.sameProcessAs(to) && src.renameTo(dst)) return true
        val copied = runCatching { copyTree(src, dst, from, overwrite = false, to = to) }.getOrDefault(false)
        if (!copied) return false
        return deleteRecursively(src, from)
    }

    fun sizeOf(file: File, access: FileAccess = LocalFileAccess): Long {
        if (access.isSymlink(file) || !file.isDirectory) return if (file.isFile) file.length() else 0L
        return file.listFiles()?.sumOf { sizeOf(it, access) } ?: 0L
    }

    fun exists(file: File, access: FileAccess = LocalFileAccess): Boolean = file.exists() || access.isSymlink(file)
}
