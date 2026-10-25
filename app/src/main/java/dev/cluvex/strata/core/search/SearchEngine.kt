package dev.cluvex.strata.core.search

import dev.cluvex.strata.core.model.FileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

object SearchEngine {

    private const val MAX_CONTENT_BYTES = 25L * 1024 * 1024
    private const val BINARY_SNIFF_BYTES = 8000
    private const val PREVIEW_MAX = 200

    fun search(query: SearchQuery): Flow<SearchEvent> = flow {
        var scanned = 0
        var found = 0
        var stoppedEarly = false
        val root = File(query.root)
        val exclude = query.excludePaths.map { File(it).absolutePath }

        val stack = ArrayDeque<Pair<File, Int>>()
        stack.addLast(root to 0)

        outer@ while (stack.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val (dir, depth) = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (child in children) {
                currentCoroutineContext().ensureActive()
                if (exclude.any { child.absolutePath == it || child.absolutePath.startsWith("$it/") }) continue
                val hidden = child.isHidden || child.name.startsWith('.')
                if (hidden && !query.includeHidden) continue

                scanned++
                if (scanned % 64 == 0) emit(SearchEvent.Progress(scanned, child.absolutePath))

                val item = FileItem.of(child)
                val typeOk = !(query.onlyFiles && item.isDirectory) && !(query.onlyDirs && !item.isDirectory)
                if (typeOk && passesFilters(item, query) && matchesName(item.name, query)) {
                    if (query.content.isBlank()) {
                        emit(SearchEvent.Found(SearchResult(item)))
                        found++
                    } else if (!item.isDirectory) {
                        val hits = searchContent(child, query)
                        if (hits.isNotEmpty()) {
                            emit(SearchEvent.Found(SearchResult(item, hits)))
                            found++
                        }
                    }
                    if (found >= query.maxResults) {
                        stoppedEarly = true
                        break@outer
                    }
                }

                if (item.isDirectory && query.includeSubdirs && depth < query.maxDepth) {
                    stack.addLast(child to depth + 1)
                }
            }
        }
        emit(SearchEvent.Finished(found, stoppedEarly))
    }.flowOn(Dispatchers.IO)

    fun matchesName(name: String, query: SearchQuery): Boolean {
        if (query.namePattern.isBlank()) return true
        val opts = if (query.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        return when (query.nameMode) {
            NameMatchMode.GLOB -> globToRegex(query.namePattern, query.caseSensitive).matches(name)
            NameMatchMode.REGEX -> runCatching { Regex(query.namePattern, opts).containsMatchIn(name) }.getOrDefault(false)
            NameMatchMode.PLAIN -> name.contains(query.namePattern, ignoreCase = !query.caseSensitive)
        }
    }

    fun globToRegex(glob: String, caseSensitive: Boolean): Regex {
        val sb = StringBuilder("^")
        for (c in glob) {
            when (c) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                '.', '(', ')', '+', '|', '^', '$', '@', '%', '{', '}', '[', ']', '\\' ->
                    sb.append('\\').append(c)
                else -> sb.append(c)
            }
        }
        sb.append('$')
        val opts = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        return Regex(sb.toString(), opts)
    }

    fun passesFilters(item: FileItem, query: SearchQuery): Boolean {
        if (!item.isDirectory) {
            query.size.min?.let { if (item.size < it) return false }
            query.size.max?.let { if (item.size > it) return false }
        }
        query.date.after?.let { if (item.lastModified < it) return false }
        query.date.before?.let { if (item.lastModified > it) return false }
        return true
    }

    fun searchContent(file: File, query: SearchQuery): List<ContentHit> {
        if (query.content.isBlank()) return emptyList()
        if (file.length() > MAX_CONTENT_BYTES) return emptyList()
        if (isBinary(file)) return emptyList()

        val opts = if (query.caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
        val regex = if (query.contentRegex) runCatching { Regex(query.content, opts) }.getOrNull() else null
        if (query.contentRegex && regex == null) return emptyList()

        val hits = ArrayList<ContentHit>()
        file.bufferedReader(Charsets.UTF_8).useLines { lines ->
            var lineNo = 0
            for (line in lines) {
                lineNo++
                val col = if (regex != null) {
                    regex.find(line)?.range?.first ?: -1
                } else {
                    line.indexOf(query.content, ignoreCase = !query.caseSensitive)
                }
                if (col >= 0) {
                    hits.add(ContentHit(lineNo, col, line.trim().take(PREVIEW_MAX)))
                    if (hits.size >= query.maxHitsPerFile) break
                }
            }
        }
        return hits
    }

    private fun isBinary(file: File): Boolean {
        return runCatching {
            file.inputStream().use { input ->
                val buf = ByteArray(BINARY_SNIFF_BYTES)
                val n = input.read(buf)
                for (i in 0 until n) {
                    if (buf[i].toInt() == 0) return true
                }
                false
            }
        }.getOrDefault(true)
    }
}
