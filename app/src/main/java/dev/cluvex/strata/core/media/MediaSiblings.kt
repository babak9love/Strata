package dev.cluvex.strata.core.media

import dev.cluvex.strata.core.model.FileItem
import dev.cluvex.strata.core.model.SortOrder
import dev.cluvex.strata.core.util.FileKind
import dev.cluvex.strata.core.util.FileKinds
import dev.cluvex.strata.core.util.NaturalOrder
import java.io.File

data class MediaList(val files: List<File>, val start: Int) {
    val current: File get() = files[start]
}

object MediaSiblings {

    /**
     * The files next to [file] of the same [kind], in the order of [sortOrder] (the folder's sort in the file list)
     * or, when it is null, by name in natural order.
     */
    fun of(file: File, kind: FileKind, showHidden: Boolean = true, sortOrder: SortOrder? = null): MediaList {
        val dir = file.parentFile
        val found = dir?.listFiles()
            ?.filter { (showHidden || !it.name.startsWith('.')) && kindOf(it.name) == kind && it.isFile }
            .orEmpty()
            .ifEmpty { listOf(file) }
        val all = if (found.any { it.path == file.path }) found else found + file
        val files = ordered(all, sortOrder)
        return MediaList(files, files.indexOfFirst { it.path == file.path }.coerceAtLeast(0))
    }

    private fun ordered(files: List<File>, sortOrder: SortOrder?): List<File> {
        if (sortOrder == null) return files.sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }
        val byFile = files.associateWith { FileItem.of(it) }
        // Same comparator as the file list; only files here, so folders-first does not matter.
        val comparator = sortOrder.copy(dirsFirst = false).comparator()
        return files.sortedWith { a, b -> comparator.compare(byFile.getValue(a), byFile.getValue(b)) }
    }

    private fun kindOf(name: String): FileKind =
        FileKinds.of(FileItem(name, name, isDirectory = false, size = 0, lastModified = 0, isHidden = false, canRead = true, canWrite = true))
}
