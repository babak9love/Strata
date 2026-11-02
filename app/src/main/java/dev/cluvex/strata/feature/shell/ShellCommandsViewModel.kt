package dev.cluvex.strata.feature.shell

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.cluvex.strata.core.shell.SavedCommand
import dev.cluvex.strata.core.shell.ShellCommandStore
import dev.cluvex.strata.core.shell.ShellResult
import dev.cluvex.strata.core.shell.ShellRunner
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
        }
    }

    fun save(id: String?, name: String, command: String) {
        val cmd = command.trim()
        if (cmd.isEmpty()) return
        val label = name.trim().ifEmpty { cmd.lineSequence().first().take(40) }
        change { list ->
            if (id == null) list + SavedCommand(UUID.randomUUID().toString(), label, cmd)
            else list.map { if (it.id == id) it.copy(name = label, command = cmd) else it }
        }
    }

    fun delete(id: String) = change { list -> list.filterNot { it.id == id } }

    private fun change(edit: (List<SavedCommand>) -> List<SavedCommand>) {
        _state.update { it.copy(commands = edit(it.commands)) }
        viewModelScope.launch(Dispatchers.IO) {
            saveLock.withLock { store.save(_state.value.commands) }
        }
    }

    fun run(command: SavedCommand) {
        if (_state.value.runningId != null) return
        _state.update { it.copy(runningId = command.id) }
        viewModelScope.launch {
            val result = ShellRunner.run(command.command)
            _state.update { it.copy(runningId = null, run = ShellRun(command, result)) }
        }
    }

    fun dismissRun() = _state.update { it.copy(run = null) }
}
