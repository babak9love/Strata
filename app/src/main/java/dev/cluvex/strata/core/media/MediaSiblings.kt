package dev.cluvex.strata.core.media

import dev.cluvex.strata.core.model.FileItem
import dev.cluvex.strata.core.util.FileKind
import dev.cluvex.strata.core.util.FileKinds
import dev.cluvex.strata.core.util.NaturalOrder
import java.io.File

data class MediaList(val files: List<File>, val start: Int) {
    val current: File get() = files[start]
}

object MediaSiblings {

    fun of(file: File, kind: FileKind, showHidden: Boolean = true): MediaList {
        val dir = file.parentFile
        val siblings = dir?.listFiles()
            ?.filter { (showHidden || !it.name.startsWith('.')) && kindOf(it.name) == kind && it.isFile }
            .orEmpty()
            .sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }
            .ifEmpty { listOf(file) }
        val files = if (siblings.any { it.path == file.path }) siblings else (siblings + file).sortedWith { a, b -> NaturalOrder.compare(a.name, b.name) }
        return MediaList(files, files.indexOfFirst { it.path == file.path }.coerceAtLeast(0))
    }

    private fun kindOf(name: String): FileKind =
        FileKinds.of(FileItem(name, name, isDirectory = false, size = 0, lastModified = 0, isHidden = false, canRead = true, canWrite = true))
}
