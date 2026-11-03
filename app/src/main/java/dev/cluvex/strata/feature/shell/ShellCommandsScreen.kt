package dev.cluvex.strata.feature.shell

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.History
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    if (sec < 60) stringResource(R.string.shell_timeout_seconds, sec) else stringResource(R.string.shell_timeout_minutes, sec / 60)

/** [command] null means "new command". */
private class EditTarget(val command: SavedCommand?)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ShellCommandsScreen(onBack: () -> Unit, vm: ShellCommandsViewModel = viewModel()) {
    val ui by vm.state.collectAsState()
    var editing by remember { mutableStateOf<EditTarget?>(null) }
    var showLog by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.reload() }
    val listState = rememberLazyListState()
    var dragId by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
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
    }

    fun endDrag() {
        if (dragId != null) {
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
        val edge = with(density) { 56.dp.toPx() }
        val top = cur.offset + dragOffset
        val bottom = top + cur.size
        val scroll = when {
            top < info.viewportStartOffset + edge -> -16f
            bottom > info.viewportEndOffset - edge -> 16f
            else -> 0f
        }
        if (scroll != 0f) {
            val consumed = listState.dispatchRawDelta(scroll)
            if (consumed != 0f) {
                dragOffset += consumed
                return
            }
        }
        val center = cur.offset + dragOffset + cur.size / 2f
        val next = visible.firstOrNull { it.index == cur.index + 1 }
        val prev = visible.firstOrNull { it.index == cur.index - 1 }
        val target = when {
            next != null && center > next.offset + next.size / 2f -> next
            prev != null && center < prev.offset + prev.size / 2f -> prev
            else -> null
        } ?: return
        val newOffset = if (target.index > cur.index) target.offset + target.size - cur.size else target.offset
        dragOffset += cur.offset - newOffset
        pendingIndex = target.index
        vm.move(cur.index, target.index)
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
                    IconButton(onClick = { showLog = true }) {
                        Icon(Icons.Rounded.History, contentDescription = stringResource(R.string.shell_log_title))
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
                ui.commands.isEmpty() -> ExpressiveEmptyState(
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
                    items(ui.commands, key = { it.id }) { c ->
                        val lifted = dragId == c.id
                        CommandCard(
                            command = c,
                            running = ui.runningId == c.id,
                            lifted = lifted,
                            dragOffset = { dragOffset },
                            onRun = { if (ui.runningId == null) vm.run(c) },
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
                            modifier = if (lifted) Modifier else Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }

    editing?.let { target ->
        EditCommandDialog(
            existing = target.command,
            onSave = { name, command, shortcut, mode, timeoutSec -> vm.save(target.command?.id, name, command, shortcut, mode, timeoutSec); editing = null },
            onDelete = target.command?.let { c -> { vm.delete(c.id); editing = null } },
            onDismiss = { editing = null },
        )
    }

    ui.run?.let { run -> RunResultDialog(run, onDismiss = vm::dismissRun) }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CommandCard(
    command: SavedCommand,
    running: Boolean,
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
    val morph = rememberPolygonMorph(MaterialShapes.Cookie9Sided, MaterialShapes.Clover4Leaf)
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
        color = lerp(colors.surfaceBright, colors.primaryContainer, lift * 0.85f),
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
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(40.dp)
                        .graphicsLayer { rotationZ = spin }
                        .clip(MorphShape(morph, morphProgress))
                        .background(colors.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.Terminal,
                        contentDescription = null,
                        tint = colors.onPrimaryContainer,
                        modifier = Modifier.size(22.dp).graphicsLayer { rotationZ = -spin },
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        command.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (command.shortcut) {
                        Text(
                            stringResource(R.string.shell_in_menu),
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.primary,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                IconButton(onClick = onRun, modifier = Modifier.size(48.dp)) {
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
            Text(
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
    onSave: (name: String, command: String, shortcut: Boolean, mode: ShellMode, timeoutSec: Int) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var command by remember { mutableStateOf(existing?.command.orEmpty()) }
    var shortcut by remember { mutableStateOf(existing?.shortcut ?: false) }
    var mode by remember { mutableStateOf(existing?.mode ?: ShellMode.AUTO) }
    var timeoutSec by remember { mutableStateOf(existing?.timeoutSec ?: ShellRunner.DEFAULT_TIMEOUT_SEC) }
    ExpressiveDialog(
        onDismissRequest = onDismiss,
        title = stringResource(if (existing == null) R.string.shell_add else R.string.shell_edit),
        icon = Icons.Rounded.Terminal,
        confirmButton = {
            TextButton(onClick = { onSave(name, command, shortcut, mode, timeoutSec) }, enabled = command.isNotBlank()) {
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.shell_shortcut), modifier = Modifier.weight(1f))
                Switch(checked = shortcut, onCheckedChange = { shortcut = it })
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
                    TIMEOUT_CHOICES.forEach { sec ->
                        DropdownMenuItem(
                            text = { Text(timeoutLabel(sec)) },
                            onClick = { timeoutSec = sec; menuOpen = false },
                        )
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
    val body = result.output.ifEmpty { if (result.backend == null) "" else stringResource(R.string.shell_no_output) }
    ExpressiveDialog(
        onDismissRequest = onDismiss,
        title = run.command.name,
        icon = Icons.Rounded.Terminal,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } },
        dismissButton = if (result.output.isNotEmpty()) {
            { TextButton(onClick = { clipboard.setText(result.output) }) { Text(stringResource(R.string.common_copy)) } }
        } else null,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(status, style = MaterialTheme.typography.labelLarge)
            if (body.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
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
