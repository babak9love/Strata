package dev.cluvex.strata.feature.explorer.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.NoteAdd
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DashboardCustomize
import androidx.compose.material.icons.rounded.East
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.West
import androidx.compose.material.icons.rounded.LayersClear
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FlexibleBottomAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.cluvex.strata.R
import dev.cluvex.strata.core.model.BrowseAction
import dev.cluvex.strata.core.model.BrowseContext
import dev.cluvex.strata.core.model.SelectionAction
import dev.cluvex.strata.core.model.SelectionContext
import dev.cluvex.strata.core.model.Zoned
import dev.cluvex.strata.core.model.available
import dev.cluvex.strata.core.util.Formatters
import dev.cluvex.strata.feature.settings.ToolbarTab
import dev.cluvex.strata.ui.expressive.ExpressiveMenu
import dev.cluvex.strata.ui.expressive.MenuEntry
import dev.cluvex.strata.ui.icon

class BottomBarActions(
    val onBack: () -> Unit = {},
    val onForward: () -> Unit = {},
    val onUp: () -> Unit = {},
    val onHome: () -> Unit = {},
    val onSwap: () -> Unit = {},
    val onNewFolder: () -> Unit = {},
    val onNewFile: () -> Unit = {},
    val onRefresh: () -> Unit = {},
    val onPaste: () -> Unit = {},
    val onCancelClipboard: () -> Unit = {},
    val onShowClipboard: () -> Unit = {},
    val onSelectAll: () -> Unit = {},
    val onClearSelection: () -> Unit = {},
    val onCopyToOther: () -> Unit = {},
    val onMoveToOther: () -> Unit = {},
    val onCopyToClipboard: () -> Unit = {},
    val onCutToClipboard: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onRename: () -> Unit = {},
    val onCompress: () -> Unit = {},
    val onShare: () -> Unit = {},
    val onProperties: () -> Unit = {},
    val onCopyPath: () -> Unit = {},
    val onInvert: () -> Unit = {},
    val onBookmarks: () -> Unit = {},
    val onBookmarkFolder: () -> Unit = {},
    val undoEnabled: Boolean = true,
    val undoCount: Int = 0,
    val onUndo: () -> Unit = {},
    val onBookmarkSelection: () -> Unit = {},
    val onFolderBadge: () -> Unit = {},
    val onProtect: () -> Unit = {},
    val onProtectedFolders: () -> Unit = {},
    val onBatchRename: () -> Unit = {},
    val onCreateLinks: () -> Unit = {},
    val onMerge: () -> Unit = {},
    val onCompare: () -> Unit = {},
    val onNewProject: () -> Unit = {},
    val onOpenTerminal: () -> Unit = {},
    val onRunProject: () -> Unit = {},
    val onSmaliToDex: () -> Unit = {},
    val onJarToDex: () -> Unit = {},
    val onFilter: () -> Unit = {},
    val onSearch: () -> Unit = {},
    val onSort: () -> Unit = {},
    val onJump: () -> Unit = {},
    val onToggleHidden: () -> Unit = {},
    val onCustomize: (ToolbarTab) -> Unit = {},
    val onMoveToVault: () -> Unit = {},
    val onRestore: () -> Unit = {},
    val onOpenWith: () -> Unit = {},
    val onExtract: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExplorerBottomBar(
    selectionCount: Int,
    canBack: Boolean,
    canForward: Boolean,
    canUp: Boolean,
    activeIsLeft: Boolean,
    dual: Boolean,
    clipboardCount: Int,
    clipboardIsMove: Boolean,
    actions: BottomBarActions,
    selectedFiles: Int = 0,
    remote: Boolean = false,
    projectLabel: String? = null,
    smaliFolder: Boolean = false,
    jarFile: Boolean = false,
    archiveFile: Boolean = false,
    protectedFolder: Boolean = false,
    vault: Boolean = false,
    browse: Zoned<BrowseAction> = dev.cluvex.strata.core.model.ActionLayout.DEFAULT_BROWSE,
    selection: Zoned<SelectionAction> = dev.cluvex.strata.core.model.ActionLayout.DEFAULT_SELECTION,
    showHidden: Boolean = false,
    hiddenSelection: Set<SelectionAction> = emptySet(),
) {
    val motion = MaterialTheme.motionScheme
    val threshold = with(LocalDensity.current) { 48.dp.toPx() }
    val current by rememberUpdatedState(actions)
    FlexibleBottomAppBar(
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.testTag("bottom-bar").pointerInput(Unit) {
            var dragged = 0f
            detectVerticalDragGestures(
                onDragStart = { dragged = 0f },
                onVerticalDrag = { change, dy ->
                    dragged += dy
                    change.consume()
                },
                onDragEnd = { if (dragged < -threshold) current.onBookmarks() },
            )
        },
    ) {
        AnimatedContent(
            targetState = selectionCount > 0,
            transitionSpec = {
                (slideInVertically(motion.defaultSpatialSpec()) { it / 2 } + fadeIn(motion.defaultEffectsSpec()))
                    .togetherWith(slideOutVertically(motion.defaultSpatialSpec()) { -it / 2 } + fadeOut(motion.fastEffectsSpec()))
            },
            label = "bottomBarMode",
            modifier = Modifier.fillMaxWidth(),
        ) { selecting ->
            if (selecting) {
                val context = SelectionContext(selectionCount, selectedFiles, dual, remote, smaliFolder, jarFile, vault, archiveFile, protectedFolder)
                SelectionActions(selectionCount, context, activeIsLeft, selection, actions, hiddenSelection)
            } else {
                val context = BrowseContext(clipboard = clipboardCount > 0, termux = !remote, project = projectLabel != null, undo = actions.undoEnabled, dual = dual)
                BrowseActions(canBack, canForward, canUp, clipboardCount, clipboardIsMove, context, projectLabel, browse, showHidden, actions)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BarButton(icon: ImageVector, description: String, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, shapes = IconButtonDefaults.shapes()) {
        Icon(icon, contentDescription = description)
    }
}

@Composable
private fun BrowseAction.text(clipboardIsMove: Boolean, showHidden: Boolean, projectLabel: String?, undoCount: Int = 0): String = when (this) {
    BrowseAction.PASTE -> stringResource(if (clipboardIsMove) R.string.explorer_move_here else R.string.explorer_paste_here)
    BrowseAction.HIDDEN_FILES -> stringResource(if (showHidden) R.string.common_hide_hidden else R.string.common_show_hidden)
    BrowseAction.RUN_PROJECT -> if (projectLabel != null) stringResource(R.string.explorer_run_project_kind, projectLabel) else stringResource(label)
    BrowseAction.JUMP -> stringResource(R.string.explorer_jump_to_path_more)
    BrowseAction.UNDO -> if (undoCount > 0) stringResource(R.string.explorer_undo_count, undoCount) else stringResource(label)
    else -> stringResource(label)
}

private fun BrowseAction.run(a: BottomBarActions) = when (this) {
    BrowseAction.BACK -> a.onBack()
    BrowseAction.FORWARD -> a.onForward()
    BrowseAction.NEW -> a.onNewFolder()
    BrowseAction.PASTE -> a.onPaste()
    BrowseAction.SWAP -> a.onSwap()
    BrowseAction.UP -> a.onUp()
    BrowseAction.UNDO -> a.onUndo()
    BrowseAction.HOME -> a.onHome()
    BrowseAction.REFRESH -> a.onRefresh()
    BrowseAction.BOOKMARKS -> a.onBookmarks()
    BrowseAction.BOOKMARK_FOLDER -> a.onBookmarkFolder()
    BrowseAction.PROTECTED_FOLDERS -> a.onProtectedFolders()
    BrowseAction.SELECT_ALL -> a.onSelectAll()
    BrowseAction.RUN_PROJECT -> a.onRunProject()
    BrowseAction.TERMINAL -> a.onOpenTerminal()
    BrowseAction.FILTER -> a.onFilter()
    BrowseAction.SEARCH -> a.onSearch()
    BrowseAction.SORT -> a.onSort()
    BrowseAction.JUMP -> a.onJump()
    BrowseAction.HIDDEN_FILES -> a.onToggleHidden()
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
@Composable
private fun BrowseActions(
    canBack: Boolean,
    canForward: Boolean,
    canUp: Boolean,
    clipboardCount: Int,
    clipboardIsMove: Boolean,
    context: BrowseContext,
    projectLabel: String?,
    browse: Zoned<BrowseAction>,
    showHidden: Boolean,
    a: BottomBarActions,
) {
    val motion = MaterialTheme.motionScheme
    var newMenu by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    fun enabled(action: BrowseAction) = when (action) {
        BrowseAction.BACK -> canBack
        BrowseAction.FORWARD -> canForward
        BrowseAction.UP -> canUp
        BrowseAction.UNDO -> a.undoCount > 0
        else -> true
    }
    val newEntries = buildList {
        add(MenuEntry(stringResource(R.string.common_new_folder), Icons.Rounded.CreateNewFolder) { newMenu = false; more = false; a.onNewFolder() })
        add(MenuEntry(stringResource(R.string.common_new_file), Icons.AutoMirrored.Rounded.NoteAdd) { newMenu = false; more = false; a.onNewFile() })
        if (context.termux) add(MenuEntry(stringResource(R.string.explorer_new_project), Icons.Rounded.Code) { newMenu = false; more = false; a.onNewProject() })
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        for (action in browse.zones[0]) {
            when (action) {
                BrowseAction.PASTE -> AnimatedVisibility(
                    visible = clipboardCount > 0,
                    enter = scaleIn(motion.fastSpatialSpec()) + fadeIn(motion.fastEffectsSpec()),
                    exit = scaleOut(motion.fastSpatialSpec()) + fadeOut(motion.fastEffectsSpec()),
                ) {
                    // The badge is drawn beside the button, not inside it: IconButton clips its
                    // content to the button shape, which used to cut the badge off.
                    Box {
                        val haptic = LocalHapticFeedback.current
                        Box(
                            Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .combinedClickable(
                                    role = Role.Button,
                                    onClick = a.onShowClipboard,
                                    onLongClick = {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        a.onPaste()
                                    },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(action.icon, contentDescription = action.text(clipboardIsMove, showHidden, projectLabel))
                        }
                        Badge(modifier = Modifier.align(Alignment.TopEnd).padding(top = 2.dp, end = 0.dp)) {
                            Text(Formatters.number(clipboardCount.toLong()))
                        }
                    }
                }
                BrowseAction.NEW -> Box {
                    BarButton(action.icon, stringResource(action.label)) { newMenu = true }
                    ExpressiveMenu(expanded = newMenu, onDismiss = { newMenu = false }, groups = listOf(newEntries))
                }
                else -> if (action.available(context)) {
                    BarButton(
                        icon = if (action == BrowseAction.HIDDEN_FILES && showHidden) Icons.Rounded.VisibilityOff else action.icon,
                        description = action.text(clipboardIsMove, showHidden, projectLabel, a.undoCount),
                        enabled = enabled(action),
                    ) { action.run(a) }
                }
            }
        }
        Box {
            BarButton(Icons.Rounded.MoreVert, stringResource(R.string.common_more)) { more = true }
            val entries = buildList {
                for (action in browse.zones[1]) {
                    if (!action.available(context)) continue
                    if (action == BrowseAction.NEW) {
                        addAll(newEntries)
                    } else {
                        add(
                            MenuEntry(
                                text = action.text(clipboardIsMove, showHidden, projectLabel, a.undoCount),
                                icon = if (action == BrowseAction.HIDDEN_FILES && showHidden) Icons.Rounded.VisibilityOff else action.icon,
                                enabled = enabled(action),
                            ) { more = false; action.run(a) },
                        )
                    }
                }
            }
            val tools = buildList {
                if (clipboardCount > 0) {
                    add(MenuEntry(stringResource(R.string.explorer_clipboard_queue), Icons.Rounded.ContentPaste) { more = false; a.onShowClipboard() })
                    add(MenuEntry(stringResource(R.string.explorer_clear_clipboard), Icons.Rounded.LayersClear) { more = false; a.onCancelClipboard() })
                }
                add(MenuEntry(stringResource(R.string.explorer_customize_bar), Icons.Rounded.DashboardCustomize) { more = false; a.onCustomize(ToolbarTab.BROWSE) })
            }
            ExpressiveMenu(expanded = more, onDismiss = { more = false }, groups = listOf(entries, tools))
        }
    }
}

@Composable
private fun SelectionAction.text(dual: Boolean, activeIsLeft: Boolean, unprotect: Boolean = false): String = when (this) {
    SelectionAction.PROTECT -> stringResource(if (unprotect) R.string.model_unprotect_folder else label)
    SelectionAction.COPY -> stringResource(
        when {
            !dual -> R.string.common_copy
            activeIsLeft -> R.string.explorer_copy_to_right_pane
            else -> R.string.explorer_copy_to_left_pane
        },
    )
    SelectionAction.MOVE -> stringResource(
        when {
            !dual -> R.string.explorer_cut
            activeIsLeft -> R.string.explorer_move_to_right_pane
            else -> R.string.explorer_move_to_left_pane
        },
    )
    else -> if (asks) stringResource(R.string.explorer_action_asks, stringResource(label)) else stringResource(label)
}

private fun SelectionAction.iconFor(c: SelectionContext): ImageVector =
    if (this == SelectionAction.PROTECT && c.protectedFolder) Icons.Rounded.LockOpen else icon

private fun SelectionAction.run(a: BottomBarActions, dual: Boolean) = when (this) {
    SelectionAction.OPEN_WITH -> a.onOpenWith()
    SelectionAction.EXTRACT -> a.onExtract()
    SelectionAction.COPY -> if (dual) a.onCopyToOther() else a.onCopyToClipboard()
    SelectionAction.MOVE -> if (dual) a.onMoveToOther() else a.onCutToClipboard()
    SelectionAction.DELETE -> a.onDelete()
    SelectionAction.RENAME -> a.onRename()
    SelectionAction.BATCH_RENAME -> a.onBatchRename()
    SelectionAction.COPY_CLIPBOARD -> a.onCopyToClipboard()
    SelectionAction.CUT_CLIPBOARD -> a.onCutToClipboard()
    SelectionAction.COMPRESS -> a.onCompress()
    SelectionAction.MERGE -> a.onMerge()
    SelectionAction.COMPARE -> a.onCompare()
    SelectionAction.SMALI_TO_DEX -> a.onSmaliToDex()
    SelectionAction.JAR_TO_DEX -> a.onJarToDex()
    SelectionAction.BOOKMARKS -> a.onBookmarks()
    SelectionAction.BOOKMARK -> a.onBookmarkSelection()
    SelectionAction.FOLDER_BADGE -> a.onFolderBadge()
    SelectionAction.PROTECT -> a.onProtect()
    SelectionAction.PROTECTED_FOLDERS -> a.onProtectedFolders()
    SelectionAction.LINKS -> a.onCreateLinks()
    SelectionAction.VAULT -> a.onMoveToVault()
    SelectionAction.RESTORE -> a.onRestore()
    SelectionAction.SHARE -> a.onShare()
    SelectionAction.COPY_PATH -> a.onCopyPath()
    SelectionAction.PROPERTIES -> a.onProperties()
    SelectionAction.SELECT_ALL -> a.onSelectAll()
    SelectionAction.INVERT -> a.onInvert()
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SelectionActions(
    count: Int,
    context: SelectionContext,
    activeIsLeft: Boolean,
    selection: Zoned<SelectionAction>,
    a: BottomBarActions,
    hidden: Set<SelectionAction> = emptySet(),
) {
    var more by remember { mutableStateOf(false) }
    val dual = context.dual
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        BarButton(Icons.Rounded.Close, stringResource(R.string.common_clear_selection), onClick = a.onClearSelection)
        Text(Formatters.number(count.toLong()), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 2.dp))
        for (action in selection.zones[0]) {
            if (!action.available(context) || action in hidden) continue
            if (dual && (action == SelectionAction.COPY || action == SelectionAction.MOVE)) {
                DirectionalAction(action.icon, toRight = activeIsLeft, description = action.text(true, activeIsLeft)) { action.run(a, true) }
            } else {
                BarButton(action.iconFor(context), action.text(dual, activeIsLeft, context.protectedFolder)) { action.run(a, dual) }
            }
        }
        Box {
            BarButton(Icons.Rounded.MoreVert, stringResource(R.string.explorer_more_actions)) { more = true }
            val entries = selection.zones[1].filter { it.available(context) && it !in hidden }.map { action ->
                MenuEntry(action.text(dual, activeIsLeft, context.protectedFolder), action.iconFor(context)) { more = false; action.run(a, dual) }
            }
            val tools = listOf(MenuEntry(stringResource(R.string.explorer_customize_bar), Icons.Rounded.DashboardCustomize) { more = false; a.onCustomize(ToolbarTab.SELECTION) })
            ExpressiveMenu(expanded = more, onDismiss = { more = false }, groups = listOf(entries, tools))
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DirectionalAction(icon: ImageVector, toRight: Boolean, description: String, onClick: () -> Unit) {
    val rightOnScreen = toRight != (LocalLayoutDirection.current == LayoutDirection.Rtl)
    IconButton(onClick = onClick, shapes = IconButtonDefaults.shapes()) {
        Box {
            Icon(icon, contentDescription = description)
            Icon(
                if (rightOnScreen) Icons.Rounded.East else Icons.Rounded.West,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(14.dp)
                    .align(Alignment.BottomEnd)
                    .offset(x = 6.dp, y = 4.dp),
            )
        }
    }
}
