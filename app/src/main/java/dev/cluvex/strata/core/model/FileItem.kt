package dev.cluvex.strata.core.model

import java.io.File

data class FileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val isHidden: Boolean,
    val canRead: Boolean,
    val canWrite: Boolean,
    val isSymlink: Boolean = false,
    val childCount: Int = -1,
) {
    val extension: String
        get() = if (isDirectory) "" else name.substringAfterLast('.', "").lowercase()

    val parentPath: String?
        get() = File(path).parent

    companion object {
        fun of(file: File): FileItem {
            return FileItem(
                name = file.name,
                path = file.absolutePath,
                isDirectory = file.isDirectory,
                size = if (file.isDirectory) 0L else file.length(),
                lastModified = file.lastModified(),
                isHidden = file.isHidden || file.name.startsWith('.'),
                canRead = file.canRead(),
                canWrite = file.canWrite(),
                isSymlink = dev.cluvex.strata.core.fs.FsUtil.isSymlink(file),
            )
        }
    }
}
