package dev.cluvex.strata.feature.shell

import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.AssistChip
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.CreateNewFolder
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay
import androidx.compose.runtime.mutableIntStateOf
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.cluvex.strata.R
import dev.cluvex.strata.core.model.MotionStyle
import dev.cluvex.strata.core.shell.SavedCommand
import dev.cluvex.strata.core.shell.ShellBackend
import dev.cluvex.strata.core.shell.ShellMode
import dev.cluvex.strata.core.shell.ShellRunner
import dev.cluvex.strata.ui.expressive.ExpressiveDialog
import dev.cluvex.strata.ui.expressive.ExpressiveEmptyState
import dev.cluvex.strata.ui.expressive.MorphShape
import dev.cluvex.strata.ui.expressive.rememberPolygonMorph
import dev.cluvex.strata.ui.rememberTextClipboard
import dev.cluvex.strata.ui.theme.LocalAppearance

private val TIMEOUT_CHOICES = listOf(10, 30, 60, 300, 600)

@Composable
private fun timeoutLabel(sec: Int): String =
    if (sec <= ShellRunner.NO_TIMEOUT) stringResource(R.string.shell_timeout_none)
    else if (sec < 60) stringResource(R.string.shell_timeout_seconds, sec) else stringResource(R.string.shell_timeout_minutes, sec / 60)

/** [command] null means "new command". */
private class EditTarget(val command: SavedCommand?)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ShellCommandsScreen(onBack: () -> Unit, vm: ShellCommandsViewModel = viewModel()) {
    val ui by vm.state.collectAsState()
    var editing by remember { mutableStateOf<EditTarget?>(null) }
    var showLog by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf<SavedCommand?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var newGroupDialog by remember { mutableStateOf(false) }
    var groupMenu by remember { mutableStateOf<String?>(null) }
    var liveId by remember { mutableStateOf<String?>(null) }
    // Swiping a row to the right asks before deleting it; swiping to the left opens its editor.
    var deleteCmd by remember { mutableStateOf<SavedCommand?>(null) }
    var deleteGroup by remember { mutableStateOf<String?>(null) }
    // A background command that is still running, whose log was opened again from its card.
    var backgroundCmd by remember { mutableStateOf<SavedCommand?>(null) }
    // A normal command opens its live output right away; a background one shows its log when it has started.
    val startCommand: (SavedCommand) -> Unit = { c ->
        vm.run(c)
        if (!c.detached) liveId = c.id
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) vm.exportTo(uri) { n ->
            val text = if (n < 0) context.getString(R.string.shell_import_failed) else context.getString(R.string.shell_exported, n)
            android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) vm.importFrom(uri) { n ->
            val text = if (n < 0) context.getString(R.string.shell_import_failed) else context.getString(R.string.shell_imported, n)
            android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(Unit) { vm.reload() }
    val listState = rememberLazyListState()
    // Display rows: each group (header + its commands unless folded), then the commands that are in no group.
    val rows = remember(ui.commands, ui.groups, ui.collapsed) {
        buildList<ShellRow> {
            for (g in ui.groups) {
                val items = ui.commands.filter { it.group == g }
                val folded = g in ui.collapsed
                add(ShellRow.Group(g, items.size, folded))
                if (!folded) items.forEach { add(ShellRow.Cmd(it, nested = true)) }
            }
            val loose = ui.commands.filter { it.group.isEmpty() }
            if (ui.groups.isNotEmpty()) add(ShellRow.Label)
            loose.forEach { add(ShellRow.Cmd(it, nested = false)) }
        }
    }
    // The drag handlers live inside pointerInput blocks that are keyed only by the command id, so they keep the
    // lambdas (and the `rows` list) from the composition in which they were created. Without this, after a group is
    // folded/unfolded or a command moves, list indexes map to the wrong rows and the wrong (or no) group is targeted.
    val rowsNow by rememberUpdatedState(rows)
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    // The group the dragged card is currently over ("" = ungrouped); the card only joins it when it is dropped.
    var hoverGroup by remember { mutableStateOf<String?>(null) }
    var pendingIndex by remember { mutableStateOf<Int?>(null) }
    if (showLog) {
        ShellLogScreen(onBack = { showLog = false })
        return
    }
    BackHandler(onBack = onBack)
    val density = LocalDensity.current

    fun startDrag(id: String) {
        dragId = id
        dragOffset = 0f
        pendingIndex = null
        hoverGroup = null
    }

    // What the dragged card is over. A group header (or the "Ungrouped" label) is small next to a card, so overlapping
    // it clearly is enough to target it; otherwise the row under the card's center is the target.
    fun findTarget(cur: LazyListItemInfo, dragged: SavedCommand): Pair<LazyListItemInfo, ShellRow>? {
        val items = listState.layoutInfo.visibleItemsInfo
        val top = cur.offset + dragOffset
        val bottom = top + cur.size
        val byOverlap = items.mapNotNull { info ->
            if (info.key == dragged.id) return@mapNotNull null
            val row = rowsNow.getOrNull(info.index) ?: return@mapNotNull null
            val targetable = (row is ShellRow.Group && row.name != dragged.group) || (row is ShellRow.Label && dragged.group.isNotEmpty())
            if (!targetable) return@mapNotNull null
            val overlap = minOf(bottom, (info.offset + info.size).toFloat()) - maxOf(top, info.offset.toFloat())
            if (overlap >= info.size * 0.3f) Triple(info, row, overlap) else null
        }.maxByOrNull { it.third }
        if (byOverlap != null) return byOverlap.first to byOverlap.second
        val center = top + cur.size / 2f
        val hit = items.firstOrNull { it.key != dragged.id && center >= it.offset && center <= it.offset + it.size } ?: return null
        return rowsNow.getOrNull(hit.index)?.let { hit to it }
    }

    // Called when the finger is released: if the card sits over another group, it moves there now.
    fun dropInto(id: String) {
        val cur = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id } ?: return
        val dragged = ui.commands.firstOrNull { it.id == id } ?: return
        val (hit, row) = findTarget(cur, dragged) ?: return
        when (row) {
            is ShellRow.Group -> if (row.name != dragged.group) {
                vm.moveToGroup(id, row.name, atEnd = false)
                // A folded group opens so the dropped command stays visible.
                if (row.collapsed) vm.expandGroup(row.name)
            }
            is ShellRow.Label -> if (dragged.group.isNotEmpty()) vm.moveToGroup(id, "", atEnd = false)
            is ShellRow.Cmd -> if (row.command.group != dragged.group) {
                val center = cur.offset + dragOffset + cur.size / 2f
                vm.moveNextTo(id, row.command.id, after = center > hit.offset + hit.size / 2f)
            }
        }
    }

    fun endDrag() {
        val dropped = dragId
        if (dropped != null) {
            dropInto(dropped)
            hoverGroup = null
            dragId = null
            dragOffset = 0f
            pendingIndex = null
            vm.commitOrder()
        }
    }

    // The dragged card follows the finger through dragOffset; when its center passes the middle of a neighbour the
    // two swap, and dragOffset is corrected so the card stays under the finger.
    fun dragBy(dy: Float) {
        val id = dragId ?: return
        dragOffset += dy
        val info = listState.layoutInfo
        val visible = info.visibleItemsInfo
        val cur = visible.firstOrNull { it.key == id } ?: return
        val waiting = pendingIndex
        if (waiting != null) {
            if (cur.index != waiting) return
            pendingIndex = null
        }
        val center = cur.offset + dragOffset + cur.size / 2f
        val dragged = ui.commands.firstOrNull { it.id == id } ?: return
        // Live reordering only happens inside the card's own group; joining another group is decided on release.
        fun sameGroupCmd(index: Int) = (rowsNow.getOrNull(index) as? ShellRow.Cmd)?.command?.group == dragged.group
        hoverGroup = when (val r = findTarget(cur, dragged)?.second) {
            is ShellRow.Group -> r.name
            is ShellRow.Cmd -> r.command.group
            is ShellRow.Label -> ""
            null -> null
        }?.takeIf { it != dragged.group }
        val next = visible.firstOrNull { it.index == cur.index + 1 && sameGroupCmd(it.index) }
        val prev = visible.firstOrNull { it.index == cur.index - 1 && sameGroupCmd(it.index) }
        val target = when {
            next != null && center > next.offset + next.size / 2f -> next
            prev != null && center < prev.offset + prev.size / 2f -> prev
            else -> null
        } ?: return
        val down = target.index > cur.index
        vm.swapCommands(id, (rowsNow[target.index] as ShellRow.Cmd).command.id)
        val newOffset = if (down) target.offset + target.size - cur.size else target.offset
        dragOffset += cur.offset - newOffset
        pendingIndex = target.index
    }

    // Auto-scroll while a card is held near the top or bottom edge. It runs every frame, so the list keeps moving
    // even when the finger is still; after each step dragBy(0f) refreshes the highlighted group and live swaps.
    LaunchedEffect(dragId) {
        val id = dragId ?: return@LaunchedEffect
        val edge = with(density) { 56.dp.toPx() }
        val speed = with(density) { 6.dp.toPx() }
        while (true) {
            withFrameNanos { }
            if (dragId != id) break
            val info = listState.layoutInfo
            val cur = info.visibleItemsInfo.firstOrNull { it.key == id } ?: continue
            val top = cur.offset + dragOffset
            val bottom = top + cur.size
            val delta = when {
                top < info.viewportStartOffset + edge -> -speed
                bottom > info.viewportEndOffset - edge -> speed
                else -> 0f
            }
            if (delta != 0f) {
                val consumed = listState.dispatchRawDelta(delta)
                if (consumed != 0f) {
                    dragOffset += consumed
                    dragBy(0f)
                }
            }
        }
    }

    Scaffold(
        modifier = Modifier.testTag("shell-commands-screen"),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.shell_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    IconButton(onClick = { newGroupDialog = true }) {
                        Icon(Icons.Rounded.CreateNewFolder, contentDescription = stringResource(R.string.shell_group_new))
                    }
                    IconButton(onClick = { showLog = true }) {
                        Icon(Icons.Rounded.History, contentDescription = stringResource(R.string.shell_log_title))
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.common_more))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.shell_export)) },
                                enabled = ui.commands.isNotEmpty(),
                                onClick = { menuOpen = false; exportLauncher.launch("strata-shell-commands.json") },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.shell_import)) },
                                onClick = { menuOpen = false; importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text(stringResource(R.string.shell_add)) },
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                onClick = { editing = EditTarget(null) },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                ui.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
                ui.commands.isEmpty() && ui.groups.isEmpty() -> ExpressiveEmptyState(
                    title = stringResource(R.string.shell_empty),
                    body = stringResource(R.string.shell_empty_body),
                    icon = Icons.Rounded.Terminal,
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                )
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(rows, key = { it.key }) { row ->
                        when (row) {
                            is ShellRow.Group -> SwipeActionRow(
                                enabled = dragId == null,
                                shape = RoundedCornerShape(16.dp),
                                onSwipeRight = { deleteGroup = row.name },
                                onSwipeLeft = { groupMenu = row.name },
                                modifier = Modifier.animateItem(),
                            ) {
                                GroupHeader(
                                    name = row.name,
                                    count = row.count,
                                    collapsed = row.collapsed,
                                    highlight = hoverGroup == row.name,
                                    onToggle = { vm.toggleCollapsed(row.name) },
                                    onMenu = { groupMenu = row.name },
                                )
                            }
                            is ShellRow.Label -> Text(
                                stringResource(R.string.shell_group_ungrouped),
                                style = MaterialTheme.typography.labelLarge,
                                color = if (hoverGroup == "") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 6.dp, top = 8.dp).animateItem(),
                            )
                            is ShellRow.Cmd -> {
                                val c = row.command
                                val lifted = dragId == c.id
                                SwipeActionRow(
                                    enabled = dragId == null,
                                    shape = RoundedCornerShape(14.dp),
                                    onSwipeRight = { deleteCmd = c },
                                    onSwipeLeft = { editing = EditTarget(c) },
                                    // The lifted card has to draw above the rows after it, so the row carries the z-index.
                                    modifier = (if (lifted) Modifier else Modifier.animateItem()).zIndex(if (lifted) 1f else 0f),
                                ) {
                                    CommandCard(
                                        command = c,
                                        running = c.id in ui.runningIds || c.id in ui.backgroundIds,
                                        blocked = false,
                                        lifted = lifted,
                                        dragOffset = { dragOffset },
                                        // While a command runs, its button opens its output (or log) instead of starting it again.
                                        onRun = {
                                            when {
                                                c.id in ui.runningIds -> if (!c.detached) liveId = c.id
                                                c.id in ui.backgroundIds -> backgroundCmd = c
                                                c.confirm -> confirming = c
                                                else -> startCommand(c)
                                            }
                                        },
                                        onEdit = { editing = EditTarget(c) },
                                        handle = Modifier.pointerInput(c.id) {
                                            detectDragGestures(
                                                onDragStart = { startDrag(c.id) },
                                                onDrag = { change, amount ->
                                                    change.consume()
                                                    dragBy(amount.y)
                                                },
                                                onDragEnd = { endDrag() },
                                                onDragCancel = { endDrag() },
                                            )
                                        },
                                        modifier = Modifier
                                            .pointerInput(c.id) {
                                                detectDragGesturesAfterLongPress(
                                                    onDragStart = { startDrag(c.id) },
                                                    onDrag = { change, amount ->
                                                        change.consume()
                                                        dragBy(amount.y)
                                                    },
                                                    onDragEnd = { endDrag() },
                                                    onDragCancel = { endDrag() },
                                                )
                                            },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    editing?.let { target ->
        EditCommandDialog(
            existing = target.command,
            groups = ui.groups,
            onNewGroup = vm::addGroup,
            onSave = { name, command, shortcut, mode, timeoutSec, detached, confirm, showStatus, group ->
                vm.save(target.command?.id, name, command, shortcut, mode, timeoutSec, detached, confirm, showStatus, group)
                editing = null
            },
            onDelete = target.command?.let { c -> { vm.delete(c.id); editing = null } },
            onDuplicate = target.command?.let { c ->
                { vm.duplicate(c.id) { name -> context.getString(R.string.shell_copy_name, name) }; editing = null }
            },
            onPin = target.command?.let { c ->
                {
                    val ok = dev.cluvex.strata.core.shell.ShellShortcuts.pin(context, c)
                    if (!ok) android.widget.Toast.makeText(context, R.string.shell_pin_failed, android.widget.Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { editing = null },
        )
    }

    deleteCmd?.let { c ->
        ExpressiveDialog(
            onDismissRequest = { deleteCmd = null },
            title = stringResource(R.string.shell_delete_confirm_title, c.name),
            icon = Icons.Rounded.Delete,
            confirmButton = {
                TextButton(onClick = { deleteCmd = null; vm.delete(c.id) }) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteCmd = null }) { Text(stringResource(R.string.common_cancel)) } },
        ) {
            Text(
                c.command,
                maxLines = 6,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    textDirection = TextDirection.Ltr,
                ),
            )
        }
    }

    deleteGroup?.let { g ->
        ExpressiveDialog(
            onDismissRequest = { deleteGroup = null },
            title = stringResource(R.string.shell_delete_confirm_title, g),
            icon = Icons.Rounded.Delete,
            confirmButton = {
                TextButton(onClick = { deleteGroup = null; vm.deleteGroup(g) }) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleteGroup = null }) { Text(stringResource(R.string.common_cancel)) } },
        ) {
            Text(stringResource(R.string.shell_group_delete_confirm_text))
        }
    }

    confirming?.let { c ->
        ExpressiveDialog(
            onDismissRequest = { confirming = null },
            title = stringResource(R.string.shell_confirm_title, c.name),
            icon = Icons.Rounded.Terminal,
            confirmButton = { TextButton(onClick = { confirming = null; startCommand(c) }) { Text(stringResource(R.string.shell_run)) } },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text(stringResource(R.string.common_cancel)) } },
        ) {
            Text(
                c.command,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    textDirection = TextDirection.Ltr,
                ),
            )
        }
    }

    if (newGroupDialog) {
        GroupNameDialog(
            title = stringResource(R.string.shell_group_new),
            initial = "",
            confirmText = stringResource(R.string.common_create),
            onDismiss = { newGroupDialog = false },
            onConfirm = { vm.addGroup(it); newGroupDialog = false },
        )
    }
    groupMenu?.let { g ->
        val position = ui.groups.indexOf(g)
        GroupNameDialog(
            title = stringResource(R.string.shell_group_edit),
            initial = g,
            confirmText = stringResource(R.string.common_save),
            onDismiss = { groupMenu = null },
            onConfirm = { vm.renameGroup(g, it); groupMenu = null },
            onDelete = { vm.deleteGroup(g); groupMenu = null },
            onMove = { by -> vm.moveGroup(g, by) },
            canMoveUp = position > 0,
            canMoveDown = position >= 0 && position < ui.groups.lastIndex,
        )
    }

    // The live view closes by itself when its command finishes (the result dialog follows). It only counts as
    // "was open" once the command shows as running, so the moment before the state catches up does not close it.
    val liveActive = liveId?.let { it in ui.runningIds } == true
    var liveWasActive by remember { mutableStateOf(false) }
    LaunchedEffect(liveActive) {
        if (liveActive) liveWasActive = true
        else if (liveWasActive) {
            liveId = null
            liveWasActive = false
        }
    }
    if (liveActive) liveId?.let { id -> LiveOutputDialog(id, onDismiss = { liveId = null }) }

    backgroundCmd?.let { c ->
        RunResultDialog(vm.backgroundRun(c), onDismiss = { backgroundCmd = null })
    }

    // Results queue up when several commands finish; each is shown in turn.
    ui.run?.let { run -> key(run) { RunResultDialog(run, onDismiss = vm::dismissRun) } }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CommandCard(
    command: SavedCommand,
    running: Boolean,
    blocked: Boolean,
    lifted: Boolean,
    dragOffset: () -> Float,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    handle: Modifier,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val reduced = LocalAppearance.current.motion == MotionStyle.REDUCED
    val lift by animateFloatAsState(
        targetValue = if (lifted) 1f else 0f,
        animationSpec = if (reduced) snap() else spring(dampingRatio = 0.55f, stiffness = 420f),
        label = "lift",
    )
    val morph = rememberPolygonMorph(MaterialShapes.Cookie9Sided, MaterialShapes.Cookie9Sided)
    val morphProgress by animateFloatAsState(
        targetValue = if (lifted) 1f else 0f,
        animationSpec = if (reduced) snap() else spring(dampingRatio = 0.6f, stiffness = 260f),
        label = "morph",
    )
    val spin = if (lifted && !reduced) {
        val transition = rememberInfiniteTransition(label = "spin")
        transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(5200, easing = LinearEasing)),
            label = "spin",
        ).value
    } else {
        0f
    }
    Surface(
        shape = RoundedCornerShape((14 + 18 * lift).dp),
        color = lerp(lerp(colors.surfaceBright, colors.primaryContainer, 0.3f), colors.primaryContainer, lift * 0.85f),
        contentColor = colors.onSurface,
        shadowElevation = (10 * lift).dp,
        modifier = modifier
            .fillMaxWidth()
            .zIndex(if (lifted) 1f else 0f)
            .graphicsLayer {
                translationY = if (lifted) dragOffset() else 0f
                val scale = 1f + 0.035f * lift
                scaleX = scale
                scaleY = scale
                rotationZ = -1.2f * lift
            },
    ) {
        Column(
            Modifier
                .animateContentSize(if (reduced) snap() else spring(stiffness = 600f))
                .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = if (lifted) 8.dp else 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(40.dp)
                        .graphicsLayer { rotationZ = spin }
                        .clip(MorphShape(morph, morphProgress))
                        .background(lerp(colors.primaryContainer, colors.primary, lift)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Terminal,
                        contentDescription = null,
                        tint = lerp(colors.onPrimaryContainer, colors.onPrimary, lift),
                        modifier = Modifier.size(22.dp).graphicsLayer { rotationZ = -spin },
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        command.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = if (lifted) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (command.shortcut && !lifted) {
                        Text(
                            stringResource(R.string.shell_in_menu),
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.primary,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                IconButton(onClick = onRun, enabled = !blocked && !lifted, modifier = Modifier.size(48.dp)) {
                    if (running) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                    } else {
                        Icon(
                            Icons.Rounded.PlayArrow,
                            contentDescription = stringResource(R.string.shell_run),
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.common_edit))
                }
                Box(handle.size(36.dp), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Rounded.DragIndicator,
                        contentDescription = stringResource(R.string.shell_drag, command.name),
                        tint = colors.onSurfaceVariant,
                    )
                }
            }
            if (!lifted) Text(
                command.command,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = colors.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    textDirection = TextDirection.Ltr,
                ),
                modifier = Modifier.padding(start = 4.dp, end = 8.dp, top = 6.dp),
            )
        }
    }
}

@Composable
private fun EditCommandDialog(
    existing: SavedCommand?,
    groups: List<String>,
    onNewGroup: (String) -> Unit,
    onSave: (name: String, command: String, shortcut: Boolean, mode: ShellMode, timeoutSec: Int, detached: Boolean, confirm: Boolean, showStatus: Boolean, group: String) -> Unit,
    onDelete: (() -> Unit)?,
    onDuplicate: (() -> Unit)?,
    onPin: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var command by remember { mutableStateOf(existing?.command.orEmpty()) }
    var shortcut by remember { mutableStateOf(existing?.shortcut ?: false) }
    var mode by remember { mutableStateOf(existing?.mode ?: ShellMode.AUTO) }
    var timeoutSec by remember { mutableStateOf(existing?.timeoutSec ?: ShellRunner.DEFAULT_TIMEOUT_SEC) }
    var detached by remember { mutableStateOf(existing?.detached ?: false) }
    var confirm by remember { mutableStateOf(existing?.confirm ?: false) }
    var showStatus by remember { mutableStateOf(existing?.showStatus ?: true) }
    var group by remember { mutableStateOf(existing?.group.orEmpty()) }
    var newGroupDialog by remember { mutableStateOf(false) }
    if (newGroupDialog) {
        GroupNameDialog(
            title = stringResource(R.string.shell_group_new),
            initial = "",
            confirmText = stringResource(R.string.common_create),
            onDismiss = { newGroupDialog = false },
            onConfirm = { onNewGroup(it); group = it.trim(); newGroupDialog = false },
        )
    }
    ExpressiveDialog(
        onDismissRequest = onDismiss,
        title = stringResource(if (existing == null) R.string.shell_add else R.string.shell_edit),
        icon = Icons.Rounded.Terminal,
        confirmButton = {
            TextButton(onClick = { onSave(name, command, shortcut, mode, timeoutSec, detached, confirm, showStatus, group) }, enabled = command.isNotBlank()) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            if (onDelete != null) {
                TextButton(onClick = onDelete) { Text(stringResource(R.string.common_delete)) }
            }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    ) {
        Column(
            modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.common_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                label = { Text(stringResource(R.string.shell_command)) },
                minLines = 3,
                maxLines = 8,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    textDirection = TextDirection.Ltr,
                ),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.shell_group_title), style = MaterialTheme.typography.labelLarge)
            // One horizontally scrollable row: "No group" and "+ New group" stay first, then the groups.
            val chipsState = rememberLazyListState()
            LaunchedEffect(group, groups.size) {
                val at = if (group.isEmpty()) 0 else groups.indexOf(group).let { if (it < 0) 0 else it + 2 }
                chipsState.animateScrollToItem(at)
            }
            LazyRow(state = chipsState, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    FilterChip(selected = group.isEmpty(), onClick = { group = "" }, label = { Text(stringResource(R.string.shell_group_none)) })
                }
                item {
                    AssistChip(onClick = { newGroupDialog = true }, label = { Text("+ " + stringResource(R.string.shell_group_new)) })
                }
                items(groups, key = { it }) { g ->
                    FilterChip(selected = group == g, onClick = { group = g }, label = { Text(g) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.shell_shortcut), modifier = Modifier.weight(1f))
                Switch(checked = shortcut, onCheckedChange = { shortcut = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.shell_confirm_switch), modifier = Modifier.weight(1f))
                Switch(checked = confirm, onCheckedChange = { confirm = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.shell_show_status), modifier = Modifier.weight(1f))
                Switch(checked = showStatus, onCheckedChange = { showStatus = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.shell_detached))
                    Text(
                        stringResource(R.string.shell_detached_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = detached, onCheckedChange = { detached = it })
            }
            Text(stringResource(R.string.shell_run_with), style = MaterialTheme.typography.labelLarge)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShellMode.entries.forEach { m ->
                    FilterChip(
                        selected = mode == m,
                        onClick = { mode = m },
                        label = {
                            Text(
                                stringResource(
                                    when (m) {
                                        ShellMode.AUTO -> R.string.shell_mode_auto
                                        ShellMode.SHIZUKU -> R.string.shell_via_shizuku
                                        ShellMode.ROOT -> R.string.shell_via_root
                                    },
                                ),
                            )
                        },
                    )
                }
            }
            Text(stringResource(R.string.shell_timeout), style = MaterialTheme.typography.labelLarge)
            Box {
                var menuOpen by remember { mutableStateOf(false) }
                OutlinedButton(onClick = { menuOpen = true }) {
                    Text(timeoutLabel(timeoutSec))
                    Icon(Icons.Rounded.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    (listOf(ShellRunner.NO_TIMEOUT) + TIMEOUT_CHOICES).forEach { sec ->
                        DropdownMenuItem(
                            text = { Text(timeoutLabel(sec)) },
                            onClick = { timeoutSec = sec; menuOpen = false },
                        )
                    }
                }
            }
            if (onPin != null) {
                OutlinedButton(onClick = onPin) { Text(stringResource(R.string.shell_pin_home)) }
            }
            if (onDuplicate != null) {
                OutlinedButton(onClick = onDuplicate) { Text(stringResource(R.string.shell_duplicate)) }
            }
        }
    }
}

private sealed interface ShellRow {
    val key: String

    data class Group(val name: String, val count: Int, val collapsed: Boolean) : ShellRow {
        override val key: String get() = "g:$name"
    }

    object Label : ShellRow {
        override val key: String = "ungrouped"
    }

    data class Cmd(val command: SavedCommand, val nested: Boolean) : ShellRow {
        override val key: String get() = command.id
    }
}

/** Header of a group: tap to fold or unfold it, long-press to rename or delete it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GroupHeader(
    name: String,
    count: Int,
    collapsed: Boolean,
    onToggle: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (highlight) colors.primaryContainer else colors.surfaceContainerHigh,
        contentColor = if (highlight) colors.onPrimaryContainer else colors.onSurface,
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).combinedClickable(onClick = onToggle, onLongClick = onMenu),
    ) {
        Row(
            Modifier.heightIn(min = 52.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Surface(shape = RoundedCornerShape(10.dp), color = colors.primaryContainer, contentColor = colors.onPrimaryContainer) {
                Text(count.toString(), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 9.dp, vertical = 2.dp))
            }
            Icon(
                if (collapsed) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess,
                contentDescription = stringResource(if (collapsed) R.string.shell_group_expand else R.string.shell_group_collapse),
            )
        }
    }
}

@Composable
private fun GroupNameDialog(
    title: String,
    initial: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    onDelete: (() -> Unit)? = null,
    /** Moves the group one place up (-1) or down (1); null for a group that doesn't exist yet. */
    onMove: ((Int) -> Unit)? = null,
    canMoveUp: Boolean = false,
    canMoveDown: Boolean = false,
) {
    var text by remember { mutableStateOf(initial) }
    ExpressiveDialog(
        onDismissRequest = onDismiss,
        title = title,
        icon = Icons.Rounded.CreateNewFolder,
        confirmButton = { TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text(confirmText) } },
        dismissButton = {
            if (onDelete != null) TextButton(onClick = onDelete) { Text(stringResource(R.string.shell_group_delete)) }
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.shell_group_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (onMove != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { onMove(-1) }, enabled = canMoveUp, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.ArrowUpward, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.shell_group_move_up), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(onClick = { onMove(1) }, enabled = canMoveDown, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.ArrowDownward, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.shell_group_move_down), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun RunResultDialog(run: ShellRun, onDismiss: () -> Unit) {
    val clipboard = rememberTextClipboard()
    val result = run.result
    val status = when {
        result.backend == null -> stringResource(R.string.shell_no_backend)
        else -> {
            val who = stringResource(if (result.backend == ShellBackend.SHIZUKU) R.string.shell_via_shizuku else R.string.shell_via_root)
            val code = if (result.timedOut) stringResource(R.string.shell_timed_out) else stringResource(R.string.shell_exit_code, result.exitCode)
            "$who · $code"
        }
    }
    // A command run in the background only prints a "started" message; show its log file instead.
    val detachedLog = if (run.command.detached && result.backend != null) ShellRunner.detachedLogPath(run.command.id) else null
    val readMode = if (result.backend == ShellBackend.ROOT) ShellMode.ROOT else ShellMode.SHIZUKU
    var reload by remember { mutableIntStateOf(0) }
    var fileText by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var stillRunning by remember { mutableStateOf(false) }
    var stopping by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    if (detachedLog != null) {
        // Reads the log once, then keeps following it every few seconds for as long as the command runs.
        // `reload` only changes after Stop, to pick up the final state straight away.
        LaunchedEffect(reload) {
            loading = true
            if (reload == 0) delay(700)
            fileText = ShellRunner.readFile(detachedLog, readMode)
            stillRunning = ShellRunner.isDetachedRunning(run.command.id, readMode)
            loading = false
            while (stillRunning) {
                delay(LOG_FOLLOW_MS)
                // A failed read keeps the text that is already shown.
                ShellRunner.readFile(detachedLog, readMode)?.let { fileText = it }
                stillRunning = ShellRunner.isDetachedRunning(run.command.id, readMode)
            }
        }
    }
    // A background log opens at its last line and follows it while the command runs.
    val scroll = rememberTailScrollState(enabled = detachedLog != null)
    val body = if (detachedLog != null) {
        when {
            loading && fileText == null -> "…"
            fileText == null -> stringResource(R.string.shell_log_file_missing)
            fileText!!.isEmpty() -> stringResource(R.string.shell_no_output)
            else -> fileText!!
        }
    } else {
        result.output.ifEmpty { if (result.backend == null) "" else stringResource(R.string.shell_no_output) }
    }
    val copyText = if (detachedLog != null) fileText.orEmpty() else result.output
    ExpressiveDialog(
        onDismissRequest = onDismiss,
        title = run.command.name,
        icon = Icons.Rounded.Terminal,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } },
        // Stop sits with Copy and Close, in the same row of the dialog.
        dismissButton = if (copyText.isNotEmpty() || (detachedLog != null && stillRunning)) {
            {
                Row {
                    if (detachedLog != null && stillRunning) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    stopping = true
                                    ShellRunner.stopDetached(run.command.id, readMode)
                                    stopping = false
                                    reload++
                                }
                            },
                            enabled = !stopping,
                        ) { Text(stringResource(R.string.shell_log_stop), color = MaterialTheme.colorScheme.error) }
                    }
                    if (copyText.isNotEmpty()) {
                        TextButton(onClick = { clipboard.setText(copyText) }) { Text(stringResource(R.string.common_copy)) }
                    }
                }
            }
        } else null,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(status, style = MaterialTheme.typography.labelLarge)
            if (body.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(scroll)) {
                    SelectionContainer {
                        Text(
                            body,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                textDirection = TextDirection.Ltr,
                            ),
                        )
                    }
                }
            }
        }
    }
}

/** How often the log of a background command is re-read while it runs. */
private const val LOG_FOLLOW_MS = 2_500L
