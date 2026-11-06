package dev.cluvex.strata.feature.shell

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.cluvex.strata.core.shell.SavedCommand
import dev.cluvex.strata.core.shell.ShellBackend
import dev.cluvex.strata.core.shell.ShellCommandStore
import dev.cluvex.strata.core.shell.ShellExecutor
import dev.cluvex.strata.core.shell.ShellLogSource
import dev.cluvex.strata.core.shell.ShellLogStore
import dev.cluvex.strata.core.shell.ShellMode
import dev.cluvex.strata.core.shell.ShellResult
import dev.cluvex.strata.core.shell.ShellRunner
import dev.cluvex.strata.core.shell.ShellShortcuts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
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
    /** Commands that are running right now; several can run at the same time. */
    val runningIds: Set<String> = emptySet(),
    /** Background commands whose process is still alive; they stay marked until the process ends. */
    val backgroundIds: Set<String> = emptySet(),
    /** Finished runs whose result has not been dismissed yet; the first one is shown. */
    val runs: List<ShellRun> = emptyList(),
    /** Group names in display order (includes groups that have no commands yet). */
    val groups: List<String> = emptyList(),
    /** Names of the groups that are currently folded. */
    val collapsed: Set<String> = emptySet(),
) {
    val run: ShellRun? get() = runs.firstOrNull()
}

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
            scanBackground(list)
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
            val groups = _state.value.groups
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    getApplication<Application>().contentResolver.openOutputStream(uri, "wt")?.use {
                        it.write(store.encode(commands, groups).toByteArray())
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
                }.getOrNull()?.let { store.decodeBundle(it) }
            }
            if (incoming == null) {
                onDone(-1)
                return@launch
            }
            // Groups from the file that this device doesn't have yet are added at the end, in the file's order;
            // this also brings over groups that hold no commands.
            _state.update { st -> st.copy(groups = st.groups + incoming.groups.filter { it !in st.groups }) }
            var added = 0
            change { list ->
                added = 0
                val result = list.toMutableList()
                for (c in incoming.commands) {
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

    /** Moves a group one place up ([by] = -1) or down ([by] = 1) in the list of groups. */
    fun moveGroup(name: String, by: Int) {
        _state.update { st ->
            val from = st.groups.indexOf(name)
            val to = from + by
            if (from < 0 || to !in st.groups.indices) st
            else st.copy(groups = st.groups.toMutableList().apply { add(to, removeAt(from)) })
        }
        persistGroups()
    }

    /** Unfolds a group, e.g. after a command was dropped into it while it was folded. */
    fun expandGroup(name: String) {
        if (name !in _state.value.collapsed) return
        _state.update { st -> st.copy(collapsed = st.collapsed - name) }
        persistGroups()
    }

    fun toggleCollapsed(name: String) {
        _state.update { st -> st.copy(collapsed = if (name in st.collapsed) st.collapsed - name else st.collapsed + name) }
        persistGroups()
    }

    /**
     * Moves a command into [group] ("" = ungrouped) while it is being dragged: to the end of that group's commands,
     * or to the start of them. Nothing is written until [commitOrder].
     */
    fun moveToGroup(id: String, group: String, atEnd: Boolean) {
        _state.update { st ->
            val cmd = st.commands.firstOrNull { it.id == id } ?: return@update st
            if (cmd.group == group) return@update st
            val rest = st.commands.filterNot { it.id == id }
            val pos = if (atEnd) rest.indexOfLast { it.group == group }.let { if (it < 0) rest.size else it + 1 }
            else rest.indexOfFirst { it.group == group }.let { if (it < 0) rest.size else it }
            st.copy(
                commands = rest.toMutableList().apply { add(pos, cmd.copy(group = group)) },
                groups = if (group.isNotEmpty() && group !in st.groups) st.groups + group else st.groups,
            )
        }
    }

    /** Drops a command next to [anchorId], joining that command's group (or none). Nothing is written until [commitOrder]. */
    fun moveNextTo(id: String, anchorId: String, after: Boolean) {
        _state.update { st ->
            val cmd = st.commands.firstOrNull { it.id == id } ?: return@update st
            val anchor = st.commands.firstOrNull { it.id == anchorId } ?: return@update st
            val rest = st.commands.filterNot { it.id == id }
            val at = rest.indexOfFirst { it.id == anchorId }.let { if (after) it + 1 else it }
            st.copy(commands = rest.toMutableList().apply { add(at, cmd.copy(group = anchor.group)) })
        }
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
        val id = command.id
        if (id in _state.value.runningIds) return
        _state.update { it.copy(runningIds = it.runningIds + id) }
        viewModelScope.launch {
            // The output while it runs is published by ShellRunTracker, which the live views read.
            val result = ShellExecutor.runLogged(getApplication<Application>(), command, ShellLogSource.APP)
            _state.update { it.copy(runningIds = it.runningIds - id, runs = it.runs + ShellRun(command, result)) }
            val backend = result.backend
            if (command.detached && backend != null && !result.timedOut && result.exitCode == 0) {
                backgroundBackend[id] = backend
                _state.update { it.copy(backgroundIds = it.backgroundIds + id) }
                ensurePoller()
            }
        }
    }

    /** Which backend started each running background process; needed to read its log again later. */
    private val backgroundBackend = mutableMapOf<String, ShellBackend>()
    private var poller: Job? = null

    /** Re-checks the background processes every few seconds, for as long as any is marked as running. */
    private fun ensurePoller() {
        if (poller?.isActive == true) return
        poller = viewModelScope.launch {
            while (_state.value.backgroundIds.isNotEmpty()) {
                delay(BACKGROUND_POLL_MS)
                val st = _state.value
                val alive = ShellRunner.runningDetached(st.commands.filter { it.id in st.backgroundIds }) ?: continue
                _state.update { cur -> cur.copy(backgroundIds = cur.backgroundIds.filter { it in alive }.toSet()) }
            }
        }
    }

    /** Marks background commands that are still running from an earlier session (e.g. the app was closed meanwhile). */
    private fun scanBackground(list: List<SavedCommand>) {
        val detached = list.filter { it.detached }
        if (detached.isEmpty()) return
        viewModelScope.launch {
            val alive = ShellRunner.runningDetached(detached)?.takeIf { it.isNotEmpty() } ?: return@launch
            // The last logged run of a command says which backend started its process.
            val log = withContext(Dispatchers.IO) { ShellLogStore(getApplication<Application>()).load() }
            for (id in alive) {
                val name = log.firstOrNull { it.commandId == id && it.logFile != null }?.backend ?: continue
                runCatching { ShellBackend.valueOf(name) }.getOrNull()?.let { backgroundBackend[id] = it }
            }
            _state.update { it.copy(backgroundIds = it.backgroundIds + alive) }
            ensurePoller()
        }
    }

    /** The run to show when the log of a still-running background command is opened again. */
    fun backgroundRun(command: SavedCommand): ShellRun {
        val backend = backgroundBackend[command.id] ?: if (command.mode == ShellMode.ROOT) ShellBackend.ROOT else ShellBackend.SHIZUKU
        return ShellRun(command, ShellResult(backend, 0, ""))
    }

    /** Removes one finished result from the queue (that exact run), or the first when none is given. */
    fun dismissRun(run: ShellRun? = null) = _state.update { st ->
        val i = if (run == null) 0 else st.runs.indexOfFirst { it === run }
        if (i < 0 || i >= st.runs.size) st else st.copy(runs = st.runs.filterIndexed { idx, _ -> idx != i })
    }

    private companion object {
        const val BACKGROUND_POLL_MS = 3_000L
    }
}
