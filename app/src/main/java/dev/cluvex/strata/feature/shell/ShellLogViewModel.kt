package dev.cluvex.strata.feature.shell

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.cluvex.strata.core.shell.LogEntry
import dev.cluvex.strata.core.shell.ShellCommandStore
import dev.cluvex.strata.core.shell.ShellMode
import dev.cluvex.strata.core.shell.ShellRunner
import dev.cluvex.strata.core.shell.ShellLogStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ShellLogUiState(
    val entries: List<LogEntry> = emptyList(),
    val loading: Boolean = true,
    /** Ids of background entries whose process is still running (the newest entry of each command). */
    val runningEntryIds: Set<String> = emptySet(),
)

class ShellLogViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ShellLogStore(app)
    private val _state = MutableStateFlow(ShellLogUiState())
    val state: StateFlow<ShellLogUiState> = _state.asStateFlow()

    private var poller: Job? = null

    fun reload() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { store.load() }
            // The running marks stay until the next check, so the list does not flicker on every reload.
            _state.update { it.copy(entries = list, loading = false) }
            refreshRunning()
        }
    }

    /**
     * Marks the background entries whose process is still alive. Only the newest entry of a command can be running,
     * because a command has one pid file that a new start overwrites. Keeps checking every few seconds while any runs.
     */
    private suspend fun refreshRunning() {
        val newest = _state.value.entries
            .filter { it.logFile != null }
            .groupBy { it.commandId }
            .map { (_, runs) -> runs.maxBy { it.time } }
        val alive = mutableSetOf<String>()
        // Each entry is checked through the backend that started it.
        for ((backend, group) in newest.groupBy { it.backend }) {
            val mode = when (backend) {
                "ROOT" -> ShellMode.ROOT
                "SHIZUKU" -> ShellMode.SHIZUKU
                else -> continue
            }
            val ids = ShellRunner.runningDetachedIds(group.map { it.commandId }, mode) ?: return
            group.filter { it.commandId in ids }.forEach { alive += it.id }
        }
        _state.update { it.copy(runningEntryIds = alive) }
        checkTimeouts(newest, alive)
        if (alive.isNotEmpty() && poller?.isActive != true) {
            poller = viewModelScope.launch {
                while (_state.value.runningEntryIds.isNotEmpty()) {
                    delay(RUNNING_POLL_MS)
                    refreshRunning()
                }
            }
        }
    }

    /** Entries whose log was already checked for a timeout line, so the shell is not asked again. */
    private val checkedForTimeout = mutableSetOf<String>()

    /**
     * A background command's entry is written when it starts, so a timeout that comes later is only visible in its
     * log file. Looks there for the watchdog's line once the process has ended, and records it on the entry.
     */
    private suspend fun checkTimeouts(newest: List<LogEntry>, alive: Set<String>) {
        val now = System.currentTimeMillis()
        for (e in newest) {
            val file = e.logFile ?: continue
            // Too fresh to judge (the pid file may not exist yet); older entries' files belong to newer runs.
            if (e.timedOut || e.id in alive || e.id in checkedForTimeout || now - e.time < 5_000) continue
            val mode = when (e.backend) {
                "ROOT" -> ShellMode.ROOT
                "SHIZUKU" -> ShellMode.SHIZUKU
                else -> continue
            }
            checkedForTimeout += e.id
            val sec = ShellRunner.detachedTimeoutSec(file, mode) ?: continue
            withContext(Dispatchers.IO) { store.markTimedOut(e.id, sec * 1000L) }
            _state.update { st -> st.copy(entries = st.entries.map { if (it.id == e.id) it.copy(timedOut = true, durationMs = sec * 1000L) else it }) }
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

    private companion object {
        const val RUNNING_POLL_MS = 3_000L
    }
}
