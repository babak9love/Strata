package dev.cluvex.strata.feature.trash

import dev.cluvex.strata.core.i18n.separator
import android.os.Environment
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.cluvex.strata.R
import dev.cluvex.strata.core.fs.Conflict
import dev.cluvex.strata.core.fs.OpProgress
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.sound.UiSounds
import dev.cluvex.strata.core.trash.RecycleBin
import dev.cluvex.strata.core.trash.TrashEntry
import dev.cluvex.strata.feature.explorer.TaskState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class RecycleBinUiState(
    val entries: List<TrashEntry> = emptyList(),
    val selected: Set<String> = emptySet(),
    val loading: Boolean = true,
    val task: TaskState? = null,
    val message: String? = null,
) {
    val totalSize: Long get() = entries.sumOf { it.info.size }
    val selectedEntries: List<TrashEntry> get() = entries.filter { it.info.id in selected }
}

class RecycleBinViewModel(
    private val bin: RecycleBin = RecycleBin.forStorage(
        Environment.getExternalStorageDirectory()?.absolutePath ?: "/storage/emulated/0",
    ),
) : ViewModel() {

    private val _state = MutableStateFlow(RecycleBinUiState())
    val state: StateFlow<RecycleBinUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val entries = withContext(Dispatchers.IO) { bin.list() }
            _state.update { s ->
                s.copy(entries = entries, loading = false, selected = s.selected.filter { id -> entries.any { it.info.id == id } }.toSet())
            }
        }
    }

    fun toggle(id: String) = _state.update {
        it.copy(selected = if (id in it.selected) it.selected - id else it.selected + id)
    }

    fun selectAll() = _state.update { s -> s.copy(selected = s.entries.map { it.info.id }.toSet()) }
    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }

    fun restore(entries: List<TrashEntry>) =
        run(R.string.trash_task_restoring, "Restoring", bin.restore(entries, Conflict.KEEP_BOTH), R.string.trash_done_restored)

    fun purge(entries: List<TrashEntry>) = run(R.string.trash_task_purging, "Deleting permanently", bin.purge(entries), R.string.trash_done_deleted)

    fun empty() = run(R.string.trash_task_emptying, "Emptying recycle bin", bin.empty(), R.string.trash_done_removed)

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private fun run(@StringRes titleRes: Int, soundKey: String, flow: Flow<OpProgress>, @StringRes done: Int) {
        val title = tr(titleRes)
        viewModelScope.launch {
            _state.update { it.copy(task = TaskState(title, indeterminate = true)) }
            flow.collect { p ->
                when (p) {
                    is OpProgress.Running -> _state.update {
                        it.copy(
                            task = TaskState(
                                title = title,
                                detail = p.currentName,
                                fraction = if (p.totalFiles > 0) p.doneFiles.toFloat() / p.totalFiles else 0f,
                                indeterminate = p.totalFiles <= 0,
                            ),
                        )
                    }
                    is OpProgress.Finished -> {
                        UiSounds.forTask(soundKey, failed = p.failed.isNotEmpty(), okCount = p.okCount)
                        val msg = buildList {
                            add(tr(done, p.okCount))
                            if (p.failed.isNotEmpty()) add(tr(R.string.trash_done_failed, p.failed.size, p.failed.first()))
                            if (p.skipped.isNotEmpty()) add(tr(R.string.trash_done_skipped, p.skipped.size))
                        }.joinToString(separator())
                        _state.update { it.copy(task = null, message = msg, selected = emptySet()) }
                        load()
                    }
                }
            }
        }
    }
}
