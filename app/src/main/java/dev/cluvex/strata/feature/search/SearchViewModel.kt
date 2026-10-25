package dev.cluvex.strata.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.cluvex.strata.core.model.FileItem
import dev.cluvex.strata.core.search.NameMatchMode
import dev.cluvex.strata.core.search.SearchEngine
import dev.cluvex.strata.core.search.SearchEvent
import dev.cluvex.strata.core.search.SearchQuery
import dev.cluvex.strata.core.search.SearchResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class TypeFilter { ALL, FILES, DIRS }

data class SearchUiState(
    val root: String = "",
    val namePattern: String = "",
    val nameMode: NameMatchMode = NameMatchMode.GLOB,
    val content: String = "",
    val contentRegex: Boolean = false,
    val caseSensitive: Boolean = false,
    val includeSubdirs: Boolean = true,
    val includeHidden: Boolean = false,
    val typeFilter: TypeFilter = TypeFilter.ALL,
    val running: Boolean = false,
    val scanned: Int = 0,
    val results: List<SearchResult> = emptyList(),
    val finished: Boolean = false,
    val stoppedEarly: Boolean = false,
    val selected: Set<String> = emptySet(),
)

class SearchViewModel : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var job: Job? = null

    /** Every result found so far; the UI list is only refreshed in batches, so Stop flushes this. */
    private var found = ArrayList<SearchResult>()

    fun initRoot(root: String) {
        if (_state.value.root.isEmpty()) _state.update { it.copy(root = root) }
    }

    fun setNamePattern(v: String) = _state.update { it.copy(namePattern = v) }
    fun setNameMode(v: NameMatchMode) = _state.update { it.copy(nameMode = v) }
    fun setContent(v: String) = _state.update { it.copy(content = v) }
    fun setContentRegex(v: Boolean) = _state.update { it.copy(contentRegex = v) }
    fun setCaseSensitive(v: Boolean) = _state.update { it.copy(caseSensitive = v) }
    fun setIncludeSubdirs(v: Boolean) = _state.update { it.copy(includeSubdirs = v) }
    fun setIncludeHidden(v: Boolean) = _state.update { it.copy(includeHidden = v) }
    fun setTypeFilter(v: TypeFilter) = _state.update { it.copy(typeFilter = v) }

    fun start() {
        val s = _state.value
        if (s.root.isEmpty()) return
        job?.cancel()
        val query = SearchQuery(
            root = s.root,
            namePattern = s.namePattern.trim(),
            nameMode = s.nameMode,
            caseSensitive = s.caseSensitive,
            includeSubdirs = s.includeSubdirs,
            includeHidden = s.includeHidden,
            onlyFiles = s.typeFilter == TypeFilter.FILES,
            onlyDirs = s.typeFilter == TypeFilter.DIRS,
            content = s.content,
            contentRegex = s.contentRegex,
        )
        _state.update { it.copy(running = true, results = emptyList(), scanned = 0, finished = false, stoppedEarly = false, selected = emptySet()) }
        val acc = ArrayList<SearchResult>()
        found = acc
        job = viewModelScope.launch {
            SearchEngine.search(query).collect { event ->
                when (event) {
                    is SearchEvent.Progress -> _state.update { it.copy(scanned = event.scanned) }
                    is SearchEvent.Found -> {
                        acc.add(event.result)
                        if (acc.size <= 20 || acc.size % 20 == 0) {
                            _state.update { it.copy(results = acc.toList()) }
                        }
                    }
                    is SearchEvent.Finished -> _state.update {
                        it.copy(results = acc.toList(), running = false, finished = true, stoppedEarly = event.stoppedEarly)
                    }
                }
            }
        }
    }

    fun toggleSelect(path: String) = _state.update {
        val next = it.selected.toMutableSet()
        if (!next.add(path)) next.remove(path)
        it.copy(selected = next)
    }

    fun selectAll() = _state.update { it.copy(selected = it.results.map { r -> r.item.path }.toSet()) }

    fun invertSelection() = _state.update {
        it.copy(selected = it.results.map { r -> r.item.path }.filterNot { p -> p in it.selected }.toSet())
    }

    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }

    fun removeResults(paths: Set<String>) = _state.update {
        it.copy(results = it.results.filterNot { r -> r.item.path in paths }, selected = it.selected - paths)
    }

    fun renameResult(oldPath: String, renamed: FileItem) = _state.update {
        it.copy(
            results = it.results.map { r -> if (r.item.path == oldPath) r.copy(item = renamed) else r },
            selected = emptySet(),
        )
    }

    fun stop() {
        job?.cancel()
        val all = found.toList()
        _state.update { if (it.running) it.copy(results = all, running = false, finished = true) else it.copy(running = false, finished = true) }
    }

    override fun onCleared() {
        job?.cancel()
    }
}
