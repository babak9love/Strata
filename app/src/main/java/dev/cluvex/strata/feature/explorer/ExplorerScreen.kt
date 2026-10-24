package dev.cluvex.strata.feature.explorer

import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import java.io.File
import kotlinx.coroutines.delay
import dev.cluvex.strata.core.i18n.separator
import dev.cluvex.strata.feature.vault.VaultSetupDialog
import dev.cluvex.strata.feature.vault.VaultUnlockDialog
import android.app.Activity
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.automirrored.rounded.ManageSearch
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SortByAlpha
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.fs.Mounts
import dev.cluvex.strata.core.data.sortFor
import dev.cluvex.strata.core.model.FolderSorts
import dev.cluvex.strata.core.archive.ArchiveFormat
import dev.cluvex.strata.core.archive.ArchiveManager
import dev.cluvex.strata.core.fs.Conflict
import dev.cluvex.strata.core.model.FileItem
import dev.cluvex.strata.core.open.Opener
import dev.cluvex.strata.core.remote.RemoteProtocol
import dev.cluvex.strata.core.remote.RemoteUri
import dev.cluvex.strata.core.termux.Project
import dev.cluvex.strata.core.termux.TermuxProjects
import dev.cluvex.strata.core.util.FileKind
import dev.cluvex.strata.core.util.FileKinds
import dev.cluvex.strata.core.util.IntentUtils
import dev.cluvex.strata.feature.explorer.dnd.DragLayer
import dev.cluvex.strata.feature.explorer.dnd.FileDragState
import dev.cluvex.strata.feature.explorer.dnd.LocalFileDrag
import dev.cluvex.strata.feature.explorer.dnd.dragHost
import dev.cluvex.strata.feature.apk.SignApkDialog
import dev.cluvex.strata.feature.explorer.components.BatchRenameDialog
import dev.cluvex.strata.feature.explorer.components.FolderIconDialog
import dev.cluvex.strata.feature.explorer.components.RenameDialog
import dev.cluvex.strata.feature.explorer.components.BookmarkActions
import dev.cluvex.strata.feature.explorer.components.BookmarkChipsBar
import dev.cluvex.strata.feature.explorer.components.isStorageRoot
import dev.cluvex.strata.feature.explorer.components.BookmarkEditDialog
import dev.cluvex.strata.core.bookmarks.Bookmark
import dev.cluvex.strata.core.bookmarks.Bookmarks
import dev.cluvex.strata.feature.explorer.components.BookmarksSheet
import dev.cluvex.strata.feature.explorer.components.ProtectedFolderActions
import dev.cluvex.strata.feature.explorer.components.ProtectedFoldersSheet
import dev.cluvex.strata.feature.explorer.components.BottomBarActions
import dev.cluvex.strata.feature.explorer.components.ChecksumDialog
import dev.cluvex.strata.feature.explorer.components.ClipboardQueueDialog
import dev.cluvex.strata.feature.explorer.components.CreateArchiveDialog
import dev.cluvex.strata.feature.explorer.components.CreateLinkDialog
import dev.cluvex.strata.feature.explorer.components.DeleteDialog
import dev.cluvex.strata.feature.explorer.components.EncryptDialog
import dev.cluvex.strata.feature.explorer.components.ExplorerBottomBar
import dev.cluvex.strata.feature.explorer.components.InputDialog
import dev.cluvex.strata.feature.explorer.components.InputSelection
import dev.cluvex.strata.feature.explorer.components.ItemActionSheet
import dev.cluvex.strata.feature.explorer.components.ItemActions
import dev.cluvex.strata.feature.explorer.components.MergeDialog
import dev.cluvex.strata.feature.explorer.components.PaneColumn
import dev.cluvex.strata.feature.explorer.components.PasswordDialog
import dev.cluvex.strata.feature.explorer.components.SortSheet
import dev.cluvex.strata.feature.explorer.components.SplitDialog
import dev.cluvex.strata.feature.explorer.components.TaskOverlay
import dev.cluvex.strata.feature.explorer.components.TransferDialog
import dev.cluvex.strata.feature.props.MediaInfoKind
import dev.cluvex.strata.feature.props.MediaInfoSheet
import dev.cluvex.strata.feature.props.PropertiesSheet
import dev.cluvex.strata.feature.remote.HostKeyChangedDialog
import dev.cluvex.strata.feature.remote.StoragePasswordDialog
import dev.cluvex.strata.feature.settings.ToolbarTab
import dev.cluvex.strata.feature.termux.NewProjectSheet
import dev.cluvex.strata.feature.termux.ProjectRunSheet
import dev.cluvex.strata.feature.termux.TerminalGate
import dev.cluvex.strata.feature.termux.TermuxViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface Dialog {
    data object None : Dialog
    data class NewFolder(val pane: Pane) : Dialog
    data class NewFile(val pane: Pane) : Dialog
    data object Delete : Dialog
    data object Sort : Dialog
    data object Bookmarks : Dialog
    data object ProtectedFolders : Dialog
    data object Clipboard : Dialog
    data class CreateArchive(val pane: Pane, val items: List<FileItem>) : Dialog
    data class BatchRename(val pane: Pane, val items: List<FileItem>) : Dialog
    data class CreateLink(val pane: Pane, val items: List<FileItem>) : Dialog
    data class Split(val pane: Pane, val item: FileItem) : Dialog
    data class Merge(val pane: Pane, val items: List<FileItem>) : Dialog
    data class Encrypt(val pane: Pane, val item: FileItem) : Dialog
    data class Decrypt(val pane: Pane, val item: FileItem) : Dialog
    data class SignApk(val item: FileItem) : Dialog
    data class Transfer(val move: Boolean) : Dialog
    data class JumpTo(val pane: Pane) : Dialog
    data class Rename(val pane: Pane, val item: FileItem) : Dialog
    data class FolderIcon(val item: FileItem) : Dialog
    data class Unprotect(val item: FileItem) : Dialog
    data class ItemSheet(val pane: Pane, val item: FileItem) : Dialog
    data class Checksum(val item: FileItem) : Dialog
    data class Properties(val items: List<FileItem>) : Dialog
    data class MediaInfo(val item: FileItem, val kind: MediaInfoKind) : Dialog
    data class NewProject(val dir: String) : Dialog
    data class RunProject(val project: Project) : Dialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExplorerScreen(
    vm: ExplorerViewModel,
    onOpenFile: (FileItem) -> Unit,
    onOpenWithOpener: (FileItem, Opener) -> Unit,
    onOpenWith: (FileItem) -> Unit,
    onCompare: (FileItem, FileItem) -> Unit,
    onOpenSearch: (String) -> Unit,
    onOpenDrawer: () -> Unit,
    onCustomize: (ToolbarTab) -> Unit = {},
    onOpenSettings: () -> Unit = {},
    drawerOpen: Boolean = false,
    appSnackbar: SnackbarHostState? = null,
) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var dialog by remember { mutableStateOf<Dialog>(Dialog.None) }
    /** Runs one queued copy/cut (or shared-file action) in the active pane's folder. */
    fun runClip(clip: ClipboardState) {
        when (clip.shareMode) {
            ShareMode.EXTRACT -> {
                clip.items.firstOrNull()?.let { vm.extractArchive(ui.active, it) }
                vm.dropClip(clip.id)
            }
            ShareMode.ARCHIVE -> dialog = Dialog.CreateArchive(ui.active, clip.items)
            null -> vm.paste(ui.active, clip.id)
        }
    }
    var editingBookmark by remember { mutableStateOf<Bookmark?>(null) }
    var overflow by remember { mutableStateOf(false) }
    var lastBackPress by remember { mutableLongStateOf(0L) }
    val rootPath = vm.rootPath
    val textClipboard = dev.cluvex.strata.ui.rememberTextClipboard()
    val dual = ui.settings.dualPanePreferred
    val termux = viewModel<TermuxViewModel>().session
    val sshHere = RemoteUri.parse(ui.activePane.path)?.let { (id, dir) ->
        ui.storages.firstOrNull { it.id == id && it.protocol == RemoteProtocol.SFTP }?.let { it to dir }
    }
    val activePath = ui.activePane.path
    val project by produceState<Project?>(null, activePath, ui.activePane.items) {
        value = if (RemoteUri.isRemote(activePath)) null else withContext(Dispatchers.IO) { TermuxProjects.detect(java.io.File(activePath)) }
    }

    LaunchedEffect(ui.message) {
        ui.message?.let {
            vm.consumeMessage()
            // Launched in `scope`, not this effect: consumeMessage() changes the key, which would
            // cancel this coroutine and dismiss the snackbar a frame after it appeared.
            scope.launch { (appSnackbar ?: snackbar).showSnackbar(it) }
        }
    }

    BackHandler(enabled = !drawerOpen) {
        val pane = ui.activePane
        when {
            pane.hasSelection -> vm.clearSelection(ui.active)
            ui.searchActive -> vm.setSearchActive(false)
            pane.path != rootPath && pane.path != "/" -> vm.navigateUp(ui.active)
            !ui.settings.behavior.doubleBackToExit -> (context as? Activity)?.finish()
            else -> {
                val now = SystemClock.uptimeMillis()
                if (now - lastBackPress < 2000) {
                    (context as? Activity)?.finish()
                } else {
                    lastBackPress = now
                    scope.launch { snackbar.showSnackbar(tr(R.string.explorer_press_back_again)) }
                }
            }
        }
    }

    fun withLocal(item: FileItem, use: (FileItem) -> Unit) {
        if (RemoteUri.isRemote(item.path)) vm.openRemote(item, use) else use(item)
    }

    fun localOnly(item: FileItem, block: () -> Unit) {
        if (RemoteUri.isRemote(item.path)) {
            dialog = Dialog.None
            scope.launch { (appSnackbar ?: snackbar).showSnackbar(tr(R.string.explorer_not_on_storage)) }
        } else {
            block()
        }
    }

    fun handleOpen(pane: Pane, item: FileItem) {
        when {
            item.isDirectory -> vm.open(pane, item)
            FileKinds.of(item) == FileKind.ENCRYPTED -> localOnly(item) { dialog = Dialog.Decrypt(pane, item) }
            else -> withLocal(item) { onOpenFile(it) }
        }
    }

    fun visibleOfActive() = visibleItems(ui.activePane, ui.settings, ui.query).map { it.path }

    val selection = ui.activePane.items.filter { it.path in ui.activePane.selected }
    val single = selection.singleOrNull()
    val smaliFolder by produceState(false, single?.path, ui.active) {
        value = single != null && single.isDirectory && vm.hasSmali(ui.active, single)
    }
    val jarFile = single != null && !single.isDirectory && single.extension.equals("jar", ignoreCase = true)
    val archiveFile = single != null && !single.isDirectory && ArchiveManager.isExtractable(single.name)

    fun selectionInOrder() = visibleItems(ui.activePane, ui.settings, ui.query).filter { it.path in ui.activePane.selected }

    fun archiveName(items: List<FileItem>, folder: String): String =
        items.singleOrNull()?.let { ArchiveFormat.ZIP.defaultName(it).removeSuffix(ArchiveFormat.ZIP.extension) }
            ?: java.io.File(folder).name.ifEmpty { "archive" }

    fun linkParent(pane: Pane): String =
        if (dual && ui.pane(pane.other).path != ui.pane(pane).path) ui.pane(pane.other).path else ui.pane(pane).path

    val drag = remember { FileDragState() }
    drag.haptics = LocalHapticFeedback.current
    drag.onDrop = { session, spot -> vm.dropInto(session.pane, session.items, spot.dir) }
    drag.onSpring = { spot -> Pane.entries.getOrNull(spot.pane)?.let { p -> vm.setActive(p); vm.openPath(p, spot.dir) } }
    val host = remember { arrayOfNulls<LayoutCoordinates>(1) }

    ui.protectQuestions.firstOrNull()?.let { q ->
        val movedName = File(q.to).name
        val existingName = File(q.from).name
        AlertDialog(
            onDismissRequest = { vm.answerProtectQuestion(keepMoved = true, keepExisting = false) },
            title = { Text(stringResource(R.string.explorer_protected_ask_title)) },
            text = { Text(stringResource(R.string.explorer_protected_ask_text, existingName, movedName)) },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { vm.answerProtectQuestion(keepMoved = true, keepExisting = false) }) {
                        Text(stringResource(R.string.explorer_protected_ask_moved, movedName))
                    }
                    TextButton(onClick = { vm.answerProtectQuestion(keepMoved = false, keepExisting = true) }) {
                        Text(stringResource(R.string.explorer_protected_ask_existing, existingName))
                    }
                    TextButton(onClick = { vm.answerProtectQuestion(keepMoved = true, keepExisting = true) }) {
                        Text(stringResource(R.string.explorer_protected_ask_both))
                    }
                }
            },
        )
    }

    LaunchedEffect(ui.undo?.seq) {
        val u = ui.undo ?: return@LaunchedEffect
        val result = (appSnackbar ?: snackbar).showSnackbar(u.message, actionLabel = tr(R.string.explorer_undo), duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed) vm.undoMove(u) else vm.consumeUndo(u.seq)
    }

    LaunchedEffect(ui.incoming?.seq) {
        val c = ui.incoming ?: return@LaunchedEffect
        val result = (appSnackbar ?: snackbar).showSnackbar(
            tr(R.string.explorer_incoming_hidden, c.name),
            actionLabel = tr(R.string.explorer_incoming_save),
            withDismissAction = true,
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) vm.saveIncomingCopy(c) else vm.consumeIncoming(c.seq)
    }

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { host[0] = it }
            .dragHost(drag) { host[0] },
    ) {
    CompositionLocalProvider(LocalFileDrag provides drag) {
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (ui.searchActive) {
                FilterBar(query = ui.query, onQuery = vm::setQuery, onClose = { vm.setSearchActive(false) })
            } else {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onOpenDrawer) { Icon(Icons.Rounded.Menu, contentDescription = stringResource(R.string.explorer_menu)) }
                    },
                    title = { ExplorerTitle(ui.activePane, visibleItems(ui.activePane, ui.settings, ui.query)) },
                    actions = {
                        IconButton(onClick = { vm.setSearchActive(true) }) { Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.explorer_filter)) }
                        IconButton(onClick = {
                            if (RemoteUri.isRemote(ui.activePane.path)) {
                                scope.launch { snackbar.showSnackbar(tr(R.string.explorer_search_local_only)) }
                            } else {
                                onOpenSearch(ui.activePane.path)
                            }
                        }) {
                            Icon(Icons.AutoMirrored.Rounded.ManageSearch, contentDescription = stringResource(R.string.explorer_advanced_search))
                        }
                        IconButton(onClick = { dialog = Dialog.Sort }) { Icon(Icons.Rounded.SortByAlpha, contentDescription = stringResource(R.string.common_sort)) }
                        Box {
                            IconButton(onClick = { overflow = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.explorer_options)) }
                            DropdownMenu(expanded = overflow, onDismissRequest = { overflow = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.explorer_add_to_bookmarks)) }, onClick = { overflow = false; vm.addBookmarks(listOf(ui.activePane.path)) })
                                DropdownMenuItem(text = { Text(stringResource(R.string.explorer_refresh_both)) }, onClick = { overflow = false; vm.refreshAll() })
                                DropdownMenuItem(text = { Text(stringResource(R.string.explorer_jump_to_path_more)) }, onClick = { overflow = false; dialog = Dialog.JumpTo(ui.active) })
                                DropdownMenuItem(
                                    text = { Text(stringResource(if (ui.settings.showHidden) R.string.common_hide_hidden else R.string.common_show_hidden)) },
                                    onClick = { overflow = false; vm.setShowHidden(!ui.settings.showHidden) },
                                )
                                val mount = ui.activePane.mount
                                if (ui.settings.rootAccess && mount != null && mount.point in Mounts.SYSTEM_POINTS) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(if (mount.readOnly) R.string.explorer_mount_rw else R.string.explorer_mount_ro, mount.point)) },
                                        onClick = { overflow = false; vm.remount(ui.active, writable = mount.readOnly) },
                                    )
                                }
                                sshHere?.let { (c, dir) ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.explorer_ssh_here)) },
                                        onClick = { overflow = false; termux.openSsh(c.host, c.port, c.username, dir) },
                                    )
                                }
                                DropdownMenuItem(text = { Text(stringResource(R.string.common_settings)) }, onClick = { overflow = false; onOpenSettings() })
                            }
                        }
                    },
                )
            }
        },
        bottomBar = {
            ExplorerBottomBar(
                selectionCount = ui.activePane.selected.size,
                canBack = ui.activePane.history.canBack,
                canForward = ui.activePane.history.canForward,
                canUp = !isStorageRoot(ui.activePane.path, rootPath),
                activeIsLeft = ui.active == Pane.LEFT,
                dual = dual,
                clipboardCount = ui.clipboards.sumOf { it.items.size },
                clipboardIsMove = ui.clipboard?.move == true,
                selectedFiles = selection.count { !it.isDirectory },
                remote = ui.isRemote(ui.active),
                projectLabel = project?.kind?.label,
                smaliFolder = smaliFolder,
                jarFile = jarFile,
                archiveFile = archiveFile,
                protectedFolder = single?.let { it.isDirectory && vm.isProtected(it.path) } == true,
                vault = ui.isVault(ui.active),
                browse = ui.settings.actions.browse,
                selection = ui.settings.actions.selection,
                showHidden = ui.settings.showHidden,
                actions = BottomBarActions(
                    onBack = { vm.goBack(ui.active) },
                    onForward = { vm.goForward(ui.active) },
                    onUp = { vm.navigateUp(ui.active) },
                    onHome = { vm.goHome(ui.active) },
                    onSwap = vm::swapPanes,
                    onNewFolder = { dialog = Dialog.NewFolder(ui.active) },
                    onNewFile = { dialog = Dialog.NewFile(ui.active) },
                    onRefresh = { vm.refresh(ui.active) },
                    onPaste = { ui.clipboard?.let(::runClip) },
                    onShowClipboard = { dialog = Dialog.Clipboard },
                    onCancelClipboard = vm::clearClipboard,
                    onSelectAll = { vm.selectAll(ui.active, visibleOfActive()) },
                    onClearSelection = { vm.clearSelection(ui.active) },
                    onCopyToOther = { vm.beginTransfer(move = false) },
                    onMoveToOther = { vm.beginTransfer(move = true) },
                    onCopyToClipboard = { vm.copySelectionToClipboard(ui.active, move = false) },
                    onCutToClipboard = { vm.copySelectionToClipboard(ui.active, move = true) },
                    onDelete = { dialog = Dialog.Delete },
                    onRename = { selection.firstOrNull()?.let { dialog = Dialog.Rename(ui.active, it) } },
                    onCompress = { dialog = Dialog.CreateArchive(ui.active, selectionInOrder()) },
                    onShare = { IntentUtils.share(context, selection) },
                    onProperties = { dialog = Dialog.Properties(selection) },
                    onCopyPath = {
                        textClipboard.setText(selection.joinToString("\n") { it.path })
                        vm.notifyPathsCopied(selection.size)
                        vm.clearSelection(ui.active)
                    },
                    onInvert = { vm.invertSelection(ui.active, visibleOfActive()) },
                    onBookmarks = { dialog = Dialog.Bookmarks },
                    onBookmarkFolder = { vm.addBookmarks(listOf(ui.activePane.path)) },
                    onBookmarkSelection = { vm.addSelectionToBookmarks(ui.active) },
                    onFolderIcon = { single?.takeIf { it.isDirectory }?.let { dialog = Dialog.FolderIcon(it); vm.clearSelection(ui.active) } },
                    onProtectedFolders = { dialog = Dialog.ProtectedFolders },
                    onProtect = {
                        single?.takeIf { it.isDirectory }?.let {
                            if (vm.isProtected(it.path)) dialog = Dialog.Unprotect(it) else vm.protectFolder(it)
                            vm.clearSelection(ui.active)
                        }
                    },
                    onBatchRename = { dialog = Dialog.BatchRename(ui.active, selectionInOrder()) },
                    onCreateLinks = { dialog = Dialog.CreateLink(ui.active, selectionInOrder()) },
                    onMerge = { dialog = Dialog.Merge(ui.active, selectionInOrder().filterNot { it.isDirectory }) },
                    onCompare = {
                        val two = selectionInOrder().filterNot { it.isDirectory }
                        if (two.size == 2) onCompare(two[0], two[1])
                    },
                    onNewProject = { dialog = Dialog.NewProject(ui.activePane.path) },
                    onOpenTerminal = { termux.openTerminal(ui.activePane.path) },
                    onRunProject = { project?.let { dialog = Dialog.RunProject(it) } },
                    onSmaliToDex = { single?.let { vm.smaliToDex(ui.active, it) } },
                    onJarToDex = { single?.let { vm.jarToDex(ui.active, it) } },
                    onFilter = { vm.setSearchActive(true) },
                    onSearch = {
                        if (RemoteUri.isRemote(ui.activePane.path)) {
                            scope.launch { snackbar.showSnackbar(tr(R.string.explorer_search_local_only)) }
                        } else {
                            onOpenSearch(ui.activePane.path)
                        }
                    },
                    onSort = { dialog = Dialog.Sort },
                    onJump = { dialog = Dialog.JumpTo(ui.active) },
                    onToggleHidden = { vm.setShowHidden(!ui.settings.showHidden) },
                    onCustomize = onCustomize,
                    onMoveToVault = { vm.moveSelectionToVault(ui.active) },
                    onRestore = { vm.restoreFromVault(ui.active) },
                    onOpenWith = { single?.takeIf { !it.isDirectory }?.let { f -> withLocal(f) { onOpenWith(it) } } },
                    onExtract = { single?.takeIf { !it.isDirectory }?.let { f -> localOnly(f) { vm.extractArchive(ui.active, f) } } },
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (!dual) {
                BookmarkChipsBar(
                    bookmarks = ui.bookmarks,
                    currentPath = ui.activePane.path,
                    onOpen = { vm.openBookmark(ui.active, it) },
                    onLongPress = { editingBookmark = it },
                )
            }
            Row(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
            val panes = if (dual) listOf(Pane.LEFT, Pane.RIGHT) else listOf(ui.active)
            for (pane in panes) {
                val state = ui.pane(pane)
                val visible = remember(state.items, ui.settings, ui.query) { visibleItems(state, ui.settings, ui.query) }
                PaneColumn(
                    state = state,
                    visible = visible,
                    isActive = ui.active == pane,
                    showActive = dual,
                    rootPath = rootPath,
                    onActivate = { vm.setActive(pane) },
                    onUp = { vm.setActive(pane); vm.navigateUp(pane) },
                    onOpen = { handleOpen(pane, it) },
                    onToggleSelect = { vm.toggleSelect(pane, it) },
                    onItemMenu = { dialog = Dialog.ItemSheet(pane, it) },
                    onJumpTo = { dialog = Dialog.JumpTo(pane) },
                    onRefresh = { vm.setActive(pane); vm.refresh(pane) },
                    modifier = Modifier.weight(1f).fillMaxSize(),
                    displayPath = if (RemoteUri.isRemote(state.path)) ui.displayPath(state.path) else null,
                    pane = pane,
                )
            }
        }
        }
    }
    }
    DragLayer(drag) { host[0]?.positionInRoot() ?: Offset.Zero }
    }

    editingBookmark?.let { b ->
        BookmarkEditDialog(
            bookmark = b,
            groups = Bookmarks.groups(ui.bookmarks),
            onSave = { edited -> vm.updateBookmark(b.path, edited); editingBookmark = null },
            onDismiss = { editingBookmark = null },
        )
    }

    when (val d = dialog) {
        Dialog.None -> Unit
        is Dialog.NewFolder -> InputDialog(
            title = stringResource(R.string.common_new_folder), label = stringResource(R.string.explorer_folder_name),
            initial = remember(d) { numberedName("New folder", "", ui.pane(d.pane).items) }, select = InputSelection.ALL,
            validate = nameTakenCheck(ui.pane(d.pane).items, stringResource(R.string.files_item_exists)),
            onConfirm = { vm.newFolder(d.pane, it); dialog = Dialog.None },
            onDismiss = { dialog = Dialog.None },
        )
        is Dialog.NewFile -> InputDialog(
            title = stringResource(R.string.common_new_file), label = stringResource(R.string.explorer_file_name),
            initial = remember(d) { numberedName("New file", ".txt", ui.pane(d.pane).items) }, select = InputSelection.ALL,
            validate = nameTakenCheck(ui.pane(d.pane).items, stringResource(R.string.files_item_exists)),
            onConfirm = { vm.newFile(d.pane, it); dialog = Dialog.None },
            onDismiss = { dialog = Dialog.None },
        )
        Dialog.Clipboard -> ClipboardQueueDialog(
            clips = ui.clipboards,
            onRun = { clip ->
                dialog = Dialog.None
                runClip(clip)
            },
            onRemove = { vm.dropClip(it.id) },
            onClear = { vm.clearClipboard(); dialog = Dialog.None },
            onDismiss = { dialog = Dialog.None },
        )
        Dialog.Bookmarks -> BookmarksSheet(
            bookmarks = ui.bookmarks,
            actions = BookmarkActions(
                onOpen = { vm.openBookmark(ui.active, it); dialog = Dialog.None },
                onAddCurrent = { vm.addBookmarks(listOf(ui.activePane.path)) },
                onUpdate = { path, edited -> vm.updateBookmark(path, edited) },
                onMove = { path, offset -> vm.moveBookmark(path, offset) },
                onDelete = { vm.removeBookmark(it) },
                onRenameGroup = { from, to -> vm.renameBookmarkGroup(from, to) },
                onDeleteGroup = { vm.deleteBookmarkGroup(it) },
            ),
            onDismiss = { dialog = Dialog.None },
        )
        Dialog.ProtectedFolders -> ProtectedFoldersSheet(
            folders = ui.settings.protectedFolders,
            actions = ProtectedFolderActions(
                onOpen = { vm.openPath(ui.active, it); dialog = Dialog.None },
                onRemove = { vm.unprotectFolder(it) },
                onAddCurrent = if (ui.isRemote(ui.active) || ui.isVault(ui.active)) null else ({ vm.protectPath(ui.activePane.path) }),
            ),
            currentPath = ui.activePane.path,
            onDismiss = { dialog = Dialog.None },
        )
        is Dialog.CreateArchive -> CreateArchiveDialog(
            items = d.items,
            defaultName = archiveName(d.items, ui.pane(d.pane).path),
            onConfirm = { name, options, independent ->
                vm.createArchive(d.pane, d.items, name, options, independent)
                vm.dropShareClips(ShareMode.ARCHIVE)
                dialog = Dialog.None
            },
            onDismiss = { dialog = Dialog.None },
        )
        is Dialog.BatchRename -> BatchRenameDialog(
            items = d.items,
            history = ui.renameHistory,
            preview = { rule, order, descending -> vm.previewRename(d.pane, d.items, rule, order, descending) },
            onApply = { plan, rule -> vm.applyRename(d.pane, plan, rule); dialog = Dialog.None },
            onDismiss = { dialog = Dialog.None },
        )
        is Dialog.CreateLink -> CreateLinkDialog(
            items = d.items,
            defaultParent = linkParent(d.pane),
            onConfirm = { parent, requests -> vm.createLinks(d.pane, parent, requests); dialog = Dialog.None },
            onDismiss = { dialog = Dialog.None },
        )
        is Dialog.Split -> SplitDialog(
            item = d.item,
            onConfirm = { method, pattern -> vm.splitFile(d.pane, d.item, method, pattern); dialog = Dialog.None },
            onDismiss = { dialog = Dialog.None },
        )
        is Dialog.Merge -> MergeDialog(
            parts = d.items,
            existingNames = ui.pane(d.pane).items.mapTo(HashSet()) { it.name },
            onConfirm = { ordered, output, pdf -> vm.mergeFiles(d.pane, ordered, output, pdf); dialog = Dialog.None },
            onDismiss = { dialog = Dialog.None },
        )
        is Dialog.Encrypt -> EncryptDialog(
            item = d.item,
            onConfirm = { pw -> vm.encryptFile(d.pane, d.item, pw); dialog = Dialog.None },
            onDismiss = { dialog = Dialog.None },
        )
        is Dialog.Decrypt -> PasswordDialog(
            title = stringResource(R.string.explorer_decrypt_file),
            fileName = d.item.name,
            wrong = false,
            onConfirm = { pw -> vm.decryptFile(d.pane, d.item, pw); dialog = Dialog.None },
            onDismiss = { dialog = Dialog.None },
        )
        is Dialog.SignApk -> SignApkDialog(
            item = d.item,
            onConfirm = { request -> vm.signApk(d.item, request); dialog = Dialog.None },
            onDismiss = { dialog = Dialog.None },
        )
        is Dialog.JumpTo -> InputDialog(
            title = stringResource(R.string.explorer_jump_to_path), label = stringResource(R.string.common_path), initial = ui.pane(d.pane).path,
            confirmText = stringResource(R.string.explorer_go), select = InputSelection.ALL, ltr = true,
            onConfirm = { vm.openPath(d.pane, it.trim()); dialog = Dialog.None },
            onDismiss = { dialog = Dialog.None },
        )
        is Dialog.FolderIcon -> FolderIconDialog(d.item.path) { dialog = Dialog.None }
        is Dialog.Unprotect -> AlertDialog(
            onDismissRequest = { dialog = Dialog.None },
            title = { Text(stringResource(R.string.explorer_protected_remove_title)) },
            text = { Text(stringResource(R.string.explorer_protected_remove_text, d.item.name)) },
            confirmButton = {
                TextButton(onClick = { vm.unprotectFolder(d.item.path); dialog = Dialog.None }) {
                    Text(stringResource(R.string.explorer_protected_remove_confirm))
                }
            },
            dismissButton = { TextButton(onClick = { dialog = Dialog.None }) { Text(stringResource(R.string.common_cancel)) } },
        )
        is Dialog.Rename -> RenameDialog(
            name = d.item.name, isDirectory = d.item.isDirectory,
            onConfirm = { vm.rename(d.pane, d.item, it); vm.clearSelection(d.pane); dialog = Dialog.None },
            onDismiss = { dialog = Dialog.None },
        )
        is Dialog.Transfer -> Unit
        Dialog.Delete -> {
        val blocked = remember(selection) { vm.protectedBlocked(selection) }
        if (blocked.isNotEmpty() && blocked.size == selection.size) {
            // Nothing in the selection may be deleted: say so right away instead of asking to confirm.
            LaunchedEffect(Unit) {
                vm.reportProtected(blocked)
                dialog = Dialog.None
            }
        } else {
        var delStats by remember { mutableStateOf<dev.cluvex.strata.core.props.DirStats?>(null) }
        var delCounting by remember { mutableStateOf(true) }
        LaunchedEffect(Unit) {
            runCatching { vm.deleteStats(ui.active, selection).collect { delStats = it } }
            delCounting = false
        }
        DeleteDialog(
            count = ui.activePane.selected.size,
            stats = delStats,
            counting = delCounting,
            recycleByDefault = ui.settings.trashByDefault,
            allowRecycle = remember(ui.active, ui.activePane.path, ui.activePane.viaRoot) { vm.canRecycle(ui.active) },
            singleName = selection.singleOrNull()?.name,
            singleIsDirectory = selection.singleOrNull()?.isDirectory == true,
            onConfirm = { toBin -> vm.deleteSelection(ui.active, toBin); dialog = Dialog.None },
            onDismiss = { dialog = Dialog.None },
        )
        }
        }
        Dialog.Sort -> {
            val folder = ui.activePane.path
            var own by remember(folder) { mutableStateOf(ui.settings.folderSorts.containsKey(FolderSorts.key(folder))) }
            var order by remember(folder) { mutableStateOf(ui.settings.sortFor(folder)) }
            SortSheet(
                current = order,
                showHidden = ui.settings.showHidden,
                onChange = { o ->
                    order = o
                    if (own) vm.setFolderSort(folder, o) else vm.setSort(o)
                },
                onToggleHidden = vm::setShowHidden,
                onDismiss = { dialog = Dialog.None },
                thisFolderOnly = own,
                folderName = ui.displayPath(folder).substringAfterLast('/').ifBlank { ui.displayPath(folder) },
                onThisFolderOnly = { on ->
                    own = on
                    vm.setFolderSort(folder, if (on) order else null)
                    if (!on) order = ui.settings.sortOrder
                },
            )
        }
        is Dialog.ItemSheet -> ItemActionSheet(
            item = d.item,
            smaliFolder = produceState(false, d.item.path) { value = d.item.isDirectory && vm.hasSmali(d.pane, d.item) }.value,
            actions = ItemActions(
                onSelect = { vm.setActive(d.pane); vm.toggleSelect(d.pane, d.item); dialog = Dialog.None },
                onOpen = {
                    dialog = Dialog.None
                    handleOpen(d.pane, d.item)
                },
                onOpenWith = { dialog = Dialog.None; withLocal(d.item) { onOpenWith(it) } },
                onOpenAsText = { dialog = Dialog.None; withLocal(d.item) { onOpenWithOpener(it, Opener.TEXT_EDITOR) } },
                onHex = { dialog = Dialog.None; withLocal(d.item) { onOpenWithOpener(it, Opener.HEX_EDITOR) } },
                onRename = { dialog = Dialog.Rename(d.pane, d.item) },
                onShare = { dialog = Dialog.None; withLocal(d.item) { IntentUtils.share(context, listOf(it)) } },
                onChecksum = { dialog = Dialog.None; withLocal(d.item) { dialog = Dialog.Checksum(it) } },
                onExtract = { localOnly(d.item) { vm.extractArchive(d.pane, d.item); dialog = Dialog.None } },
                onApkInfo = { dialog = Dialog.None; withLocal(d.item) { onOpenWithOpener(it, Opener.APK_VIEWER) } },
                onView = { dialog = Dialog.None; withLocal(d.item) { onOpenWithOpener(it, Opener.ARCHIVE_VIEWER) } },
                onSignApk = { localOnly(d.item) { dialog = Dialog.SignApk(d.item) } },
                onCompress = { localOnly(d.item) { dialog = Dialog.CreateArchive(d.pane, listOf(d.item)) } },
                onAddBookmark = { vm.addBookmarks(listOf(d.item.path)); dialog = Dialog.None },
                onProtect = {
                    if (vm.isProtected(d.item.path)) {
                        dialog = Dialog.Unprotect(d.item)
                    } else {
                        vm.protectFolder(d.item)
                        dialog = Dialog.None
                    }
                },
                onCreateLink = { localOnly(d.item) { dialog = Dialog.CreateLink(d.pane, listOf(d.item)) } },
                onSplit = { localOnly(d.item) { dialog = Dialog.Split(d.pane, d.item) } },
                onEncrypt = { localOnly(d.item) { dialog = Dialog.Encrypt(d.pane, d.item) } },
                onDecrypt = { localOnly(d.item) { dialog = Dialog.Decrypt(d.pane, d.item) } },
                onProperties = { localOnly(d.item) { dialog = Dialog.Properties(listOf(d.item)) } },
                onAudioInfo = { localOnly(d.item) { dialog = Dialog.MediaInfo(d.item, MediaInfoKind.AUDIO) } },
                onVideoInfo = { localOnly(d.item) { dialog = Dialog.MediaInfo(d.item, MediaInfoKind.VIDEO) } },
                onDelete = {
                    vm.setActive(d.pane)
                    vm.clearSelection(d.pane)
                    vm.toggleSelect(d.pane, d.item, sound = false)
                    dialog = Dialog.Delete
                },
                onSmaliToDex = { localOnly(d.item) { dialog = Dialog.None; vm.smaliToDex(d.pane, d.item) } },
                onJarToDex = { localOnly(d.item) { dialog = Dialog.None; vm.jarToDex(d.pane, d.item) } },
                onMoveToVault = { localOnly(d.item) { dialog = Dialog.None; vm.moveToVault(d.pane, listOf(d.item)) } },
                onRestore = { dialog = Dialog.None; vm.restoreFromVault(d.pane, listOf(d.item)) },
            ),
            onDismiss = { dialog = Dialog.None },
            layout = ui.settings.actions.sheet,
            onCustomize = { dialog = Dialog.None; onCustomize(ToolbarTab.SHEET) },
        )
        is Dialog.Checksum -> ChecksumDialog(item = d.item, onDismiss = { dialog = Dialog.None })
        is Dialog.MediaInfo -> MediaInfoSheet(item = d.item, kind = d.kind, onDismiss = { dialog = Dialog.None })
        is Dialog.Properties -> PropertiesSheet(
            items = d.items,
            rootEnabled = ui.settings.rootAccess,
            onDismiss = { dialog = Dialog.None },
            onChanged = { vm.refreshAll() },
        )
        is Dialog.NewProject -> NewProjectSheet(
            parentDir = d.dir,
            session = termux,
            onCreated = { path ->
                dialog = Dialog.None
                vm.revealCreated(d.dir, path)
            },
            onDismiss = { dialog = Dialog.None },
        )
        is Dialog.RunProject -> ProjectRunSheet(d.project, onDismiss = { dialog = Dialog.None }, session = termux)
    }

    if (ui.remoteEdits.isNotEmpty() && dialog == Dialog.None) {
        val edits = ui.remoteEdits
        AlertDialog(
            onDismissRequest = vm::keepRemoteEdits,
            title = { Text(stringResource(R.string.explorer_upload_title)) },
            text = {
                Text(
                    if (edits.size == 1) {
                        stringResource(
                            R.string.explorer_upload_one,
                            RemoteUri.name(edits[0].uri),
                            ui.displayPath(RemoteUri.parent(edits[0].uri) ?: edits[0].uri),
                        )
                    } else {
                        pluralStringResource(R.plurals.explorer_upload_many, edits.size, edits.size)
                    },
                )
            },
            confirmButton = { TextButton(onClick = vm::uploadRemoteEdits) { Text(stringResource(R.string.explorer_upload)) } },
            dismissButton = { TextButton(onClick = vm::keepRemoteEdits) { Text(stringResource(R.string.explorer_not_now)) } },
        )
    }
    TerminalGate(termux, appSnackbar ?: snackbar)
    when (val prompt = ui.storagePrompt) {
        null -> Unit
        is StoragePrompt.Password -> StoragePasswordDialog(prompt.connection, onConfirm = vm::unlockStorage, onDismiss = vm::dismissStoragePrompt)
        is StoragePrompt.HostKey -> HostKeyChangedDialog(
            prompt.connection, old = prompt.connection.hostKey, presented = prompt.presented,
            onTrust = vm::trustStorageKey, onDismiss = vm::dismissStoragePrompt,
        )
        is StoragePrompt.Vault -> if (prompt.setup) {
            VaultSetupDialog(onDismiss = vm::dismissStoragePrompt, onReady = vm::vaultReady)
        } else {
            VaultUnlockDialog(onDismiss = vm::dismissStoragePrompt, onUnlocked = vm::vaultReady)
        }
    }

    ui.transferPrompt?.let { prompt ->
        TransferDialog(
            move = prompt.move,
            count = prompt.count,
            destination = prompt.destination,
            conflicts = prompt.conflicts,
            onConfirm = { conflict -> vm.confirmTransfer(conflict) },
            onDismiss = { vm.cancelTransfer() },
        )
    }

    ui.passwordPrompt?.let { p ->
        PasswordDialog(
            title = stringResource(if (p.purpose == PasswordPurpose.EXTRACT) R.string.explorer_encrypted_archive else R.string.explorer_decrypt_file),
            fileName = p.item.name,
            wrong = p.wrong,
            onConfirm = vm::submitPassword,
            onDismiss = vm::dismissPasswordPrompt,
        )
    }

    ui.task?.let { TaskOverlay(it) }
    ui.build?.let { dev.cluvex.strata.feature.build.BuildDialog(it, onClose = vm::closeBuild) }
    if ((ui.task != null || ui.build?.finished == false) && ui.settings.behavior.keepScreenOnTasks) {
        val view = LocalView.current
        DisposableEffect(view) {
            view.keepScreenOn = true
            onDispose { view.keepScreenOn = false }
        }
    }
}

@Composable
private fun ExplorerTitle(pane: PaneState, visible: List<FileItem>) {
    Column {
        Text("Strata", maxLines = 1)
        Text(
            countsLine(visible),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun countsLine(items: List<FileItem>): String {
    val folders = items.count { it.isDirectory }
    val files = items.size - folders
    return pluralStringResource(R.plurals.common_folders, folders, folders) + separator() + pluralStringResource(R.plurals.common_files, files, files)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterBar(query: String, onQuery: (String) -> Unit, onClose: () -> Unit) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // Opening the filter puts the cursor in the field and brings up the keyboard.
    LaunchedEffect(Unit) {
        delay(100)
        runCatching { focusRequester.requestFocus() }
        keyboard?.show()
    }
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.common_close)) }
        },
        title = {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                placeholder = { Text(stringResource(R.string.explorer_filter_placeholder)) },
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
        },
    )
}

/** Inline validator for name dialogs: flags names already used in this folder (case-insensitive). */
private fun nameTakenCheck(existing: List<FileItem>, message: String): (String) -> String? {
    val taken = existing.mapTo(HashSet()) { it.name.lowercase() }
    return { name -> if (name.lowercase() in taken) message else null }
}

/** "New folder", then "New folder 1", "New folder 2"… — the first name no item in the folder already has. */
private fun numberedName(base: String, ext: String, existing: List<FileItem>): String {
    val taken = existing.mapTo(HashSet()) { it.name.lowercase() }
    var name = base + ext
    var i = 1
    while (name.lowercase() in taken) {
        name = "$base $i$ext"
        i++
    }
    return name
}
