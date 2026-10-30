package dev.cluvex.strata.feature.explorer.components

import dev.cluvex.strata.ui.expressive.popupBorder
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.MoreVert
import dev.cluvex.strata.ui.expressive.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.zIndex
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.cluvex.strata.R
import dev.cluvex.strata.core.bookmarks.Bookmark
import dev.cluvex.strata.core.bookmarks.Bookmarks
import dev.cluvex.strata.ui.asPath
import dev.cluvex.strata.ui.expressive.ArtTone
import dev.cluvex.strata.ui.expressive.ExpressiveEmptyState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class BookmarkActions(
    val onOpen: (Bookmark) -> Unit,
    val onAddCurrent: () -> Unit,
    val onUpdate: (path: String, edited: Bookmark) -> Unit,
    val onMove: (path: String, offset: Int) -> Unit,
    val onDelete: (path: String) -> Unit,
    val onRenameGroup: (from: String, to: String) -> Unit,
    val onDeleteGroup: (String) -> Unit,
)

/** A row that was just dropped: it glides into its slot while the list still shows the old order, until the new order arrives. */
private class Settle(val path: String, val target: Int, val base: List<Bookmark>)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksSheet(bookmarks: List<Bookmark>, actions: BookmarkActions, onDismiss: () -> Unit) {
    var editing by remember { mutableStateOf<Bookmark?>(null) }
    var deleting by remember { mutableStateOf<Bookmark?>(null) }
    var renamingGroup by remember { mutableStateOf<String?>(null) }
    var deletingGroup by remember { mutableStateOf<String?>(null) }
    val groups = Bookmarks.groups(bookmarks).filter { g -> g == Bookmarks.DEFAULT_GROUP || bookmarks.any { it.group == g } }
    val showHeaders = groups.size > 1
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scrollState = rememberScrollState()
    // Layout position (inside the scrolled content) and height of every row, so a drop can be resolved from where it was released.
    val tops = remember { HashMap<String, Float>() }
    val heights = remember { HashMap<String, Float>() }
    var viewportHeight by remember { mutableFloatStateOf(0f) }
    var dragPath by remember { mutableStateOf<String?>(null) }
    var dragDy by remember { mutableFloatStateOf(0f) }
    var fingerY by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val latest by rememberUpdatedState(bookmarks)
    val settleAnim = remember { Animatable(0f) }
    var settle by remember { mutableStateOf<Settle?>(null) }
    val settling = settle?.takeIf { it.base == bookmarks }
    // Where the held row would land if it were released now, among the rows of its own group.
    val liveTarget by remember {
        derivedStateOf {
            val p = dragPath
            if (p == null) -1 else {
                val group = latest.firstOrNull { it.path == p }?.group
                val center = (tops[p] ?: 0f) + (heights[p] ?: 0f) / 2 + dragDy
                latest.count { it.group == group && it.path != p && (tops[it.path] ?: 0f) + (heights[it.path] ?: 0f) / 2 < center }
            }
        }
    }
    val activePath = dragPath ?: settling?.path
    val activeTarget = if (dragPath != null) liveTarget else settling?.target ?: -1

    LaunchedEffect(bookmarks) { settle = null }

    fun release(b: Bookmark, members: List<Bookmark>, cancelled: Boolean) {
        val from = members.indexOfFirst { it.path == b.path }
        val center = (tops[b.path] ?: 0f) + (heights[b.path] ?: 0f) / 2 + dragDy
        val target = if (cancelled) from else members.count { it.path != b.path && (tops[it.path] ?: 0f) + (heights[it.path] ?: 0f) / 2 < center }
        val slot = when {
            target > from -> members.subList(from + 1, target + 1).sumOf { (heights[it.path] ?: 0f).toDouble() }.toFloat()
            target < from -> -members.subList(target, from).sumOf { (heights[it.path] ?: 0f).toDouble() }.toFloat()
            else -> 0f
        }
        val fromDy = dragDy
        val base = latest
        // Undispatched, so the glide starts from exactly where the finger let go.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            settleAnim.snapTo(fromDy)
            settleAnim.animateTo(slot, tween(180))
            if (target != from) actions.onMove(b.path, target - from)
            delay(600)
            if (settle?.path == b.path) settle = null
        }
        settle = Settle(b.path, target, base)
        dragPath = null
        dragDy = 0f
        if (!cancelled) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
    }

    // While a row is held near the top or bottom edge, the list scrolls and the row keeps following the finger.
    LaunchedEffect(dragPath) {
        if (dragPath == null) return@LaunchedEffect
        val edge = with(density) { 56.dp.toPx() }
        val maxStep = with(density) { 14.dp.toPx() }
        while (true) {
            val h = viewportHeight
            val step = when {
                h <= 0f -> 0f
                fingerY < edge -> -maxStep * ((edge - fingerY) / edge).coerceIn(0f, 1f)
                fingerY > h - edge -> maxStep * ((fingerY - (h - edge)) / edge).coerceIn(0f, 1f)
                else -> 0f
            }
            if (step != 0f) dragDy += scrollState.scrollBy(step)
            withFrameNanos { }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.common_bookmarks), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = actions.onAddCurrent) {
                Icon(Icons.Rounded.BookmarkAdd, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text(stringResource(R.string.explorer_add_current_folder))
            }
        }
        if (bookmarks.isEmpty()) {
            ExpressiveEmptyState(
                stringResource(R.string.explorer_no_bookmarks),
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                icon = Icons.Rounded.Bookmarks,
                tone = ArtTone.TERTIARY,
                body = stringResource(R.string.explorer_no_bookmarks_body),
                maxHero = 104.dp,
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .onSizeChanged { viewportHeight = it.height.toFloat() }
                .verticalScroll(scrollState)
                .padding(bottom = 16.dp),
        ) {
            for (group in groups) {
                val members = Bookmarks.inGroup(bookmarks, group)
                if (showHeaders) {
                    GroupHeader(
                        name = group,
                        onRename = { renamingGroup = group },
                        onDelete = { deletingGroup = group },
                    )
                }
                members.forEach { b ->
                    val index = members.indexOfFirst { it.path == b.path }
                    val activeIndex = if (activePath == null) -1 else members.indexOfFirst { it.path == activePath }
                    val activeHeight = activePath?.let { heights[it] } ?: 0f
                    val shift = when {
                        activeIndex < 0 || b.path == activePath -> 0f
                        activeTarget > activeIndex && index in (activeIndex + 1)..activeTarget -> -activeHeight
                        activeTarget < activeIndex && index in activeTarget until activeIndex -> activeHeight
                        else -> 0f
                    }
                    BookmarkRow(
                        bookmark = b,
                        isFirst = b == members.first(),
                        isLast = b == members.last(),
                        lifted = activePath == b.path,
                        liftOffset = { if (dragPath == b.path) dragDy else settleAnim.value },
                        shift = shift,
                        shifting = activePath != null,
                        onDragStart = { localY ->
                            if (dragPath == null && settling == null) {
                                dragPath = b.path
                                dragDy = 0f
                                fingerY = (tops[b.path] ?: 0f) - scrollState.value + localY
                                haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                            }
                        },
                        onDrag = { dy, localY ->
                            if (dragPath == b.path) {
                                dragDy += dy
                                fingerY = (tops[b.path] ?: 0f) - scrollState.value + dragDy + localY
                            }
                        },
                        onDragEnd = { if (dragPath == b.path) release(b, members, cancelled = false) },
                        onDragCancel = { if (dragPath == b.path) release(b, members, cancelled = true) },
                        modifier = Modifier.onGloballyPositioned {
                            tops[b.path] = it.positionInParent().y
                            heights[b.path] = it.size.height.toFloat()
                        },
                        onOpen = { actions.onOpen(b) },
                        onEdit = { editing = b },
                        onMove = { offset -> actions.onMove(b.path, offset) },
                        onDelete = { deleting = b },
                    )
                }
            }
        }
    }

    editing?.let { b ->
        BookmarkEditDialog(
            bookmark = b,
            groups = groups,
            onSave = { edited -> actions.onUpdate(b.path, edited); editing = null },
            onDismiss = { editing = null },
            onDelete = { actions.onDelete(b.path); editing = null },
        )
    }
    deleting?.let { b ->
        ConfirmDialog(
            title = stringResource(R.string.explorer_delete_bookmark),
            message = stringResource(R.string.explorer_delete_bookmark_confirm, b.name),
            confirmText = stringResource(R.string.common_delete),
            onConfirm = { actions.onDelete(b.path); deleting = null },
            onDismiss = { deleting = null },
        )
    }
    renamingGroup?.let { g ->
        InputDialog(
            title = stringResource(R.string.explorer_rename_group),
            label = stringResource(R.string.explorer_group_name),
            initial = g,
            select = InputSelection.ALL,
            onConfirm = { actions.onRenameGroup(g, it); renamingGroup = null },
            onDismiss = { renamingGroup = null },
        )
    }
    deletingGroup?.let { g ->
        ConfirmDialog(
            title = stringResource(R.string.explorer_delete_group),
            message = stringResource(R.string.explorer_delete_group_confirm, g),
            confirmText = stringResource(R.string.common_delete),
            onConfirm = { actions.onDeleteGroup(g); deletingGroup = null },
            onDismiss = { deletingGroup = null },
        )
    }
}

@Composable
private fun GroupHeader(name: String, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            name.ifEmpty { stringResource(R.string.explorer_default_group) },
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        if (name != Bookmarks.DEFAULT_GROUP) {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.explorer_group_options)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, border = popupBorder()) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.explorer_rename_group)) }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.explorer_delete_group)) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookmarkRow(
    bookmark: Bookmark,
    isFirst: Boolean,
    isLast: Boolean,
    lifted: Boolean,
    liftOffset: () -> Float,
    shift: Float,
    shifting: Boolean,
    onDragStart: (localY: Float) -> Unit,
    onDrag: (dy: Float, localY: Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val dragStart by rememberUpdatedState(onDragStart)
    val drag by rememberUpdatedState(onDrag)
    val dragEnd by rememberUpdatedState(onDragEnd)
    val dragCancel by rememberUpdatedState(onDragCancel)
    val description = stringResource(R.string.explorer_bookmark_cd, bookmark.name)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    var rowHeight by remember { mutableFloatStateOf(0f) }
    var handleDown by remember { mutableStateOf(false) }
    // Rows the held one passes slide out of its way; once nothing is held they snap back, because by then the list itself has the new order.
    val slide by animateFloatAsState(
        targetValue = if (shifting) shift else 0f,
        animationSpec = if (shifting) tween(180) else snap(),
        label = "bookmark-slide",
    )
    val handleTint by animateColorAsState(
        targetValue = if (handleDown || lifted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        label = "bookmark-handle",
    )
    ListItem(
        content = { Text(bookmark.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Text(bookmark.path, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall.asPath())
        },
        leadingContent = { Icon(Icons.Rounded.Bookmark, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(44.dp)
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                handleDown = true
                                do {
                                    val event = awaitPointerEvent(PointerEventPass.Final)
                                } while (event.changes.any { it.pressed })
                                handleDown = false
                            }
                        }
                        .pointerInput(bookmark.path) {
                            // The handle's coordinates are its own; the drag logic wants them inside the row, and the handle sits at the row's vertical middle.
                            fun inRow(y: Float) = rowHeight / 2f + (y - size.height / 2f)
                            detectDragGestures(
                                onDragStart = { offset -> dragStart(inRow(offset.y)) },
                                onDrag = { change, amount -> change.consume(); drag(amount.y, inRow(change.position.y)) },
                                onDragEnd = { dragEnd() },
                                onDragCancel = { dragCancel() },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Rounded.DragIndicator,
                        contentDescription = stringResource(R.string.settings_zone_drag, bookmark.name),
                        tint = handleTint,
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.explorer_options_for, bookmark.name)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, border = popupBorder()) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.common_edit)) }, onClick = { menu = false; onEdit() })
                        if (!isFirst) DropdownMenuItem(text = { Text(stringResource(R.string.explorer_move_up)) }, onClick = { menu = false; onMove(-1) })
                        if (!isLast) DropdownMenuItem(text = { Text(stringResource(R.string.explorer_move_down)) }, onClick = { menu = false; onMove(+1) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.common_delete)) }, onClick = { menu = false; onDelete() })
                    }
                }
            }
        },
        modifier = modifier
            .onSizeChanged { rowHeight = it.height.toFloat() }
            .zIndex(if (lifted) 1f else 0f)
            .graphicsLayer {
                translationY = if (lifted) liftOffset() else if (shifting) slide else 0f
                if (lifted) {
                    shadowElevation = 12.dp.toPx()
                    shape = RoundedCornerShape(16.dp)
                }
            }
            .pointerInput(bookmark.path, rtl) {
                // A long press over the handle and the three-dot button does nothing here; the handle starts its own drag.
                val trailing = 112.dp.toPx()
                var ignored = false
                detectDragGesturesAfterLongPress(
                    onDragStart = { offset ->
                        ignored = if (rtl) offset.x < trailing else offset.x > size.width - trailing
                        if (!ignored) dragStart(offset.y)
                    },
                    onDrag = { change, amount ->
                        if (!ignored) { change.consume(); drag(amount.y, change.position.y) }
                    },
                    onDragEnd = { if (!ignored) dragEnd(); ignored = false },
                    onDragCancel = { if (!ignored) dragCancel(); ignored = false },
                )
            }
            .semantics { contentDescription = description }
            .combinedClickable(onClick = onOpen, onLongClick = {}),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BookmarkEditDialog(bookmark: Bookmark, groups: List<String>, onSave: (Bookmark) -> Unit, onDismiss: () -> Unit, onDelete: (() -> Unit)? = null) {
    var confirmDelete by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(bookmark.name) }
    var path by remember { mutableStateOf(bookmark.path) }
    var group by remember { mutableStateOf(bookmark.group) }
    val valid = name.isNotBlank() && path.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.explorer_edit_bookmark)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.common_name)) }, singleLine = true)
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text(stringResource(R.string.common_path)) },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                )
                OutlinedTextField(
                    value = group,
                    onValueChange = { group = it },
                    label = { Text(stringResource(R.string.explorer_bookmark_group)) },
                    placeholder = { Text(stringResource(R.string.explorer_default_group)) },
                    singleLine = true,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (listOf(Bookmarks.DEFAULT_GROUP) + groups).distinct().forEach { g ->
                        FilterChip(selected = group.trim() == g, onClick = { group = g }, label = { Text(g.ifEmpty { stringResource(R.string.explorer_default_group) }) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(Bookmark(name.trim(), path.trim(), group.trim())) }, enabled = valid) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = { confirmDelete = true }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                        Text(stringResource(R.string.common_delete))
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
            }
        },
    )
    if (confirmDelete && onDelete != null) {
        ConfirmDialog(
            title = stringResource(R.string.explorer_delete_bookmark),
            message = stringResource(R.string.explorer_delete_bookmark_confirm, bookmark.name),
            confirmText = stringResource(R.string.common_delete),
            onConfirm = { confirmDelete = false; onDelete() },
            onDismiss = { confirmDelete = false },
        )
    }
}
