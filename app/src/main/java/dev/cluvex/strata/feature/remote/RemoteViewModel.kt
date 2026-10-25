package dev.cluvex.strata.feature.remote

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.cluvex.strata.R
import dev.cluvex.strata.core.fs.FsUtil
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.i18n.trPlural
import dev.cluvex.strata.core.remote.RemoteConnection
import dev.cluvex.strata.core.remote.RemoteConnectionRepository
import dev.cluvex.strata.core.remote.RemoteEntry
import dev.cluvex.strata.core.remote.RemoteFileSystem
import dev.cluvex.strata.core.remote.RemoteFileSystems
import dev.cluvex.strata.core.remote.RemoteHostKeyChangedException
import dev.cluvex.strata.core.remote.RemotePaths
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class OpenedRemote(val local: File, val remotePath: String, val stamp: Long, val length: Long) {
    val modified: Boolean get() = local.exists() && (local.lastModified() != stamp || local.length() != length)
}

data class HostKeyChange(val connection: RemoteConnection, val passwordOverride: String?, val old: String, val presented: String)

data class RemoteSession(
    val connection: RemoteConnection,
    val path: String = "/",
    val entries: List<RemoteEntry> = emptyList(),
    val loading: Boolean = false,
)

data class RemoteUiState(
    val connections: List<RemoteConnection> = emptyList(),
    val session: RemoteSession? = null,
    val busy: String? = null,
    val needsPassword: RemoteConnection? = null,
    val openLocalFile: String? = null,
    val pendingUploads: List<OpenedRemote> = emptyList(),
    val hostKeyChange: HostKeyChange? = null,
    val message: String? = null,
)

class RemoteViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = RemoteConnectionRepository(app)

    private val _state = MutableStateFlow(RemoteUiState())
    val state: StateFlow<RemoteUiState> = _state.asStateFlow()

    private val io: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
    private var fs: RemoteFileSystem? = null
    private val opened = LinkedHashMap<String, OpenedRemote>()

    init {
        viewModelScope.launch { repo.connections.collect { list -> _state.update { it.copy(connections = list) } } }
    }

    fun saveConnection(connection: RemoteConnection) = viewModelScope.launch { repo.save(connection) }

    fun deleteConnection(id: String) = viewModelScope.launch { repo.remove(id) }

    fun connect(connection: RemoteConnection, passwordOverride: String? = null) {
        if (_state.value.busy != null) return
        val effective = passwordOverride?.let { connection.copy(password = it) } ?: connection
        val needsPw = !effective.anonymous && effective.password.isBlank()
        if (needsPw) {
            _state.update { it.copy(needsPassword = connection) }
            return
        }
        _state.update { it.copy(busy = tr(R.string.remote_connecting), needsPassword = null) }
        viewModelScope.launch {
            val result = runCatching {
                withContext(io) {
                    closeFs()
                    val newFs = RemoteFileSystems.create(effective)
                    newFs.connect()
                    fs = newFs
                    val start = effective.initialPath
                    start to newFs.list(start)
                }
            }
            result.onSuccess { (path, entries) ->
                val trusted = fs?.newlyTrustedHostKey()
                val pinned = if (trusted != null && effective.hostKey.isBlank()) effective.copy(hostKey = trusted) else effective
                if (trusted != null && effective.hostKey.isBlank() && connection.id.isNotBlank()) {
                    repo.save(pinned)
                }
                _state.update {
                    it.copy(busy = null, session = RemoteSession(pinned, path, entries.sorted()))
                }
            }.onFailure { e ->
                Log.w(TAG, "connect ${effective.displayAddress} failed", e)
                withContext(io) { closeFs() }
                if (e is RemoteHostKeyChangedException && e.presented != null) {
                    _state.update {
                        it.copy(busy = null, hostKeyChange = HostKeyChange(connection, passwordOverride, effective.hostKey, e.presented))
                    }
                } else {
                    _state.update { it.copy(busy = null, message = e.message ?: tr(R.string.remote_connect_failed)) }
                }
            }
        }
    }

    fun trustNewHostKey() {
        val change = _state.value.hostKeyChange ?: return
        val updated = change.connection.copy(hostKey = change.presented)
        _state.update { it.copy(hostKeyChange = null) }
        viewModelScope.launch {
            if (updated.id.isNotBlank()) repo.save(updated)
            connect(updated, change.passwordOverride)
        }
    }

    fun dismissHostKeyChange() = _state.update { it.copy(hostKeyChange = null) }

    fun disconnect() {
        viewModelScope.launch {
            withContext(io) { closeFs() }
            opened.clear()
            _state.update { it.copy(session = null, busy = null, pendingUploads = emptyList()) }
        }
    }

    fun navigateTo(path: String) {
        val session = _state.value.session ?: return
        if (_state.value.busy != null) return
        _state.update { it.copy(session = session.copy(loading = true)) }
        viewModelScope.launch {
            val result = runCatching { withContext(io) { requireFs().list(path) } }
            result.onSuccess { entries ->
                _state.update { it.copy(session = session.copy(path = RemotePaths.normalize(path), entries = entries.sorted(), loading = false)) }
            }.onFailure { e ->
                _state.update { it.copy(session = session.copy(loading = false), message = e.message ?: tr(R.string.remote_open_folder_failed)) }
            }
        }
    }

    fun up() {
        val session = _state.value.session ?: return
        if (session.path != "/") navigateTo(RemotePaths.parent(session.path))
    }

    fun refresh() = _state.value.session?.let { navigateTo(it.path) }

    fun open(entry: RemoteEntry) {
        if (entry.isDirectory) {
            navigateTo(entry.path)
            return
        }
        if (_state.value.busy != null) return
        _state.update { it.copy(busy = tr(R.string.remote_opening, entry.name)) }
        viewModelScope.launch {
            val result = runCatching {
                withContext(io) {
                    val connection = _state.value.session?.connection?.id ?: "remote"
                    val local = File(File(getApplication<Application>().cacheDir, "remote/$connection"), entry.path.removePrefix("/"))
                    local.parentFile?.mkdirs()
                    local.outputStream().use { out -> requireFs().download(entry.path, out) }
                    opened[entry.path] = OpenedRemote(local, entry.path, local.lastModified(), local.length())
                    local.absolutePath
                }
            }
            result.onSuccess { path -> _state.update { it.copy(busy = null, openLocalFile = path) } }
                .onFailure { e ->
                    Log.w(TAG, "open ${entry.path} failed", e)
                    _state.update { it.copy(busy = null, message = e.message ?: tr(R.string.remote_open_file_failed)) }
                }
        }
    }

    fun consumeOpenLocalFile() = _state.update { it.copy(openLocalFile = null) }

    fun download(entry: RemoteEntry, localDir: String, onDone: (String) -> Unit = {}) {
        _state.value.session ?: return
        if (_state.value.busy != null) return
        _state.update { it.copy(busy = tr(R.string.remote_downloading, entry.name)) }
        viewModelScope.launch {
            val result = runCatching {
                withContext(io) {
                    val dir = File(localDir)
                    if (!dir.isDirectory || !dir.canWrite()) throw java.io.IOException(tr(R.string.remote_cant_write_to, localDir))
                    val target = FsUtil.uniqueSibling(File(dir, entry.name), entry.isDirectory)
                    downloadRecursive(entry, target)
                    target.absolutePath
                }
            }
            result.onSuccess { saved ->
                _state.update { it.copy(busy = null, message = tr(R.string.remote_saved_to, saved)) }
                onDone(saved)
            }.onFailure { e ->
                Log.w(TAG, "download ${entry.path} failed", e)
                _state.update { it.copy(busy = null, message = e.message ?: tr(R.string.remote_download_failed)) }
            }
        }
    }

    private fun downloadRecursive(entry: RemoteEntry, target: File) {
        val fs = requireFs()
        if (entry.isDirectory) {
            target.mkdirs()
            fs.list(entry.path).forEach { downloadRecursive(it, File(target, it.name)) }
        } else {
            target.parentFile?.mkdirs()
            target.outputStream().use { out -> fs.download(entry.path, out) }
        }
    }

    fun checkModified() {
        if (_state.value.session == null) return
        _state.update { it.copy(pendingUploads = opened.values.filter { o -> o.modified }) }
    }

    fun uploadPending() {
        val pending = _state.value.pendingUploads
        val session = _state.value.session ?: return
        if (pending.isEmpty() || _state.value.busy != null) return
        _state.update { it.copy(busy = tr(R.string.remote_uploading), pendingUploads = emptyList()) }
        viewModelScope.launch {
            val result = runCatching {
                withContext(io) {
                    pending.forEach { o -> o.local.inputStream().use { input -> requireFs().upload(input, o.remotePath) } }
                    requireFs().list(session.path)
                }
            }
            result.onSuccess { entries ->
                pending.forEach { o -> opened[o.remotePath] = o.copy(stamp = o.local.lastModified(), length = o.local.length()) }
                _state.update { s ->
                    s.copy(busy = null, session = s.session?.copy(entries = entries.sorted()), message = trPlural(R.plurals.remote_uploaded, pending.size, pending.size))
                }
            }.onFailure { e ->
                Log.w(TAG, "upload failed", e)
                _state.update { it.copy(busy = null, message = e.message ?: tr(R.string.remote_upload_failed)) }
            }
        }
    }

    fun discardPending(deleteCopies: Boolean) {
        _state.value.pendingUploads.forEach { o ->
            if (deleteCopies) {
                opened.remove(o.remotePath)
                o.local.delete()
            } else {
                opened[o.remotePath] = o.copy(stamp = o.local.lastModified(), length = o.local.length())
            }
        }
        _state.update { it.copy(pendingUploads = emptyList()) }
    }

    fun makeDir(name: String) = mutate(tr(R.string.remote_creating_folder)) { fs, session ->
        fs.mkdir(RemotePaths.join(session.path, name.trim()))
    }

    fun deleteEntry(entry: RemoteEntry) = mutate(tr(R.string.remote_deleting)) { fs, _ ->
        fs.delete(entry.path, recursive = entry.isDirectory)
    }

    fun rename(entry: RemoteEntry, newName: String) = mutate(tr(R.string.remote_renaming)) { fs, session ->
        fs.rename(entry.path, RemotePaths.join(session.path, newName.trim()))
    }

    private inline fun mutate(label: String, crossinline op: (RemoteFileSystem, RemoteSession) -> Unit) {
        val session = _state.value.session ?: return
        if (_state.value.busy != null) return
        _state.update { it.copy(busy = label) }
        viewModelScope.launch {
            val result = runCatching { withContext(io) { op(requireFs(), session); requireFs().list(session.path) } }
            result.onSuccess { entries ->
                _state.update { it.copy(busy = null, session = session.copy(entries = entries.sorted())) }
            }.onFailure { e ->
                Log.w(TAG, "$label failed", e)
                _state.update { it.copy(busy = null, message = e.message ?: tr(R.string.remote_operation_failed)) }
            }
        }
    }

    fun dismissPasswordPrompt() = _state.update { it.copy(needsPassword = null) }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private fun requireFs(): RemoteFileSystem = fs ?: error(tr(R.string.remote_not_connected))

    private fun closeFs() {
        runCatching { fs?.close() }
        fs = null
    }

    override fun onCleared() {
        super.onCleared()
        runCatching { fs?.close() }
    }

    private companion object {
        const val TAG = "Strata/Remote"
    }

    private fun List<RemoteEntry>.sorted(): List<RemoteEntry> =
        sortedWith(compareByDescending<RemoteEntry> { it.isDirectory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
}
