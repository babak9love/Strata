package dev.cluvex.strata.feature.shell

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.cluvex.strata.core.shell.SavedCommand
import dev.cluvex.strata.core.shell.ShellCommandStore
import dev.cluvex.strata.core.shell.ShellExecutor
import dev.cluvex.strata.core.shell.ShellLogSource
import dev.cluvex.strata.core.shell.ShellMode
import dev.cluvex.strata.core.shell.ShellResult
import dev.cluvex.strata.core.shell.ShellRunner
import dev.cluvex.strata.core.shell.ShellShortcuts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.UUID

data class ShellRun(val command: SavedCommand, val result: ShellResult)

data class ShellUiState(
    val commands: List<SavedCommand> = emptyList(),
    val loading: Boolean = true,
    val runningId: String? = null,
    val run: ShellRun? = null,
    /** Group names in display order (includes groups that have no commands yet). */
    val groups: List<String> = emptyList(),
    /** Names of the groups that are currently folded. */
    val collapsed: Set<String> = emptySet(),
)

class ShellCommandsViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ShellCommandStore(app)
    private val saveLock = Mutex()
    private val prefs = app.getSharedPreferences("shell_ui", android.content.Context.MODE_PRIVATE)
    private val names = ListSerializer(String.serializer())

    private fun loadNames(key: String): List<String> =
        runCatching { Json.decodeFromString(names, prefs.getString(key, "[]") ?: "[]") }.getOrDefault(emptyList())

    private fun persistGroups() {
        val st = _state.value
        prefs.edit()
            .putString("groups", Json.encodeToString(names, st.groups))
            .putString("collapsed", Json.encodeToString(names, st.collapsed.toList()))
            .apply()
    }

    /** Stored group order, plus any group named by a command (e.g. after an import) that is not stored yet. */
    private fun mergeGroups(stored: List<String>, commands: List<SavedCommand>): List<String> {
        val result = stored.toMutableList()
        for (c in commands) if (c.group.isNotEmpty() && c.group !in result) result += c.group
        return result
    }
    private val _state = MutableStateFlow(ShellUiState())
    val state: StateFlow<ShellUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { store.load() }
            _state.update { it.copy(commands = list, groups = mergeGroups(loadNames("groups"), list), collapsed = loadNames("collapsed").toSet(), loading = false) }
            withContext(Dispatchers.IO) { ShellShortcuts.publish(getApplication<Application>(), list) }
        }
    }

    /** Re-reads the saved commands, e.g. after a settings backup was restored while this screen was not open. */
    fun reload() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { saveLock.withLock { store.load() } }
            _state.update { it.copy(commands = list, groups = mergeGroups(loadNames("groups"), list), loading = false) }
        }
    }

    fun save(
        id: String?,
        name: String,
        command: String,
        shortcut: Boolean,
        mode: ShellMode,
        timeoutSec: Int,
        detached: Boolean = false,
        confirm: Boolean = false,
        showStatus: Boolean = true,
        group: String = "",
    ) {
        val cmd = command.trim()
        if (cmd.isEmpty()) return
        val label = name.trim().ifEmpty { cmd.lineSequence().first().take(40) }
        _state.value.commands.firstOrNull { it.id == id }?.let { old -> if (old.detached && !detached) cleanupFiles(old) }
        change { list ->
            if (id == null) list + SavedCommand(UUID.randomUUID().toString(), label, cmd, shortcut, mode, timeoutSec, detached, confirm, showStatus, group.trim())
            else list.map { if (it.id == id) it.copy(name = label, command = cmd, shortcut = shortcut, mode = mode, timeoutSec = timeoutSec, detached = detached, confirm = confirm, showStatus = showStatus, group = group.trim()) else it }
        }
    }

    /** Inserts a copy right after the original; the copy is not shown in the app-icon menu until it is edited. */
    fun duplicate(id: String, copyName: (String) -> String) = change { list ->
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) list
        else {
            val original = list[index]
            val copy = original.copy(id = UUID.randomUUID().toString(), name = copyName(original.name), shortcut = false)
            list.toMutableList().apply { add(index + 1, copy) }
        }
    }

    /** Writes every saved command to [uri] as JSON; [onDone] gets the number written, or -1 on failure. */
    fun exportTo(uri: Uri, onDone: (Int) -> Unit) {
        viewModelScope.launch {
            val commands = _state.value.commands
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use {
                        it.write(store.encode(commands).toByteArray())
                    } != null
                }.getOrDefault(false)
            }
            onDone(if (ok) commands.size else -1)
        }
    }

    /** Adds the commands found in [uri]; exact duplicates are skipped. [onDone] gets the number added, or -1 on failure. */
    fun importFrom(uri: Uri, onDone: (Int) -> Unit) {
        viewModelScope.launch {
            val incoming = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { String(it.readBytes()) }
                }.getOrNull()?.let { store.decode(it) }
            }
            if (incoming == null) {
                onDone(-1)
                return@launch
            }
            var added = 0
            change { list ->
                added = 0
                val result = list.toMutableList()
                for (c in incoming) {
                    if (c.command.isBlank()) continue
                    if (result.any { it.name == c.name && it.command == c.command }) continue
                    val id = if (result.any { it.id == c.id }) UUID.randomUUID().toString() else c.id
                    result += c.copy(id = id)
                    added++
                }
                result
            }
            onDone(added)
        }
    }

    fun delete(id: String) {
        _state.value.commands.firstOrNull { it.id == id }?.let { cleanupFiles(it) }
        change { list -> list.filterNot { it.id == id } }
    }

    /** Deletes the background script and its log for a command that no longer uses them; best effort. */
    private fun cleanupFiles(command: SavedCommand) {
        if (!command.detached) return
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { ShellRunner.run(ShellRunner.detachedCleanup(command.id), command.mode, 10) }
        }
    }

    /** Moves a command while it is being dragged; nothing is written until [commitOrder]. */
    fun move(from: Int, to: Int) {
        _state.update { st ->
            if (from !in st.commands.indices || to !in st.commands.indices || from == to) st
            else st.copy(commands = st.commands.toMutableList().apply { add(to, removeAt(from)) })
        }
    }

    /** Saves the current order and refreshes the launcher shortcuts, whose order follows the list. */
    fun commitOrder() = change { it }

    fun addGroup(name: String) {
        val n = name.trim()
        if (n.isEmpty() || n in _state.value.groups) return
        _state.update { it.copy(groups = it.groups + n) }
        persistGroups()
    }

    fun renameGroup(old: String, new: String) {
        val n = new.trim()
        if (n.isEmpty() || n == old || n in _state.value.groups) return
        _state.update { st ->
            st.copy(
                groups = st.groups.map { if (it == old) n else it },
                collapsed = if (old in st.collapsed) st.collapsed - old + n else st.collapsed,
            )
        }
        change { list -> list.map { if (it.group == old) it.copy(group = n) else it } }
    }

    /** Deletes only the group: its commands stay and become ungrouped. */
    fun deleteGroup(name: String) {
        _state.update { st -> st.copy(groups = st.groups - name, collapsed = st.collapsed - name) }
        change { list -> list.map { if (it.group == name) it.copy(group = "") else it } }
    }

    fun toggleCollapsed(name: String) {
        _state.update { st -> st.copy(collapsed = if (name in st.collapsed) st.collapsed - name else st.collapsed + name) }
        persistGroups()
    }

    /** Swaps two commands while one is being dragged; nothing is written until [commitOrder]. */
    fun swapCommands(a: String, b: String) {
        _state.update { st ->
            val ia = st.commands.indexOfFirst { it.id == a }
            val ib = st.commands.indexOfFirst { it.id == b }
            if (ia < 0 || ib < 0 || ia == ib) st
            else st.copy(commands = st.commands.toMutableList().apply { val t = this[ia]; this[ia] = this[ib]; this[ib] = t })
        }
    }

    private fun change(edit: (List<SavedCommand>) -> List<SavedCommand>) {
        _state.update { st -> val cmds = edit(st.commands); st.copy(commands = cmds, groups = mergeGroups(st.groups, cmds)) }
        persistGroups()
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
