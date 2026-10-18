@file:OptIn(kotlinx.coroutines.FlowPreview::class)

package dev.cluvex.strata.feature.explorer

import dev.cluvex.strata.core.i18n.separator
import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.i18n.trPlural
import dev.cluvex.strata.core.vault.Vault
import dev.cluvex.strata.core.vault.VaultLockedException
import dev.cluvex.strata.core.vault.VaultStore
import dev.cluvex.strata.core.vault.VaultTransfers
import android.app.Application
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.cluvex.strata.core.apk.ApkSigning
import dev.cluvex.strata.core.apk.KeyStoreFactory
import dev.cluvex.strata.core.apk.KeystoreFile
import dev.cluvex.strata.core.apk.KeystoreFormat
import dev.cluvex.strata.core.apk.SavedKeys
import dev.cluvex.strata.core.apk.SigningKey
import dev.cluvex.strata.core.apk.SigningKeys
import dev.cluvex.strata.core.archive.ArchiveCreator
import dev.cluvex.strata.core.archive.ArchiveManager
import dev.cluvex.strata.core.archive.ArchiveOptions
import dev.cluvex.strata.core.archive.PasswordRequiredException
import dev.cluvex.strata.core.archive.WrongPasswordException
import dev.cluvex.strata.core.bookmarks.Bookmark
import dev.cluvex.strata.core.bookmarks.BookmarkRepository
import dev.cluvex.strata.core.bookmarks.Bookmarks
import dev.cluvex.strata.core.crypto.FileCipher
import dev.cluvex.strata.core.crypto.IncorrectPasswordException
import dev.cluvex.strata.core.data.SettingsRepository
import dev.cluvex.strata.core.data.StartFolders
import dev.cluvex.strata.core.fs.DropRules
import dev.cluvex.strata.core.fs.DropVerdict
import dev.cluvex.strata.core.fs.TopLevel
import dev.cluvex.strata.core.fs.Conflict
import dev.cluvex.strata.core.fs.DirectoryLister
import dev.cluvex.strata.core.dex.DexConvert
import dev.cluvex.strata.core.fs.FileAccess
import dev.cluvex.strata.core.fs.FileOperations
import dev.cluvex.strata.core.fs.FsUtil
import dev.cluvex.strata.core.fs.LinkRequest
import dev.cluvex.strata.core.fs.Listing
import dev.cluvex.strata.core.fs.Mounts
import dev.cluvex.strata.core.fs.MountEntry
import dev.cluvex.strata.core.fs.LocalFileAccess
import dev.cluvex.strata.core.fs.OpProgress
import dev.cluvex.strata.core.model.ConflictChoice
import dev.cluvex.strata.core.model.FileItem
import dev.cluvex.strata.core.pdf.PdfMerger
import dev.cluvex.strata.core.model.SortOrder
import dev.cluvex.strata.core.open.IncomingFiles
import dev.cluvex.strata.core.open.IncomingPaths
import dev.cluvex.strata.core.plugin.PluginFiles
import dev.cluvex.strata.core.remote.Place
import dev.cluvex.strata.core.remote.RemoteConnection
import dev.cluvex.strata.core.remote.RemoteConnectionRepository
import dev.cluvex.strata.core.remote.RemoteHostKeyChangedException
import dev.cluvex.strata.core.remote.RemotePasswordNeededException
import dev.cluvex.strata.core.remote.RemoteSessions
import dev.cluvex.strata.core.remote.RemoteTransfers
import dev.cluvex.strata.core.remote.RemoteUri
import dev.cluvex.strata.core.rename.AndroidRenameInfo
import dev.cluvex.strata.core.rename.BatchRename
import dev.cluvex.strata.core.rename.RenameOrder
import dev.cluvex.strata.core.rename.RenamePlan
import dev.cluvex.strata.core.rename.RenameRule
import dev.cluvex.strata.core.root.RootManager
import dev.cluvex.strata.core.shizuku.ShizukuManager
import dev.cluvex.strata.core.root.RootPaths
import dev.cluvex.strata.core.sound.UiSound
import dev.cluvex.strata.core.sound.UiSounds
import dev.cluvex.strata.core.tools.FileSplitter
import dev.cluvex.strata.core.tools.SplitMethod
import dev.cluvex.strata.core.trash.RecycleBin
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ExplorerViewModel(
    app: Application,
    private val settingsRepo: SettingsRepository = SettingsRepository(app),
    private val bookmarkRepo: BookmarkRepository = BookmarkRepository(app),
    private val remoteRepo: RemoteConnectionRepository = RemoteConnectionRepository(app),
) : AndroidViewModel(app) {

    val rootPath: String = Environment.getExternalStorageDirectory()?.absolutePath ?: "/storage/emulated/0"

    val recycleBin: RecycleBin = RecycleBin.forStorage(rootPath)

    private val sessions = RemoteSessions(
        lookup = { id -> if (id == Vault.ID) Vault.connection() else remoteRepo.connections.first().firstOrNull { it.id == id } },
        onTrusted = { c -> remoteRepo.save(c) },
    )

    private val remoteOpened = LinkedHashMap<String, RemoteOpened>()

    private data class RemoteOpened(val uri: String, val stamp: Long, val length: Long)

    private val lister = DirectoryLister(
        storageRoot = rootPath,
        local = { p -> withContext(Dispatchers.IO) { File(p).takeIf { it.isDirectory }?.listFiles()?.map { FileItem.of(it) } } },
        root = { p -> privilegedAccess()?.let { a -> withContext(Dispatchers.IO) { a.list(p) } } },
        missing = { p -> isMissing(p) },
        fallback = { p -> if (p == "/") withContext(Dispatchers.IO) { TopLevel.probe() } else null },
        remote = { uri -> listRemote(uri) },
    )

    private val _state = MutableStateFlow(
        ExplorerUiState(left = PaneState(path = rootPath), right = PaneState(path = rootPath)),
    )
    val state: StateFlow<ExplorerUiState> = _state.asStateFlow()

    private val navJobs = mutableMapOf<Pane, Job>()

    private val rootEnabled: Boolean get() = _state.value.settings.rootAccess
    private val shizukuEnabled: Boolean get() = _state.value.settings.shizukuAccess

    private suspend fun privilegedAccess(prompt: Boolean = true): dev.cluvex.strata.core.root.RootFileAccess? {
        if (rootEnabled) RootManager.access()?.let { return it }
        if (!shizukuEnabled) return null
        if (!prompt && !ShizukuManager.isReady) return null
        return ShizukuManager.access()
    }

    private val privilegedAvailable: Boolean get() = rootEnabled || (shizukuEnabled && ShizukuManager.isReady)

    private val renameInfo = AndroidRenameInfo(app)

    init {
        viewModelScope.launch {
            var previousRoot: Boolean? = null
            settingsRepo.settings.collect { s ->
                _state.update { it.copy(settings = s) }
                if (previousRoot != null && previousRoot != s.rootAccess) onRootModeChanged(s.rootAccess)
                previousRoot = s.rootAccess
            }
        }
        viewModelScope.launch { bookmarkRepo.bookmarks.collect { b -> _state.update { it.copy(bookmarks = b) } } }
        viewModelScope.launch {
            var previous = emptyMap<String, RemoteConnection>()
            remoteRepo.connections.collect { c ->
                _state.update { it.copy(storages = c) }
                val now = c.associateBy { it.id }
                previous.forEach { (id, old) ->
                    val new = now[id]
                    if (new == null || new.copy(hostKey = "") != old.copy(hostKey = "")) sessions.forget(id)
                }
                previous = now
            }
        }
        viewModelScope.launch { settingsRepo.renameHistory.collect { h -> _state.update { it.copy(renameHistory = h) } } }
        if (Vault.ready) {
            viewModelScope.launch {
                Vault.store.state.collect { s ->
                    if (s != VaultStore.State.UNLOCKED) leaveVault(s == VaultStore.State.LOCKED)
                }
            }
        }
        viewModelScope.launch {
            settingsRepo.settings.map { it.trashAutoCleanDays }.distinctUntilChanged().collect { days ->
                if (days > 0) {
                    val purged = withContext(Dispatchers.IO) { recycleBin.purgeOlderThan(days * 86_400_000L) }
                    if (purged > 0) postMessage(tr(R.string.explorer_trash_cleaned, purged))
                }
            }
        }
        viewModelScope.launch {
            val s = settingsRepo.settings.first()
            val last = settingsRepo.lastFolders.first()
            val (left, right) = StartFolders.resolve(s.behavior, last, rootPath)
            if (navJobs[Pane.LEFT] == null) navigate(Pane.LEFT, left, initial = true, history = HistoryOp.NONE, fallbacks = listOf(rootPath))
            if (navJobs[Pane.RIGHT] == null) navigate(Pane.RIGHT, right, initial = true, history = HistoryOp.NONE, fallbacks = listOf(rootPath))
            navJobs[Pane.LEFT]?.join()
            navJobs[Pane.RIGHT]?.join()
            _state.map { it.left.path to it.right.path }.distinctUntilChanged().debounce(800).collect { (l, r) ->
                if (!RemoteUri.isRemote(l) && !RemoteUri.isRemote(r)) settingsRepo.setLastFolders(l, r)
            }
        }
    }

    private fun conflictPolicy(): Conflict? = when (_state.value.settings.operations.conflict) {
        ConflictChoice.ASK -> null
        ConflictChoice.KEEP_BOTH -> Conflict.KEEP_BOTH
        ConflictChoice.OVERWRITE -> Conflict.OVERWRITE
        ConflictChoice.SKIP -> Conflict.SKIP
    }

    private val keepTime: Boolean get() = _state.value.settings.operations.keepModifiedTime

    private suspend fun conflictsIn(dst: PaneState, names: List<String>): List<String> = conflictsAt(dst.path, dst.viaRoot, names)

    private suspend fun conflictsAt(path: String, viaRoot: Boolean, names: List<String>): List<String> = withContext(Dispatchers.IO) {
        if (RemoteUri.isRemote(path)) {
            val taken = runCatching {
                sessions.use(RemoteUri.id(path)) { fs -> fs.list(RemoteUri.path(path)).mapTo(HashSet()) { it.name } }
            }.getOrDefault(emptySet())
            return@withContext names.filter { it in taken }
        }
        val to = runCatching { access(path, write = true, viaRoot = viaRoot) }.getOrNull() ?: return@withContext emptyList<String>()
        val destFile = to.file(path)
        names.filter { FsUtil.exists(to.child(destFile, it), to) }
    }

    fun setActive(pane: Pane) {
        if (_state.value.active != pane) _state.update { it.copy(active = pane) }
    }

    fun open(pane: Pane, item: FileItem) {
        if (item.isDirectory) navigate(pane, item.path)
    }

    fun openPath(pane: Pane, path: String) {
        val clean = path.trim().let { if (it.length > 1) it.trimEnd('/') else it }
        if (clean.isEmpty()) return
        if (RemoteUri.isRemote(clean)) return navigate(pane, clean)
        navigate(pane, if (clean.startsWith("/")) clean else File(_state.value.pane(pane).path, clean).path)
    }

    fun navigateUp(pane: Pane) {
        val current = _state.value.pane(pane).path
        if (RemoteUri.isRemote(current)) {
            RemoteUri.parent(current)?.let { return navigate(pane, it) }
            val places = _state.value.pane(pane).history.outsideStorages(rootPath)
            navigate(pane, places.first(), fallbacks = places.drop(1))
            return
        }
        val ancestors = generateSequence(File(_state.value.pane(pane).path).parentFile) { it.parentFile }.map { it.path }.toList()
        if (ancestors.isEmpty()) return
        navigate(pane, ancestors.first(), fallbacks = ancestors.drop(1))
    }

    fun goHome(pane: Pane) = navigate(pane, rootPath)

    fun goBack(pane: Pane) {
        val target = _state.value.pane(pane).history.back.lastOrNull() ?: return
        navigate(pane, target, history = HistoryOp.BACK)
    }

    fun goForward(pane: Pane) {
        val target = _state.value.pane(pane).history.forward.lastOrNull() ?: return
        navigate(pane, target, history = HistoryOp.FORWARD)
    }

    fun refresh(pane: Pane, reveal: Collection<String> = emptyList()) =
        navigate(pane, _state.value.pane(pane).path, refresh = true, history = HistoryOp.NONE, reveal = reveal)

    fun refreshAll() {
        refresh(Pane.LEFT)
        refresh(Pane.RIGHT)
    }

    private fun navigate(
        pane: Pane,
        path: String,
        refresh: Boolean = false,
        initial: Boolean = false,
        history: HistoryOp = HistoryOp.VISIT,
        reveal: Collection<String> = emptyList(),
        fallbacks: List<String> = emptyList(),
    ) {
        navJobs[pane]?.cancel()
        updatePane(pane) { it.copy(loading = true) }
        navJobs[pane] = viewModelScope.launch {
            var opened = path
            val listing = try {
                listFirst(listOf(path) + fallbacks) { opened = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                updatePane(pane) { it.copy(loading = false, error = if (initial) e.message else it.error) }
                if (!initial && !askAboutStorage(pane, path, e)) {
                    val shown = if (RemoteUri.isRemote(path)) _state.value.displayPath(path) else File(path).name.ifEmpty { path }
                    postMessage(tr(R.string.explorer_cannot_open, shown, e.message ?: tr(R.string.explorer_permission_denied)))
                }
                return@launch
            }
            val items = listing.items
            updatePane(pane) { p ->
                val samePath = p.path == opened
                val dir = directionOf(p.path, opened)
                p.copy(
                    path = opened,
                    items = items,
                    loading = false,
                    error = null,
                    viaRoot = listing.viaRoot,
                    selected = if (samePath) p.selected.filterTo(HashSet()) { s -> items.any { it.path == s } } else emptySet(),
                    direction = if (initial || refresh || samePath) NavDirection.NONE else dir,
                    cameFrom = if (!samePath && dir == NavDirection.BACK) childTowards(opened, p.path) else null,
                    history = if (samePath) p.history else when (history) {
                        HistoryOp.VISIT -> p.history.visit(p.path, opened)
                        HistoryOp.BACK -> p.history.afterBack(p.path)
                        HistoryOp.FORWARD -> p.history.afterForward(p.path)
                        HistoryOp.NONE -> p.history
                    },
                    reveal = reveal.toSet(),
                    revealSeq = if (reveal.isEmpty()) p.revealSeq else p.revealSeq + 1,
                )
            }
            refreshMountState(pane, opened, listing.viaRoot)
        }
    }

    private var mountsCache: Pair<Long, List<MountEntry>>? = null

    private suspend fun mounts(force: Boolean = false): List<MountEntry> {
        val now = android.os.SystemClock.elapsedRealtime()
        mountsCache?.let { (t, m) -> if (!force && now - t < 3_000) return m }
        val text = withContext(Dispatchers.IO) {
            (if (rootEnabled) runCatching { RootManager.exec("cat /proc/self/mounts").out.joinToString("\n") }.getOrNull() else null)
                ?: runCatching { File("/proc/self/mounts").readText() }.getOrDefault("")
        }
        return Mounts.parse(text).also { mountsCache = now to it }
    }

    private fun refreshMountState(pane: Pane, path: String, viaRoot: Boolean) {
        if (RemoteUri.isRemote(path)) {
            updatePane(pane) { if (it.path == path) it.copy(mount = null, readOnly = false) else it }
            return
        }
        viewModelScope.launch {
            val table = mounts()
            val (m, ro) = withContext(Dispatchers.IO) { Mounts.mountOf(File(path), table) to Mounts.readOnly(File(path), table, viaRoot) }
            updatePane(pane) { if (it.path == path) it.copy(mount = m, readOnly = ro) else it }
        }
    }

    fun remount(pane: Pane, writable: Boolean) = viewModelScope.launch {
        val m = _state.value.pane(pane).mount ?: return@launch
        if (writable && Mounts.isReadOnlyType(m)) {
            postMessage(tr(R.string.explorer_remount_ro_fs, m.point, m.type.uppercase()))
            return@launch
        }
        val r = runCatching { RootManager.exec(Mounts.remountCommand(m, writable) + " 2>&1") }.getOrNull()
        val detail = r?.out?.joinToString(" ")?.trim().orEmpty()
        postMessage(
            when {
                r == null -> tr(R.string.explorer_root_unavailable)
                r.isSuccess -> tr(if (writable) R.string.explorer_mounted_rw else R.string.explorer_mounted_ro, m.point)
                detail.isNotEmpty() -> tr(R.string.explorer_remount_failed_detail, m.point, detail)
                else -> tr(R.string.explorer_remount_failed, m.point)
            },
        )
        mounts(force = true)
        refresh(pane)
    }

    private suspend fun listFirst(paths: List<String>, opened: (String) -> Unit): Listing {
        var first: Exception? = null
        for (p in paths) {
            try {
                return lister.list(p, privilegedAvailable).also { opened(p) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (first == null) first = e
            }
        }
        throw first ?: IOException("nothing to open")
    }

    private suspend fun isMissing(path: String): Boolean = withContext(Dispatchers.IO) {
        val f = File(path)
        if (f.exists()) return@withContext false
        f.parentFile?.list()?.let { siblings -> return@withContext f.name !in siblings }
        val priv = privilegedAccess(prompt = false)?.file(path) ?: return@withContext false
        !priv.exists()
    }

    private fun onRootModeChanged(enabled: Boolean) {
        for (p in Pane.entries) {
            if (!enabled && _state.value.pane(p).viaRoot) navigate(p, rootPath) else refresh(p)
        }
    }

    fun toggleSelect(pane: Pane, item: FileItem, sound: Boolean = true) {
        if (sound && _state.value.pane(pane).selected.isEmpty()) UiSounds.play(UiSound.SELECT)
        updatePane(pane) {
            val next = it.selected.toMutableSet()
            if (!next.add(item.path)) next.remove(item.path)
            it.copy(selected = next)
        }
    }

    fun selectAll(pane: Pane, visiblePaths: List<String>) =
        updatePane(pane) { it.copy(selected = visiblePaths.toSet()) }

    fun invertSelection(pane: Pane, visiblePaths: List<String>) = updatePane(pane) {
        it.copy(selected = visiblePaths.filterNot { p -> p in it.selected }.toSet())
    }

    fun clearSelection(pane: Pane) = updatePane(pane) { it.copy(selected = emptySet()) }

    private fun selectedItems(pane: Pane): List<FileItem> {
        val ps = _state.value.pane(pane)
        return ps.items.filter { it.path in ps.selected }
    }

    fun selectionOf(pane: Pane): List<FileItem> = selectedItems(pane)

    fun setSort(order: SortOrder) = viewModelScope.launch { settingsRepo.setSortOrder(order) }

    fun setFolderSort(path: String, order: SortOrder?) = viewModelScope.launch { settingsRepo.setFolderSort(path, order) }
    fun setShowHidden(value: Boolean) = viewModelScope.launch { settingsRepo.setShowHidden(value) }

    fun setSearchActive(active: Boolean) = _state.update {
        it.copy(searchActive = active, query = if (active) it.query else "")
    }

    fun setQuery(q: String) = _state.update { it.copy(query = q) }

    fun newFolder(pane: Pane, name: String) {
        if (_state.value.isRemote(pane)) remoteCreate(pane, name, folder = true)
        else create(pane) { dir, access -> FileOperations.mkdir(dir, name, access) }
    }

    fun newFile(pane: Pane, name: String) {
        if (_state.value.isRemote(pane)) remoteCreate(pane, name, folder = false)
        else create(pane) { dir, access -> FileOperations.newFile(dir, name, access) }
    }

    private fun create(pane: Pane, op: suspend (String, FileAccess) -> Result<FileItem>) = viewModelScope.launch {
        val ps = _state.value.pane(pane)
        val result = try {
            op(ps.path, access(ps.path, write = true, viaRoot = ps.viaRoot))
        } catch (e: IOException) {
            Result.failure(e)
        }
        result
            .onSuccess { created -> refreshAllRevealing(ps.path, listOf(created.path)) }
            .onFailure { postMessage(it.message) }
    }

    fun rename(pane: Pane, item: FileItem, newName: String) = viewModelScope.launch {
        if (RemoteUri.isRemote(item.path)) return@launch remoteRename(item, newName)
        val ps = _state.value.pane(pane)
        val dir = item.parentPath ?: return@launch
        val result = try {
            FileOperations.rename(item, newName, access(dir, write = true, viaRoot = ps.viaRoot))
        } catch (e: IOException) {
            Result.failure(e)
        }
        result
            .onSuccess { renamed -> refreshAllRevealing(dir, listOf(renamed.path)) }
            .onFailure { postMessage(it.message) }
    }

    fun canRecycle(pane: Pane): Boolean {
        if (_state.value.isRemote(pane)) return false
        val ps = _state.value.pane(pane)
        return !ps.viaRoot && !RootPaths.needsRoot(File(ps.path), rootPath, write = true)
    }

    fun deleteStats(pane: Pane, items: List<FileItem>): kotlinx.coroutines.flow.Flow<dev.cluvex.strata.core.props.DirStats> {
        val ps = _state.value.pane(pane)
        if (RemoteUri.isRemote(ps.path)) {
            return kotlinx.coroutines.flow.flow {
                var bytes = 0L
                var files = 0
                var folders = 0
                var seen = 0
                for (item in items) {
                    val location = RemoteUri.parse(item.path)
                    if (location == null || !item.isDirectory) {
                        files++
                        bytes += item.size.coerceAtLeast(0)
                        continue
                    }
                    folders++
                    sessions.use(location.first) { fs ->
                        val stack = ArrayDeque<String>()
                        stack.addLast(location.second)
                        while (stack.isNotEmpty()) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            val dir = stack.removeLast()
                            val children = try { fs.list(dir) } catch (e: java.io.IOException) { emptyList() }
                            for (e in children) {
                                if (e.isDirectory && !e.isSymlink) {
                                    folders++
                                    stack.addLast(e.path)
                                } else {
                                    files++
                                    bytes += e.size.coerceAtLeast(0)
                                }
                                if (++seen % 64 == 0) emit(dev.cluvex.strata.core.props.DirStats(bytes, files, folders))
                            }
                        }
                    }
                }
                emit(dev.cluvex.strata.core.props.DirStats(bytes, files, folders))
            }.flowOn(kotlinx.coroutines.Dispatchers.IO)
        }
        val paths = items.map { it.path }
        return kotlinx.coroutines.flow.flow {
            val fa = access(ps.path, write = true, viaRoot = ps.viaRoot)
            emitAll(dev.cluvex.strata.core.props.DirectoryStats.stream(paths, fa, countRoots = true))
        }
    }

    fun deleteSelection(pane: Pane, toRecycleBin: Boolean) {
        val items = selectedItems(pane)
        if (items.isEmpty()) return
        val ps = _state.value.pane(pane)
        if (RemoteUri.isRemote(ps.path)) {
            runTask(tr(R.string.explorer_task_deleting), { RemoteTransfers.delete(items, sessions) }, sound = UiSound.TRASH) {
                clearSelection(pane)
                refreshAll()
            }
            return
        }
        val useBin = toRecycleBin && canRecycle(pane)
        runTask(tr(if (useBin) R.string.explorer_task_to_bin else R.string.explorer_task_deleting), {
            if (useBin) recycleBin.moveToTrash(items) else FileOperations.delete(items, access(ps.path, write = true, viaRoot = ps.viaRoot))
        }, sound = UiSound.TRASH) {
            clearSelection(pane)
            refreshAll()
        }
    }

    fun canRecycleItems(items: List<FileItem>): Boolean {
        if (items.isEmpty() || items.any { RemoteUri.isRemote(it.path) }) return false
        return items.all { !RootPaths.needsRoot(File(it.parentPath ?: "/"), rootPath, write = true) }
    }

    fun deleteStatsFor(items: List<FileItem>): kotlinx.coroutines.flow.Flow<dev.cluvex.strata.core.props.DirStats> =
        kotlinx.coroutines.flow.flow {
            val dir = items.firstOrNull()?.parentPath ?: rootPath
            val fa = access(dir, write = true, viaRoot = false)
            emitAll(dev.cluvex.strata.core.props.DirectoryStats.stream(items.map { it.path }, fa, countRoots = true))
        }

    fun deleteItems(items: List<FileItem>, toRecycleBin: Boolean, onDone: () -> Unit = {}) {
        if (items.isEmpty()) return
        val dir = items.first().parentPath ?: rootPath
        val useBin = toRecycleBin && canRecycleItems(items)
        runTask(tr(if (useBin) R.string.explorer_task_to_bin else R.string.explorer_task_deleting), {
            if (useBin) recycleBin.moveToTrash(items) else FileOperations.delete(items, access(dir, write = true, viaRoot = false))
        }, sound = UiSound.TRASH) {
            refreshAll()
            onDone()
        }
    }

    fun copyItemsToClipboard(items: List<FileItem>, move: Boolean) {
        if (items.isEmpty()) return
        val dir = items.first().parentPath ?: rootPath
        _state.update { it.copy(clipboard = ClipboardState(items, move, sourceDir = dir, viaRoot = false)) }
        UiSounds.play(UiSound.POP)
        postMessage(trPlural(if (move) R.plurals.explorer_clip_cut else R.plurals.explorer_clip_copied, items.size, items.size))
    }

    fun renameItem(item: FileItem, newName: String, onDone: (FileItem) -> Unit) = viewModelScope.launch {
        val dir = item.parentPath ?: return@launch
        val result = try {
            FileOperations.rename(item, newName, access(dir, write = true, viaRoot = false))
        } catch (e: IOException) {
            Result.failure(e)
        }
        result
            .onSuccess { renamed ->
                refreshAllRevealing(dir, listOf(renamed.path))
                onDone(renamed)
            }
            .onFailure { postMessage(it.message) }
    }

    fun copySelectionToClipboard(pane: Pane, move: Boolean) {
        val items = selectedItems(pane)
        if (items.isEmpty()) return
        val ps = _state.value.pane(pane)
        _state.update { it.copy(clipboard = ClipboardState(items, move, sourceDir = ps.path, viaRoot = ps.viaRoot)) }
        UiSounds.play(UiSound.POP)
        clearSelection(pane)
        postMessage(trPlural(if (move) R.plurals.explorer_clip_cut else R.plurals.explorer_clip_copied, items.size, items.size))
    }

    fun clearClipboard() = _state.update { it.copy(clipboard = null) }

    fun paste(pane: Pane) {
        val clip = _state.value.clipboard ?: return
        val dst = _state.value.pane(pane)
        val policy = conflictPolicy()
        if (policy != null || dst.path == clip.sourceDir) return pasteWith(pane, policy ?: Conflict.KEEP_BOTH)
        viewModelScope.launch {
            val conflicts = conflictsIn(dst, clip.items.map { it.name })
            if (conflicts.isEmpty()) {
                pasteWith(pane, Conflict.KEEP_BOTH)
            } else {
                UiSounds.play(UiSound.ATTENTION)
                _state.update { it.copy(transferPrompt = TransferPrompt(clip.move, clip.items.size, dst.path, conflicts, pastePane = pane)) }
            }
        }
    }

    private fun pasteWith(pane: Pane, conflict: Conflict) {
        val clip = _state.value.clipboard ?: return
        val dst = _state.value.pane(pane)
        val title = tr(if (clip.move) R.string.explorer_task_moving else R.string.explorer_task_copying)
        if (RemoteUri.isRemote(clip.sourceDir) || RemoteUri.isRemote(dst.path)) {
            runTask(title, {
                RemoteTransfers.transfer(clip.items, Place.of(dst.path), clip.move, conflict, sessions, transferTemp())
            }, sound = UiSound.LAND) { done ->
                _state.update { it.copy(clipboard = null) }
                refreshAllRevealing(dst.path, done.created)
            }
            return
        }
        runTask(title, {
            val from = access(clip.sourceDir, write = clip.move, viaRoot = clip.viaRoot)
            val to = access(dst.path, write = true, viaRoot = dst.viaRoot)
            if (clip.move) {
                FileOperations.move(clip.items, dst.path, conflict, from = from, to = to)
            } else {
                FileOperations.copy(clip.items, dst.path, conflict, from = from, to = to, keepTime = keepTime)
            }
        }, sound = UiSound.LAND) { done ->
            _state.update { it.copy(clipboard = null) }
            refreshAllRevealing(dst.path, done.created)
        }
    }

    fun beginTransfer(move: Boolean) {
        val s = _state.value
        val items = selectedItems(s.active)
        if (items.isEmpty()) return
        val dst = s.pane(s.active.other)
        conflictPolicy()?.let { return transferToOther(move, it) }
        viewModelScope.launch {
            val conflicts = conflictsIn(dst, items.map { it.name })
            if (conflicts.isEmpty()) {
                transferToOther(move, Conflict.KEEP_BOTH)
            } else {
                UiSounds.play(UiSound.ATTENTION)
                _state.update { it.copy(transferPrompt = TransferPrompt(move, items.size, dst.path, conflicts)) }
            }
        }
    }

    fun cancelTransfer() = _state.update { it.copy(transferPrompt = null) }

    fun confirmTransfer(conflict: Conflict) {
        val prompt = _state.value.transferPrompt ?: return
        _state.update { it.copy(transferPrompt = null) }
        prompt.drop?.let { return moveDropped(it.pane, it.items, it.destination, conflict) }
        val pane = prompt.pastePane
        if (pane != null) pasteWith(pane, conflict) else transferToOther(prompt.move, conflict)
    }

    fun dropInto(fromPane: Pane, items: List<FileItem>, destination: String) {
        if (items.isEmpty()) return
        if (DropRules.verdict(items.map { it.path }, destination) != DropVerdict.MOVE) return
        conflictPolicy()?.let { return moveDropped(fromPane, items, destination, it) }
        viewModelScope.launch {
            val viaRoot = viaRootFor(destination) || _state.value.pane(fromPane).viaRoot
            val conflicts = conflictsAt(destination, viaRoot, items.map { it.name })
            if (conflicts.isEmpty()) {
                moveDropped(fromPane, items, destination, Conflict.KEEP_BOTH)
            } else {
                UiSounds.play(UiSound.ATTENTION)
                _state.update {
                    it.copy(transferPrompt = TransferPrompt(true, items.size, destination, conflicts, drop = DropRequest(fromPane, items, destination)))
                }
            }
        }
    }

    private fun moveDropped(fromPane: Pane, items: List<FileItem>, destination: String, conflict: Conflict) {
        val src = _state.value.pane(fromPane)
        val sourceDir = items.first().parentPath ?: src.path
        if (RemoteUri.isRemote(sourceDir) || RemoteUri.isRemote(destination)) {
            runTask(tr(R.string.explorer_task_moving), {
                RemoteTransfers.transfer(items, Place.of(destination), true, conflict, sessions, transferTemp())
            }, sound = UiSound.LAND) { done ->
                clearSelection(fromPane)
                refreshAllRevealing(destination, done.created)
            }
            return
        }
        runTask(tr(R.string.explorer_task_moving), {
            val from = access(sourceDir, write = true, viaRoot = src.viaRoot)
            val to = access(destination, write = true, viaRoot = viaRootFor(destination) || src.viaRoot)
            FileOperations.move(items, destination, conflict, from, to)
        }, sound = UiSound.LAND) { done ->
            clearSelection(fromPane)
            refreshAllRevealing(destination, done.created)
            if (done.created.isNotEmpty()) {
                val name = File(destination).name.ifEmpty { destination }
                val count = done.created.size
                val message = if (count == 1) {
                    tr(R.string.explorer_moved_one, File(done.created.first()).name, name)
                } else {
                    trPlural(R.plurals.explorer_moved_many, count, count, name)
                }
                _state.update { it.copy(undo = MoveUndo(message, done.created, sourceDir, (it.undo?.seq ?: 0) + 1)) }
            }
        }
    }

    fun undoMove(undo: MoveUndo) {
        _state.update { it.copy(undo = null) }
        runTask(tr(R.string.explorer_task_moving), {
            val viaRoot = viaRootFor(undo.backTo)
            val firstDir = File(undo.moved.first()).parent ?: undo.backTo
            val from = access(firstDir, write = true, viaRoot = viaRoot)
            val to = access(undo.backTo, write = true, viaRoot = viaRoot)
            val items = undo.moved.map { FileOperations.itemOf(from.file(it), from) }
            FileOperations.move(items, undo.backTo, Conflict.KEEP_BOTH, from, to)
        }, sound = UiSound.LAND) { done -> refreshAllRevealing(undo.backTo, done.created) }
    }

    fun consumeUndo(seq: Int) = _state.update { if (it.undo?.seq == seq) it.copy(undo = null) else it }

    fun transferToOther(move: Boolean, conflict: Conflict = Conflict.KEEP_BOTH) {
        val s = _state.value
        val fromPane = s.active
        val items = selectedItems(fromPane)
        if (items.isEmpty()) return
        val src = s.pane(fromPane)
        val dst = s.pane(fromPane.other)
        val title = tr(if (move) R.string.explorer_task_moving else R.string.explorer_task_copying)
        if (RemoteUri.isRemote(src.path) || RemoteUri.isRemote(dst.path)) {
            runTask(title, {
                RemoteTransfers.transfer(items, Place.of(dst.path), move, conflict, sessions, transferTemp())
            }, sound = UiSound.LAND) { done ->
                clearSelection(fromPane)
                refreshAllRevealing(dst.path, done.created)
            }
            return
        }
        runTask(title, {
            val from = access(src.path, write = move, viaRoot = src.viaRoot)
            val to = access(dst.path, write = true, viaRoot = dst.viaRoot)
            if (move) {
                FileOperations.move(items, dst.path, conflict, from, to)
            } else {
                FileOperations.copy(items, dst.path, conflict, from, to, keepTime)
            }
        }, sound = UiSound.LAND) { done ->
            clearSelection(fromPane)
            refreshAllRevealing(dst.path, done.created)
        }
    }

    fun extractArchive(pane: Pane, item: FileItem, password: CharArray? = null) {
        if (_state.value.isRemote(pane)) return notOnStorage()
        val ps = _state.value.pane(pane)
        val destParent = ps.path
        runTask(tr(R.string.explorer_task_extracting), {
            val from = access(item.parentPath ?: destParent, write = false, viaRoot = ps.viaRoot)
            val to = access(destParent, write = true, viaRoot = ps.viaRoot)
            val folder = to.child(to.file(destParent), ArchiveManager.extractionFolderName(item.name))
            val dest = FsUtil.uniqueSibling(folder, isDirectory = true, access = to)
            ArchiveManager.extract(item.path, dest.absolutePath, from, to, password)
        }, onError = { e ->
            when (e) {
                is PasswordRequiredException -> true.also { askPassword(pane, item, PasswordPurpose.EXTRACT, wrong = false) }
                is WrongPasswordException -> true.also { askPassword(pane, item, PasswordPurpose.EXTRACT, wrong = true) }
                else -> false
            }
        }, onFinally = { password?.fill('\u0000') }, sound = UiSound.LAND) { done -> refreshAllRevealing(destParent, done.created) }
    }

    fun createArchive(pane: Pane, items: List<FileItem>, name: String, options: ArchiveOptions, independent: Boolean) {
        if (_state.value.isRemote(pane)) return notOnStorage()
        if (items.isEmpty()) return
        val ps = _state.value.pane(pane)
        val fileName = options.format.fileName(name)
        if (!independent) {
            FileOperations.validateName(fileName)?.let {
                postMessage(it)
                return
            }
        }
        runTask(tr(R.string.explorer_task_compressing), {
            val from = access(ps.path, write = false, viaRoot = ps.viaRoot)
            val to = access(ps.path, write = true, viaRoot = ps.viaRoot)
            if (independent) {
                ArchiveCreator.createEach(items, ps.path, options, from, to)
            } else {
                val dest = FsUtil.uniqueSibling(to.child(to.file(ps.path), fileName), isDirectory = false, access = to)
                ArchiveCreator.create(items, dest.absolutePath, options, from, to)
            }
        }, onFinally = { options.password?.fill('\u0000') }, sound = UiSound.SUCCESS) { done ->
            clearSelection(pane)
            refreshAllRevealing(ps.path, done.created)
        }
    }

    fun signApk(item: FileItem, request: dev.cluvex.strata.feature.apk.SignRequest) {
        if (RemoteUri.isRemote(item.path)) return notOnStorage()
        val dir = item.parentPath ?: return
        val keyStep = tr(
            when (request) {
                is dev.cluvex.strata.feature.apk.SignRequest.Quick -> R.string.explorer_step_default_key
                is dev.cluvex.strata.feature.apk.SignRequest.SavedKey -> R.string.explorer_step_saved_key
                is dev.cluvex.strata.feature.apk.SignRequest.NewKey -> R.string.explorer_step_new_key
                is dev.cluvex.strata.feature.apk.SignRequest.ExistingKeystore -> R.string.explorer_step_keystore
            },
        )
        var keptMessage: String? = null
        if (_state.value.build?.finished == false) return
        viewModelScope.launch {
            try {
                val result = dev.cluvex.strata.feature.build.runBuild(
                    tr(R.string.explorer_signing_title),
                    item.name,
                    listOf(keyStep, ApkSigning.STEP_SIGN, ApkSigning.STEP_VERIFY),
                    publish = { run -> _state.update { it.copy(build = run) } },
                    summary = { out: File -> tr(R.string.explorer_signed_as, out.name) },
                ) { log ->
                    log.step(keyStep)
                    val key: SigningKey = when (request) {
                        is dev.cluvex.strata.feature.apk.SignRequest.Quick -> defaultSigningKey()
                        is dev.cluvex.strata.feature.apk.SignRequest.SavedKey ->
                            SavedKeys(File(getApplication<Application>().filesDir, "keystores")).load(request.id)
                        is dev.cluvex.strata.feature.apk.SignRequest.NewKey -> {
                            val key = KeyStoreFactory.generateKey(request.identity)
                            SavedKeys(File(getApplication<Application>().filesDir, "keystores")).save(request.alias, key)
                            log.info(tr(R.string.explorer_kept_as, request.alias))
                            keptMessage = tr(R.string.explorer_signed_kept)
                            if (request.jksCopy) {
                                val jks = FsUtil.uniqueSibling(File(dir, "${request.alias}.jks"), isDirectory = false)
                                jks.writeBytes(KeystoreFile.export(KeystoreFormat.JKS, listOf(request.alias to key), request.storePassword, request.keyPassword))
                                log.info(tr(R.string.explorer_saved_file, jks.name))
                                keptMessage = tr(R.string.explorer_signed_kept_jks, jks.name)
                            }
                            key
                        }
                        is dev.cluvex.strata.feature.apk.SignRequest.ExistingKeystore -> KeyStoreFactory.load(request.spec)
                    }
                    key.certificates.firstOrNull()?.let { log.info(it.subjectX500Principal.name) }
                    val out = FsUtil.uniqueSibling(File(dir, signedApkName(item.name)), isDirectory = false)
                    ApkSigning.sign(File(item.path), out, key, request.options, log = log)
                    out
                }
                result.onSuccess { out ->
                    keptMessage?.let { postMessage(it) }
                    refreshAllRevealing(dir, listOf(out.absolutePath))
                }
            } finally {
                request.wipe()
            }
        }
    }

    suspend fun openInput(item: FileItem): InputStream {
        val dir = item.parentPath ?: throw IOException(tr(R.string.explorer_no_folder))
        val access = access(dir, write = false, viaRoot = viaRootFor(dir))
        return withContext(Dispatchers.IO) { access.openInput(access.file(item.path)) }
    }

    fun savePluginResult(item: FileItem, result: File, name: String?, replace: Boolean, onFinally: () -> Unit) {
        val dir = item.parentPath ?: return onFinally()
        runTask(tr(R.string.explorer_task_saving), {
            flow {
                emit(OpProgress.Running(item.name, 0, 1, 0, 1))
                val access = access(dir, write = true, viaRoot = viaRootFor(dir))
                val out = PluginFiles.save(result, access.file(item.path), name, replace, access)
                emit(OpProgress.Finished(1, emptyList(), created = listOf(out.absolutePath)))
            }
        }, onFinally = onFinally, sound = UiSound.SUCCESS) { done ->
            postMessage(if (replace) tr(R.string.explorer_replaced, item.name) else tr(R.string.explorer_saved_as, File(done.created.first()).name))
            refreshAllRevealing(dir, done.created)
        }
    }

    private fun signedApkName(name: String): String =
        if (name.endsWith(".apk", ignoreCase = true)) name.dropLast(4) + "-signed.apk" else "$name-signed.apk"

    private fun defaultSigningKey(): SigningKey = SigningKeys.default(getApplication<Application>().filesDir)

    fun dismissPasswordPrompt() = _state.update { it.copy(passwordPrompt = null) }

    fun submitPassword(password: CharArray) {
        val prompt = _state.value.passwordPrompt ?: return
        dismissPasswordPrompt()
        when (prompt.purpose) {
            PasswordPurpose.EXTRACT -> extractArchive(prompt.pane, prompt.item, password)
            PasswordPurpose.DECRYPT -> decryptFile(prompt.pane, prompt.item, password)
        }
    }

    private fun askPassword(pane: Pane, item: FileItem, purpose: PasswordPurpose, wrong: Boolean) =
        _state.update { it.copy(passwordPrompt = PasswordPrompt(pane, item, purpose, wrong)) }

    fun encryptFile(pane: Pane, item: FileItem, password: CharArray) {
        if (_state.value.isRemote(pane)) return notOnStorage()
        val ps = _state.value.pane(pane)
        val dir = item.parentPath ?: return
        runTask(tr(R.string.explorer_task_encrypting), {
            val from = access(dir, write = false, viaRoot = ps.viaRoot)
            val to = access(dir, write = true, viaRoot = ps.viaRoot)
            val out = FsUtil.uniqueSibling(to.child(to.file(dir), FileCipher.encryptedName(item.name)), isDirectory = false, access = to)
            FileCipher.encrypt(item.path, out.absolutePath, password, from, to)
        }, onFinally = { password.fill('\u0000') }, sound = UiSound.LOCK) { done -> refreshAllRevealing(dir, done.created) }
    }

    fun decryptFile(pane: Pane, item: FileItem, password: CharArray) {
        if (_state.value.isRemote(pane)) return notOnStorage()
        val ps = _state.value.pane(pane)
        val dir = item.parentPath ?: return
        runTask(tr(R.string.explorer_task_decrypting), {
            val from = access(dir, write = false, viaRoot = ps.viaRoot)
            val to = access(dir, write = true, viaRoot = ps.viaRoot)
            val out = FsUtil.uniqueSibling(to.child(to.file(dir), FileCipher.decryptedName(item.name)), isDirectory = false, access = to)
            FileCipher.decrypt(item.path, out.absolutePath, password, from, to)
        }, onError = { e ->
            (e is IncorrectPasswordException).also { wrong -> if (wrong) askPassword(pane, item, PasswordPurpose.DECRYPT, wrong = true) }
        }, onFinally = { password.fill('\u0000') }, sound = UiSound.UNLOCK) { done -> refreshAllRevealing(dir, done.created) }
    }

    fun splitFile(pane: Pane, item: FileItem, method: SplitMethod, pattern: String) {
        if (_state.value.isRemote(pane)) return notOnStorage()
        val ps = _state.value.pane(pane)
        val dir = item.parentPath ?: return
        runTask(tr(R.string.explorer_task_splitting), {
            val from = access(dir, write = false, viaRoot = ps.viaRoot)
            val to = access(dir, write = true, viaRoot = ps.viaRoot)
            FileSplitter.split(item.path, dir, method, pattern, from, to)
        }, sound = UiSound.SUCCESS) { done -> refreshAllRevealing(dir, done.created) }
    }

    fun mergeFiles(pane: Pane, parts: List<FileItem>, outputName: String, pdf: Boolean = false) {
        if (_state.value.isRemote(pane)) return notOnStorage()
        FileOperations.validateName(outputName)?.let {
            postMessage(it)
            return
        }
        val ps = _state.value.pane(pane)
        runTask(tr(R.string.explorer_task_merging), {
            val from = access(ps.path, write = false, viaRoot = ps.viaRoot)
            val to = access(ps.path, write = true, viaRoot = ps.viaRoot)
            val output = to.child(to.file(ps.path), outputName).absolutePath
            if (pdf) PdfMerger.merge(parts.map { it.path }, output, from, to) else FileSplitter.merge(parts.map { it.path }, output, from, to)
        }, sound = UiSound.SUCCESS) { done ->
            clearSelection(pane)
            refreshAllRevealing(ps.path, done.created)
        }
    }

    suspend fun hasSmali(pane: Pane, folder: FileItem): Boolean {
        if (!folder.isDirectory || _state.value.isRemote(pane)) return false
        val ps = _state.value.pane(pane)
        return withContext(Dispatchers.IO) {
            runCatching {
                val from = access(ps.path, write = false, viaRoot = ps.viaRoot)
                DexConvert.containsSmali(from.file(folder.path), from)
            }.getOrDefault(false)
        }
    }

    fun smaliToDex(pane: Pane, folder: FileItem) {
        if (_state.value.isRemote(pane)) return notOnStorage()
        val ps = _state.value.pane(pane)
        build("Smali → Dex", folder.name, listOf(DexConvert.STEP_COLLECT), pane, ps.path) { log ->
            val from = access(ps.path, write = false, viaRoot = ps.viaRoot)
            val to = access(ps.path, write = true, viaRoot = ps.viaRoot)
            DexConvert.smaliToDex(folder.path, from, to, log)
        }
    }

    fun jarToDex(pane: Pane, jar: FileItem) {
        if (_state.value.isRemote(pane)) return notOnStorage()
        val ps = _state.value.pane(pane)
        build("Jar → Dex", jar.name, listOf(DexConvert.STEP_READ_JAR, DexConvert.STEP_D8), pane, ps.path) { log ->
            val from = access(ps.path, write = false, viaRoot = ps.viaRoot)
            val to = access(ps.path, write = true, viaRoot = ps.viaRoot)
            DexConvert.jarToDex(jar.path, from, to, log = log)
        }
    }

    private fun build(title: String, subject: String, plan: List<String>, pane: Pane?, dir: String, onDone: (File) -> Unit = {}, work: suspend (dev.cluvex.strata.core.build.BuildListener) -> File) {
        if (_state.value.build?.finished == false) return
        viewModelScope.launch {
            val result = dev.cluvex.strata.feature.build.runBuild(
                title,
                subject,
                plan,
                publish = { run -> _state.update { it.copy(build = run) } },
                summary = { out: File -> tr(R.string.explorer_wrote, out.name) },
                work = work,
            )
            result.onSuccess { out ->
                pane?.let { clearSelection(it) }
                refreshAllRevealing(dir, listOf(out.absolutePath))
                onDone(out)
            }.onFailure { refreshAll() }
        }
    }

    fun closeBuild() = _state.update { it.copy(build = null) }

    fun createLinks(pane: Pane, destDir: String, requests: List<LinkRequest>) {
        if (_state.value.isRemote(pane)) return notOnStorage()
        if (requests.isEmpty()) return
        val dest = destDir.trim().let { if (it.length > 1) it.trimEnd('/') else it }
        runTask(tr(R.string.explorer_task_linking), {
            FileOperations.createLinks(dest, requests, access(dest, write = true, viaRoot = viaRootFor(dest)), keepBoth = true)
        }, sound = UiSound.SUCCESS) { done ->
            clearSelection(pane)
            refreshAllRevealing(dest, done.created)
        }
    }

    suspend fun previewRename(pane: Pane, items: List<FileItem>, rule: RenameRule, order: RenameOrder, descending: Boolean): RenamePlan {
        val existing = _state.value.pane(pane).items.map { it.name }
        return withContext(Dispatchers.IO) {
            BatchRename.plan(BatchRename.ordered(items, order, descending), rule, existing, renameInfo)
        }
    }

    fun applyRename(pane: Pane, plan: RenamePlan, rule: RenameRule) {
        if (_state.value.isRemote(pane)) return notOnStorage()
        if (!plan.canApply) return
        val ps = _state.value.pane(pane)
        viewModelScope.launch { settingsRepo.pushRenameHistory(rule) }
        runTask(tr(R.string.explorer_task_renaming), { BatchRename.apply(plan, access(ps.path, write = true, viaRoot = ps.viaRoot)) }, sound = UiSound.SUCCESS) { done ->
            clearSelection(pane)
            refreshAllRevealing(ps.path, done.created)
        }
    }

    // Internal storage is called "Sdcard" and a storage's own root takes the storage's name, instead of the
    // last path segment ("0" for /storage/emulated/0, the connection id for a storage root).
    private fun bookmarkName(path: String): String {
        val p = Bookmarks.normalize(path)
        if (RemoteUri.isRemote(p)) {
            val (id, inner) = RemoteUri.parse(p) ?: return Bookmarks.defaultName(p)
            if (inner == "/") {
                _state.value.storages.firstOrNull { it.id == id }?.let { s -> return s.name.ifBlank { s.host }.ifBlank { Bookmarks.defaultName(p) } }
            } else {
                return RemoteUri.name(p).ifEmpty { Bookmarks.defaultName(p) }
            }
        }
        if (p == rootPath.trimEnd('/') || p == "/sdcard") return "Sdcard"
        return Bookmarks.defaultName(p)
    }

    fun addBookmarks(paths: List<String>) = viewModelScope.launch {
        var added = 0
        bookmarkRepo.edit { list -> Bookmarks.add(list, paths, nameOf = ::bookmarkName).also { added = it.second }.first }
        postMessage(
            when (added) {
                0 -> tr(R.string.explorer_bookmark_exists)
                1 -> tr(R.string.explorer_bookmark_added)
                else -> trPlural(R.plurals.explorer_bookmarks_added, added, added)
            },
        )
    }

    fun addSelectionToBookmarks(pane: Pane) {
        val paths = selectedItems(pane).map { it.path }
        if (paths.isEmpty()) return
        addBookmarks(paths)
        clearSelection(pane)
    }

    fun updateBookmark(path: String, edited: Bookmark) = viewModelScope.launch {
        var error: String? = null
        bookmarkRepo.edit { list -> Bookmarks.update(list, path, edited).getOrElse { e -> error = e.message; list } }
        postMessage(error)
    }

    fun removeBookmark(path: String) = viewModelScope.launch { bookmarkRepo.edit { Bookmarks.remove(it, listOf(path)) } }

    fun moveBookmark(path: String, offset: Int) = viewModelScope.launch { bookmarkRepo.edit { Bookmarks.move(it, path, offset) } }

    fun deleteBookmarkGroup(group: String) = viewModelScope.launch { bookmarkRepo.edit { Bookmarks.deleteGroup(it, group) } }

    fun renameBookmarkGroup(from: String, to: String) = viewModelScope.launch { bookmarkRepo.edit { Bookmarks.renameGroup(it, from, to) } }

    fun openBookmark(pane: Pane, bookmark: Bookmark) = viewModelScope.launch {
        setActive(pane)
        val path = bookmark.path
        when (kindOf(path)) {
            PathKind.FOLDER -> navigate(pane, path)
            PathKind.FILE -> navigate(pane, File(path).parent ?: "/", reveal = listOf(path))
            PathKind.MISSING -> postMessage(tr(R.string.explorer_bookmark_missing, bookmark.name, bookmark.path))
        }
    }

    fun showInFolder(pane: Pane, folder: String, reveal: String? = null) {
        setActive(pane)
        navigate(pane, folder, reveal = listOfNotNull(reveal))
    }

    fun locateIncoming(uri: Uri) = viewModelScope.launch {
        val app = getApplication<Application>()
        val pane = _state.value.active
        val path = withContext(Dispatchers.IO) { IncomingFiles.locate(app, uri) }
        val kind = if (path == null) PathKind.MISSING else kindOf(path)
        when {
            path != null && kind == PathKind.FILE -> showInFolder(pane, File(path).parent ?: "/", path)
            path != null && kind == PathKind.FOLDER -> showInFolder(pane, path)
            else -> {
                val name = withContext(Dispatchers.IO) { IncomingPaths.safeName(IncomingFiles.displayName(app, uri)) }
                _state.update { it.copy(incoming = IncomingCopy(uri.toString(), name, (it.incoming?.seq ?: 0) + 1)) }
            }
        }
    }

    fun consumeIncoming(seq: Int) = _state.update { if (it.incoming?.seq == seq) it.copy(incoming = null) else it }

    fun saveIncomingCopy(copy: IncomingCopy) {
        consumeIncoming(copy.seq)
        val app = getApplication<Application>()
        val uri = Uri.parse(copy.uri)
        val dir = File(rootPath, Environment.DIRECTORY_DOWNLOADS).path
        runTask(tr(R.string.explorer_task_saving), work = {
            flow {
                val to = access(dir, write = true, viaRoot = false)
                val target = FsUtil.uniqueSibling(to.child(to.file(dir), copy.name), false, to)
                val total = IncomingFiles.size(app, uri)
                val input = app.contentResolver.openInputStream(uri) ?: throw IOException(tr(R.string.explorer_incoming_unreadable))
                try {
                    input.use { i ->
                        to.openOutput(target).use { o ->
                            val buffer = ByteArray(256 * 1024)
                            var done = 0L
                            var shown = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val n = i.read(buffer)
                                if (n < 0) break
                                o.write(buffer, 0, n)
                                done += n
                                val now = System.nanoTime()
                                if (now - shown > 100_000_000L) {
                                    shown = now
                                    emit(OpProgress.Running(copy.name, done, total, 0, if (total > 0) 1 else 0))
                                }
                            }
                        }
                    }
                } catch (e: Throwable) {
                    runCatching { target.delete() }
                    throw e
                }
                emit(OpProgress.Finished(okCount = 1, failed = emptyList(), created = listOf(target.path)))
            }.flowOn(Dispatchers.IO)
        }, sound = UiSound.LAND) { done ->
            done.created.firstOrNull()?.let { showInFolder(_state.value.active, dir, it) }
        }
    }

    private enum class PathKind { FOLDER, FILE, MISSING }

    private suspend fun kindOf(path: String): PathKind = withContext(Dispatchers.IO) {
        val f = File(path)
        when {
            f.isDirectory -> PathKind.FOLDER
            f.exists() -> PathKind.FILE
            else -> {
                val root = privilegedAccess(prompt = false)?.file(path)
                when {
                    root?.isDirectory == true -> PathKind.FOLDER
                    root?.exists() == true -> PathKind.FILE
                    runCatching { lister.list(path, privilegedAvailable) }.isSuccess -> PathKind.FOLDER
                    else -> PathKind.MISSING
                }
            }
        }
    }

    fun swapPanes() = _state.update {
        it.copy(left = it.right.copy(direction = NavDirection.NONE), right = it.left.copy(direction = NavDirection.NONE), active = it.inactive)
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private suspend fun access(dir: String, write: Boolean, viaRoot: Boolean): FileAccess {
        val needsPrivileged = viaRoot || withContext(Dispatchers.IO) { RootPaths.needsRoot(File(dir), rootPath, write) }
        if (!needsPrivileged) return LocalFileAccess
        return privilegedAccess() ?: LocalFileAccess
    }

    private fun viaRootFor(dir: String): Boolean =
        Pane.entries.map { _state.value.pane(it) }.firstOrNull { it.path.trimEnd('/') == dir.trimEnd('/') }?.viaRoot ?: false

    fun revealCreated(dir: String, path: String) = refreshAllRevealing(dir, listOf(path))

    private fun refreshAllRevealing(dir: String, paths: Collection<String>) {
        val target = dir.trimEnd('/')
        for (p in Pane.entries) {
            val showing = _state.value.pane(p).path.trimEnd('/') == target
            refresh(p, reveal = if (showing) paths else emptyList())
        }
    }

    private fun runTask(
        title: String,
        work: suspend () -> Flow<OpProgress>,
        onError: (Exception) -> Boolean = { false },
        onFinally: () -> Unit = {},
        sound: UiSound? = null,
        onDone: (OpProgress.Finished) -> Unit,
    ) {
        viewModelScope.launch {
            _state.update { it.copy(task = TaskState(title = title, indeterminate = true)) }
            try {
                withContext(Dispatchers.IO) { work() }.collect { p ->
                    when (p) {
                        is OpProgress.Running -> _state.update { it.copy(task = taskState(title, p)) }
                        is OpProgress.Finished -> {
                            _state.update { it.copy(task = null) }
                            if (sound == null) {
                                UiSounds.forTask(title, failed = p.failed.isNotEmpty(), okCount = p.okCount)
                            } else if (p.failed.isNotEmpty()) {
                                UiSounds.play(UiSound.ERROR)
                            } else if (p.okCount > 0) {
                                UiSounds.play(sound)
                            }
                            val parts = buildList {
                                if (p.failed.isNotEmpty()) add(tr(R.string.explorer_task_failed, p.failed.size, p.failed.first()))
                                if (p.skipped.isNotEmpty()) add(tr(R.string.explorer_task_skipped, p.skipped.size))
                            }
                            if (parts.isNotEmpty()) postMessage(parts.joinToString(separator()))
                            onDone(p)
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!onError(e)) {
                    UiSounds.play(UiSound.ERROR)
                    postMessage(tr(R.string.explorer_task_error, title, e.message ?: e.javaClass.simpleName))
                }
                refreshAll()
            } finally {
                _state.update { it.copy(task = null) }
                onFinally()
            }
        }
    }

    private suspend fun listRemote(uri: String): List<FileItem> = withContext(Dispatchers.IO) {
        val (id, path) = RemoteUri.parse(uri) ?: throw IOException(tr(R.string.explorer_not_network))
        sessions.use(id) { fs ->
            fs.list(path).map { e ->
                FileItem(e.name, RemoteUri.of(id, e.path), e.isDirectory, e.size, e.modified, e.name.startsWith("."), canRead = true, canWrite = true, isSymlink = e.isSymlink)
            }
        }
    }

    private fun remoteCreate(pane: Pane, name: String, folder: Boolean) = viewModelScope.launch {
        val dir = _state.value.pane(pane).path
        val clean = name.trim()
        if (clean.isEmpty() || '/' in clean) return@launch postMessage(tr(R.string.common_invalid_name))
        val target = RemoteUri.child(dir, clean)
        runCatching {
            withContext(Dispatchers.IO) {
                sessions.use(RemoteUri.id(dir)) { fs ->
                    val path = RemoteUri.path(target)
                    if (fs.stat(path) != null) throw IOException(tr(R.string.common_already_exists, clean))
                    if (folder) fs.mkdir(path) else fs.upload(ByteArray(0).inputStream(), path)
                }
            }
        }.onSuccess { refreshAllRevealing(dir, listOf(target)) }.onFailure { postMessage(it.message ?: tr(R.string.explorer_couldnt_create, clean)) }
    }

    private suspend fun remoteRename(item: FileItem, newName: String) {
        val dir = RemoteUri.parent(item.path) ?: return
        val clean = newName.trim()
        if (clean.isEmpty() || '/' in clean) return postMessage(tr(R.string.common_invalid_name))
        val target = RemoteUri.child(dir, clean)
        runCatching {
            withContext(Dispatchers.IO) {
                sessions.use(RemoteUri.id(dir)) { fs -> fs.rename(RemoteUri.path(item.path), RemoteUri.path(target)) }
            }
        }.onSuccess { refreshAllRevealing(dir, listOf(target)) }.onFailure { postMessage(it.message ?: tr(R.string.files_rename_failed)) }
    }

    fun openRemote(item: FileItem, onReady: (FileItem) -> Unit) {
        val (id, path) = RemoteUri.parse(item.path) ?: return
        viewModelScope.launch {
            _state.update { it.copy(task = TaskState(tr(R.string.explorer_opening, item.name), indeterminate = true)) }
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val local = File(File(getApplication<Application>().cacheDir, "remote-panes/$id"), path.removePrefix("/"))
                    local.parentFile?.mkdirs()
                    sessions.use(id) { fs -> local.outputStream().use { out -> fs.download(path, out) } }
                    remoteOpened[local.path] = RemoteOpened(item.path, local.lastModified(), local.length())
                    if (id == Vault.ID) Vault.store.trackOpened(local, path)
                    local
                }
            }
            _state.update { it.copy(task = null) }
            result.onSuccess { onReady(FileItem.of(it)) }.onFailure { postMessage(tr(R.string.explorer_couldnt_open, item.name, it.message ?: it.javaClass.simpleName)) }
        }
    }

    fun checkRemoteEdits() {
        remoteOpened.keys.filter { !File(it).exists() }.forEach { remoteOpened.remove(it) }
        val edited = remoteOpened.filter { (copy, o) -> File(copy).let { it.exists() && (it.lastModified() != o.stamp || it.length() != o.length) } }
        _state.update { it.copy(remoteEdits = edited.map { (copy, o) -> RemoteEdit(copy, o.uri) }) }
    }

    fun uploadRemoteEdits() {
        val edits = _state.value.remoteEdits
        if (edits.isEmpty()) return
        _state.update { it.copy(remoteEdits = emptyList()) }
        viewModelScope.launch {
            _state.update { it.copy(task = TaskState(tr(R.string.explorer_task_uploading), indeterminate = true)) }
            val failed = ArrayList<String>()
            withContext(Dispatchers.IO) {
                for (e in edits) {
                    val (id, path) = RemoteUri.parse(e.uri) ?: continue
                    runCatching { sessions.use(id) { fs -> File(e.copy).inputStream().use { fs.upload(it, path) } } }
                        .onSuccess {
                            File(e.copy).let { f ->
                                remoteOpened[e.copy] = RemoteOpened(e.uri, f.lastModified(), f.length())
                                if (id == Vault.ID) Vault.store.trackOpened(f, path)
                            }
                        }
                        .onFailure { failed += "${RemoteUri.name(e.uri)}: ${it.message}" }
                }
            }
            _state.update { it.copy(task = null) }
            postMessage(if (failed.isEmpty()) trPlural(R.plurals.explorer_uploaded, edits.size, edits.size) else tr(R.string.explorer_upload_failed, failed.first()))
            refreshAll()
        }
    }

    fun keepRemoteEdits() {
        _state.value.remoteEdits.forEach { e ->
            remoteOpened[e.copy]?.let { o -> File(e.copy).let { f -> remoteOpened[e.copy] = o.copy(stamp = f.lastModified(), length = f.length()) } }
        }
        _state.update { it.copy(remoteEdits = emptyList()) }
    }

    private fun askAboutStorage(pane: Pane, path: String, e: Exception): Boolean {
        val id = RemoteUri.parse(path)?.first ?: return false
        if (id == Vault.ID) {
            if (e !is VaultLockedException || !Vault.ready) return false
            _state.update { it.copy(storagePrompt = StoragePrompt.Vault(pane, path, Vault.connection(), setup = !Vault.store.exists)) }
            return true
        }
        val connection = _state.value.storages.firstOrNull { it.id == id } ?: return false
        val prompt = when {
            e is RemotePasswordNeededException -> StoragePrompt.Password(pane, path, connection)
            e is RemoteHostKeyChangedException && e.presented != null -> StoragePrompt.HostKey(pane, path, connection, e.presented)
            else -> return false
        }
        _state.update { it.copy(storagePrompt = prompt) }
        return true
    }

    fun unlockStorage(password: String) {
        val prompt = _state.value.storagePrompt as? StoragePrompt.Password ?: return
        _state.update { it.copy(storagePrompt = null) }
        viewModelScope.launch {
            sessions.unlock(prompt.connection.id, password)
            navigate(prompt.pane, prompt.path)
        }
    }

    fun trustStorageKey() {
        val prompt = _state.value.storagePrompt as? StoragePrompt.HostKey ?: return
        _state.update { it.copy(storagePrompt = null) }
        viewModelScope.launch {
            remoteRepo.save(prompt.connection.copy(hostKey = prompt.presented))
            sessions.trust(prompt.connection.id, prompt.presented)
            navigate(prompt.pane, prompt.path)
        }
    }

    fun dismissStoragePrompt() = _state.update { it.copy(storagePrompt = null) }

    fun openVault(pane: Pane = _state.value.active) {
        if (!Vault.ready) return postMessage(tr(R.string.vault_err_unavailable))
        val store = Vault.store
        if (store.isUnlocked) return navigate(pane, Vault.root)
        _state.update { it.copy(storagePrompt = StoragePrompt.Vault(pane, Vault.root, Vault.connection(), setup = !store.exists)) }
    }

    fun vaultReady() {
        val prompt = _state.value.storagePrompt as? StoragePrompt.Vault ?: return
        _state.update { it.copy(storagePrompt = null) }
        if (prompt.moving.isNotEmpty()) runVaultMove(prompt.pane, prompt.moving) else navigate(prompt.pane, prompt.path)
    }

    fun moveToVault(pane: Pane, items: List<FileItem>) {
        if (items.isEmpty()) return
        if (items.any { RemoteUri.isRemote(it.path) }) return notOnStorage()
        if (!Vault.ready) return postMessage(tr(R.string.vault_err_unavailable))
        val store = Vault.store
        if (!store.isUnlocked) {
            _state.update { it.copy(storagePrompt = StoragePrompt.Vault(pane, Vault.root, Vault.connection(), setup = !store.exists, moving = items)) }
            return
        }
        runVaultMove(pane, items)
    }

    fun moveSelectionToVault(pane: Pane) = moveToVault(pane, selectedItems(pane))

    private fun runVaultMove(pane: Pane, items: List<FileItem>) {
        clearSelection(pane)
        val conflict = conflictPolicy() ?: Conflict.KEEP_BOTH
        runTask(tr(R.string.vault_task_moving), { VaultTransfers.moveIn(items, Vault.store, "/", conflict) }, sound = UiSound.LOCK) { done ->
            if (done.okCount > 0) postMessage(trPlural(R.plurals.vault_moved, done.okCount, done.okCount))
            refreshAll()
        }
    }

    fun restoreFromVault(pane: Pane, items: List<FileItem> = selectedItems(pane)) {
        val paths = items.mapNotNull { RemoteUri.parse(it.path)?.takeIf { (id, _) -> id == Vault.ID }?.second }
        if (paths.isEmpty() || !Vault.ready) return
        clearSelection(pane)
        val other = _state.value.pane(pane.other).path
        val fallback = if (!RemoteUri.isRemote(other)) File(other) else File(rootPath, Environment.DIRECTORY_DOWNLOADS)
        val conflict = conflictPolicy() ?: Conflict.KEEP_BOTH
        runTask(tr(R.string.vault_task_restoring), { VaultTransfers.restore(paths, Vault.store, fallback, conflict) }, sound = UiSound.UNLOCK) { done ->
            if (done.created.isNotEmpty()) postMessage(trPlural(R.plurals.vault_restored, done.created.size, done.created.size))
            refreshAll()
        }
    }

    fun lockVault() = viewModelScope.launch {
        withContext(Dispatchers.IO) { if (Vault.ready) Vault.store.lock() }
        UiSounds.play(UiSound.LOCK)
    }

    private fun leaveVault(locked: Boolean) {
        var left = false
        for (pane in Pane.entries) {
            if (!Vault.isVault(_state.value.pane(pane).path)) continue
            left = true
            val back = _state.value.pane(pane).history.back.lastOrNull { !Vault.isVault(it) } ?: rootPath
            updatePane(pane) { it.copy(items = emptyList(), selected = emptySet()) }
            navigate(pane, back, history = HistoryOp.NONE)
        }
        remoteOpened.keys.filter { it.contains("/remote-panes/${Vault.ID}/") }.forEach { remoteOpened.remove(it) }
        if (left && locked) postMessage(tr(R.string.vault_locked_message))
    }

    private fun transferTemp(): File = File(getApplication<Application>().cacheDir, "transfers").apply { mkdirs() }

    private fun notOnStorage() = postMessage(tr(R.string.explorer_not_on_storage))


    override fun onCleared() {
        sessions.closeAll()
    }

    fun notifyPathsCopied(count: Int) =
        postMessage(if (count == 1) tr(R.string.props_path_copied) else trPlural(R.plurals.props_paths_copied, count, count))

    private fun postMessage(msg: String?) {
        if (msg != null) _state.update { it.copy(message = msg) }
    }

    private inline fun updatePane(pane: Pane, crossinline block: (PaneState) -> PaneState) =
        _state.update {
            if (pane == Pane.LEFT) it.copy(left = block(it.left)) else it.copy(right = block(it.right))
        }

    companion object {
        fun factory(app: Application): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                ExplorerViewModel(app) as T
        }
    }
}
