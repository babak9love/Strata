package dev.cluvex.strata.core.search

import dev.cluvex.strata.core.model.FileItem

enum class NameMatchMode { GLOB, REGEX, PLAIN }

data class SizeFilter(val min: Long? = null, val max: Long? = null)
data class DateFilter(val after: Long? = null, val before: Long? = null)

data class SearchQuery(
    val root: String,
    val namePattern: String = "",
    val nameMode: NameMatchMode = NameMatchMode.GLOB,
    val caseSensitive: Boolean = false,
    val includeSubdirs: Boolean = true,
    val maxDepth: Int = Int.MAX_VALUE,
    val includeHidden: Boolean = false,
    val onlyFiles: Boolean = false,
    val onlyDirs: Boolean = false,
    val size: SizeFilter = SizeFilter(),
    val date: DateFilter = DateFilter(),
    val content: String = "",
    val contentRegex: Boolean = false,
    val excludePaths: List<String> = emptyList(),
    val maxResults: Int = 2000,
    val maxHitsPerFile: Int = 50,
)

data class ContentHit(val line: Int, val column: Int, val preview: String)

data class SearchResult(
    val item: FileItem,
    val contentHits: List<ContentHit> = emptyList(),
)

sealed interface SearchEvent {
    data class Progress(val scanned: Int, val currentPath: String) : SearchEvent
    data class Found(val result: SearchResult) : SearchEvent
    data class Finished(val total: Int, val stoppedEarly: Boolean) : SearchEvent
}
