package dev.cluvex.strata.feature.shell

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.cluvex.strata.core.shell.LogEntry
import dev.cluvex.strata.core.shell.ShellCommandStore
import dev.cluvex.strata.core.shell.ShellRunner
import dev.cluvex.strata.core.shell.ShellLogStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ShellLogUiState(val entries: List<LogEntry> = emptyList(), val loading: Boolean = true)

class ShellLogViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ShellLogStore(app)
    private val _state = MutableStateFlow(ShellLogUiState())
    val state: StateFlow<ShellLogUiState> = _state.asStateFlow()

    fun reload() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { store.load() }
            _state.value = ShellLogUiState(list, loading = false)
        }
    }

    /** Clears the log, and also removes the background scripts, logs and pid files left in /data/local/tmp (running ones are kept). */
    fun clear() {
        val ids = _state.value.entries.filter { it.logFile != null }.map { it.commandId }.toSet()
        viewModelScope.launch {
            val detachedIds = withContext(Dispatchers.IO) {
                store.clear()
                ids + ShellCommandStore(getApplication<Application>()).load().filter { it.detached }.map { it.id }
            }
            _state.value = ShellLogUiState(emptyList(), loading = false)
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { ShellRunner.cleanupIdleDetached(detachedIds) }
        }
    }
}
