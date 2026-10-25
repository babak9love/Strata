package dev.cluvex.strata.feature.props

import android.os.Environment
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.cluvex.strata.R
import dev.cluvex.strata.core.fs.FileAccess
import dev.cluvex.strata.core.fs.FileOperations
import dev.cluvex.strata.core.fs.LocalFileAccess
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.model.FileItem
import dev.cluvex.strata.core.props.DirStats
import dev.cluvex.strata.core.props.DirectoryStats
import dev.cluvex.strata.core.props.OsPosixApi
import dev.cluvex.strata.core.props.PermissionApplier
import dev.cluvex.strata.core.props.PosixApi
import dev.cluvex.strata.core.props.PosixStat
import dev.cluvex.strata.core.root.RootManager
import dev.cluvex.strata.core.root.RootPaths
import dev.cluvex.strata.core.root.RootPosixApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class PropertiesUiState(
    val items: List<FileItem> = emptyList(),
    val stats: DirStats? = null,
    val counting: Boolean = false,
    val stat: PosixStat? = null,
    val owner: String? = null,
    val group: String? = null,
    val linkTarget: String? = null,
    val statError: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val viaRoot: Boolean = false,
    val changes: Int = 0,
) {
    val single: FileItem? get() = items.singleOrNull()
    val hasDirectories: Boolean get() = items.any { it.isDirectory }
}

class PropertiesViewModel(
    private val localApi: PosixApi = OsPosixApi(),
    private val rootApi: () -> PosixApi = { RootPosixApi() },
) : ViewModel() {

    private val _state = MutableStateFlow(PropertiesUiState())
    val state: StateFlow<PropertiesUiState> = _state.asStateFlow()

    private var countJob: Job? = null
    private var api: PosixApi = localApi
    private var access: FileAccess = LocalFileAccess

    fun load(items: List<FileItem>, rootEnabled: Boolean = false) {
        countJob?.cancel()
        _state.value = PropertiesUiState(items = items, counting = true)
        api = localApi
        access = LocalFileAccess
        countJob = viewModelScope.launch {
            val root = if (rootEnabled && withContext(Dispatchers.IO) { items.any(::needsRoot) }) RootManager.access() else null
            if (root != null) {
                api = rootApi()
                access = root
                _state.update { it.copy(viaRoot = true) }
            }
            if (items.size == 1) refreshStat()
            DirectoryStats.stream(items.map { it.path }, access).collect { s -> _state.update { it.copy(stats = s) } }
            _state.update { it.copy(counting = false) }
        }
    }

    private fun needsRoot(item: FileItem): Boolean {
        val storage = Environment.getExternalStorageDirectory()?.absolutePath ?: "/storage/emulated/0"
        val parent = File(item.parentPath ?: item.path)
        return !File(item.path).canRead() || RootPaths.needsRoot(parent, storage, write = true)
    }

    private fun refreshStat() {
        val path = _state.value.single?.path ?: return
        val api = api
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val st = api.lstat(path)
                    StatBundle(st, api.userName(st.uid), api.groupName(st.gid), if (st.isSymlink) api.readlink(path) else null)
                }
            }
            result.onSuccess { b ->
                _state.update { it.copy(stat = b.stat, owner = b.owner, group = b.group, linkTarget = b.link, statError = null) }
            }.onFailure { e ->
                _state.update { it.copy(statError = e.message ?: tr(R.string.props_stat_error)) }
            }
        }
    }

    private class StatBundle(val stat: PosixStat, val owner: String?, val group: String?, val link: String?)

    fun applyPermissions(mode: Int, subFiles: Boolean, subDirs: Boolean) =
        applyTree(R.string.props_permissions_updated) { paths -> PermissionApplier(api, access).apply(paths, mode, subFiles, subDirs) }

    fun applyOwner(uid: Int, gid: Int, subFiles: Boolean, subDirs: Boolean) =
        applyTree(R.string.props_owner_updated) { paths -> PermissionApplier(api, access).applyOwner(paths, uid, gid, subFiles, subDirs) }

    private fun applyTree(@StringRes done: Int, block: (List<String>) -> PermissionApplier.Result) {
        val paths = _state.value.items.map { it.path }
        if (paths.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val result = withContext(Dispatchers.IO) { block(paths) }
            val msg = if (result.failed.isEmpty()) {
                tr(done, result.changed)
            } else {
                tr(R.string.props_updated_failed, result.changed, result.failed.size, result.failed.first())
            }
            _state.update { it.copy(busy = false, message = msg, changes = it.changes + 1) }
            if (_state.value.items.size == 1) refreshStat()
        }
    }

    fun applyModifiedTime(millis: Long) {
        val paths = _state.value.items.map { it.path }
        if (paths.isEmpty()) return
        val access = access
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val ok = FileOperations.setModified(paths, millis, access)
            val refreshed = withContext(Dispatchers.IO) { paths.map { FileOperations.itemOf(access.file(it), access) } }
            val msg = if (ok == paths.size) tr(R.string.props_time_updated) else tr(R.string.props_updated_some, ok, paths.size)
            _state.update { it.copy(busy = false, items = refreshed, message = msg, changes = it.changes + 1) }
            if (refreshed.size == 1) refreshStat()
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun notify(message: String) = _state.update { it.copy(message = message) }

    override fun onCleared() {
        countJob?.cancel()
    }
}
