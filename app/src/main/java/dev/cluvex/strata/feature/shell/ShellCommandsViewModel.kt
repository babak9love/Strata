package dev.cluvex.strata.feature.shell

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.cluvex.strata.core.shell.SavedCommand
import dev.cluvex.strata.core.shell.ShellCommandStore
import dev.cluvex.strata.core.shell.ShellExecutor
import dev.cluvex.strata.core.shell.ShellLogSource
import dev.cluvex.strata.core.shell.ShellMode
import dev.cluvex.strata.core.shell.ShellResult
import dev.cluvex.strata.core.shell.ShellShortcuts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

data class ShellRun(val command: SavedCommand, val result: ShellResult)

data class ShellUiState(
    val commands: List<SavedCommand> = emptyList(),
    val loading: Boolean = true,
    val runningId: String? = null,
    val run: ShellRun? = null,
)

class ShellCommandsViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ShellCommandStore(app)
    private val saveLock = Mutex()
    private val _state = MutableStateFlow(ShellUiState())
    val state: StateFlow<ShellUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { store.load() }
            _state.update { it.copy(commands = list, loading = false) }
            withContext(Dispatchers.IO) { ShellShortcuts.publish(getApplication<Application>(), list) }
        }
    }

    /** Re-reads the saved commands, e.g. after a settings backup was restored while this screen was not open. */
    fun reload() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { saveLock.withLock { store.load() } }
            _state.update { it.copy(commands = list, loading = false) }
        }
    }

    fun save(id: String?, name: String, command: String, shortcut: Boolean, mode: ShellMode, timeoutSec: Int) {
        val cmd = command.trim()
        if (cmd.isEmpty()) return
        val label = name.trim().ifEmpty { cmd.lineSequence().first().take(40) }
        change { list ->
            if (id == null) list + SavedCommand(UUID.randomUUID().toString(), label, cmd, shortcut, mode, timeoutSec)
            else list.map { if (it.id == id) it.copy(name = label, command = cmd, shortcut = shortcut, mode = mode, timeoutSec = timeoutSec) else it }
        }
    }

    fun delete(id: String) = change { list -> list.filterNot { it.id == id } }

    /** Moves a command while it is being dragged; nothing is written until [commitOrder]. */
    fun move(from: Int, to: Int) {
        _state.update { st ->
            if (from !in st.commands.indices || to !in st.commands.indices || from == to) st
            else st.copy(commands = st.commands.toMutableList().apply { add(to, removeAt(from)) })
        }
    }

    /** Saves the current order and refreshes the launcher shortcuts, whose order follows the list. */
    fun commitOrder() = change { it }

    private fun change(edit: (List<SavedCommand>) -> List<SavedCommand>) {
        _state.update { it.copy(commands = edit(it.commands)) }
        viewModelScope.launch(Dispatchers.IO) {
            saveLock.withLock {
                val latest = _state.value.commands
                store.save(latest)
                ShellShortcuts.publish(getApplication<Application>(), latest)
            }
        }
    }

    fun run(command: SavedCommand) {
        if (_state.value.runningId != null) return
        _state.update { it.copy(runningId = command.id) }
        viewModelScope.launch {
            val result = ShellExecutor.runLogged(getApplication<Application>(), command, ShellLogSource.APP)
            _state.update { it.copy(runningId = null, run = ShellRun(command, result)) }
        }
    }

    fun dismissRun() = _state.update { it.copy(run = null) }
}
