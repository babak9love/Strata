package dev.cluvex.strata.core.fs

import java.io.File
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** Real on-disk content size of a folder: the sum of every regular file inside it. Symlinks are not followed. */
object FolderSize {
    fun of(dir: File, active: () -> Boolean = { true }): Long {
        var total = 0L
        try {
            Files.walkFileTree(dir.toPath(), object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(d: Path, attrs: BasicFileAttributes): FileVisitResult =
                    if (active()) FileVisitResult.CONTINUE else FileVisitResult.TERMINATE

                override fun visitFile(f: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (attrs.isRegularFile) total += attrs.size()
                    return if (active()) FileVisitResult.CONTINUE else FileVisitResult.TERMINATE
                }

                override fun visitFileFailed(f: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            })
        } catch (_: Exception) {
        }
        return total
    }
}
