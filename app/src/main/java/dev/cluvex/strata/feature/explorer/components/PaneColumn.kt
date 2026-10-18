package dev.cluvex.strata.feature.explorer.components

import dev.cluvex.strata.ui.asPath
import dev.cluvex.strata.ui.isolated
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.cluvex.strata.R
import dev.cluvex.strata.core.fs.DropKind
import dev.cluvex.strata.core.fs.DropRules
import dev.cluvex.strata.core.model.FileItem
import dev.cluvex.strata.core.model.ViewMode
import dev.cluvex.strata.feature.explorer.Pane
import dev.cluvex.strata.feature.explorer.dnd.DragSession
import dev.cluvex.strata.feature.explorer.dnd.DropHalo
import dev.cluvex.strata.feature.explorer.dnd.DropLook
import dev.cluvex.strata.feature.explorer.dnd.FileDragState
import dev.cluvex.strata.feature.explorer.dnd.LocalFileDrag
import dev.cluvex.strata.feature.explorer.dnd.PaneScroller
import dev.cluvex.strata.feature.explorer.dnd.dropAnchor
import dev.cluvex.strata.feature.explorer.dnd.dropHaloBehind
import dev.cluvex.strata.feature.explorer.dnd.dropSpot
import dev.cluvex.strata.feature.explorer.dnd.rememberDropAnimation
import dev.cluvex.strata.ui.LocalSettings
import dev.cluvex.strata.core.remote.RemoteUri
import dev.cluvex.strata.feature.explorer.NavDirection
import dev.cluvex.strata.feature.explorer.PaneState
import java.io.File
import dev.cluvex.strata.ui.expressive.Art
import dev.cluvex.strata.ui.expressive.ArtTone
import dev.cluvex.strata.ui.expressive.ExpressiveEmptyState
import dev.cluvex.strata.ui.expressive.HeroShapes
import kotlinx.coroutines.delay

private data class FolderPage(
    val path: String,
    val items: List<FileItem>,
    val direction: NavDirection,
    val cameFrom: String?,
    val error: String?,
    val viaRoot: Boolean,
    val reveal: Set<String>,
    val revealSeq: Int,
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PaneColumn(
    state: PaneState,
    visible: List<FileItem>,
    isActive: Boolean,
    showActive: Boolean,
    rootPath: String,
    onActivate: () -> Unit,
    onUp: () -> Unit,
    onOpen: (FileItem) -> Unit,
    onToggleSelect: (FileItem) -> Unit,
    onItemMenu: (FileItem) -> Unit,
    onJumpTo: () -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    displayPath: String? = null,
    pane: Pane = Pane.LEFT,
) {
    val motion = MaterialTheme.motionScheme
    val activate by rememberUpdatedState(onActivate)
    val scrollMemory = remember { HashMap<String, Pair<Int, Int>>() }
    val drag = LocalFileDrag.current
    val paneKey = "pane:${pane.ordinal}"
    val paneHover = drag?.hover?.key == paneKey
    val paneGlow by animateFloatAsState(if (paneHover) 1f else 0f, motion.defaultSpatialSpec(), label = "paneGlow")
    val glowColor = MaterialTheme.colorScheme.primary
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    Box(
        modifier
            .dropSpot(drag, paneKey, state.path, DropKind.PANE, pane.ordinal)
            .drawWithContent {
                drawContent()
                if (paneGlow > 0.01f) {
                    drawRoundRect(
                        color = glowColor,
                        alpha = paneGlow,
                        cornerRadius = CornerRadius(22.dp.toPx()),
                        style = Stroke(width = 2.5.dp.toPx()),
                    )
                }
            },
    ) {
    Column(
        Modifier.fillMaxSize().pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                activate()
            }
        },
    ) {
        PathHeader(
            path = state.path,
            display = displayPath,
            rootPath = rootPath,
            highlighted = isActive && showActive,
            loading = state.loading,
            viaRoot = state.viaRoot,
            readOnly = state.readOnly,
            onLongPress = onJumpTo,
        )

        val page = FolderPage(
            path = state.path,
            items = visible,
            direction = state.direction,
            cameFrom = state.cameFrom,
            error = state.error,
            viaRoot = state.viaRoot,
            reveal = state.reveal,
            revealSeq = state.revealSeq,
        )
        AnimatedContent(
            targetState = page,
            contentKey = { it.path },
            transitionSpec = {
                val dir = targetState.direction
                if (dir == NavDirection.FORWARD || dir == NavDirection.BACK) {
                    val sign = (if (dir == NavDirection.FORWARD) 1 else -1) * (if (rtl) -1 else 1)
                    (slideInHorizontally(motion.defaultSpatialSpec()) { w -> sign * w / 3 } + fadeIn(motion.defaultEffectsSpec()))
                        .togetherWith(slideOutHorizontally(motion.defaultSpatialSpec()) { w -> -sign * w / 3 } + fadeOut(motion.fastEffectsSpec()))
                } else {
                    fadeIn(motion.defaultEffectsSpec()).togetherWith(fadeOut(motion.fastEffectsSpec()))
                }.using(SizeTransform(clip = true))
            },
            label = "folderPage",
            modifier = Modifier.fillMaxSize().clipToBounds(),
        ) { folder ->
            val selected = if (folder.path == state.path) state.selected else emptySet()
            FolderList(
                folder = folder,
                selected = selected,
                scrollMemory = scrollMemory,
                refreshing = state.loading && folder.path == state.path,
                onRefresh = onRefresh,
                onUp = onUp,
                onOpen = onOpen,
                onToggleSelect = onToggleSelect,
                onItemMenu = onItemMenu,
                pane = pane,
                storageRoot = rootPath,
                drag = drag,
            )
        }
    }
    AnimatedVisibility(
        visible = paneHover,
        enter = scaleIn(motion.fastSpatialSpec(), initialScale = 0.7f) + fadeIn(motion.fastEffectsSpec()),
        exit = scaleOut(motion.fastSpatialSpec(), targetScale = 0.7f) + fadeOut(motion.fastEffectsSpec()),
        modifier = Modifier.align(Alignment.TopCenter).padding(top = 58.dp),
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, shadowElevation = 6.dp) {
            Text(
                stringResource(R.string.explorer_move_here),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
    }
}

private const val PARENT_KEY = ".."

internal fun isStorageRoot(path: String, storageRoot: String): Boolean {
    val p = path.trimEnd('/')
    return p == storageRoot.trimEnd('/') || p == "/sdcard"
}

private data class Flash(val paths: Set<String>, val seq: Int, val pulses: Int) {
    companion object {
        val NONE = Flash(emptySet(), 0, 0)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FolderList(
    folder: FolderPage,
    selected: Set<String>,
    scrollMemory: MutableMap<String, Pair<Int, Int>>,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onUp: () -> Unit,
    onOpen: (FileItem) -> Unit,
    onToggleSelect: (FileItem) -> Unit,
    onItemMenu: (FileItem) -> Unit,
    pane: Pane,
    storageRoot: String,
    drag: FileDragState?,
) {
    val paneSettings = LocalSettings.current
    if (paneSettings.list.viewMode == ViewMode.GRID && !paneSettings.dualPanePreferred) {
        FolderGrid(folder, selected, scrollMemory, refreshing, onRefresh, onUp, onOpen, onToggleSelect, onItemMenu, pane, storageRoot, drag)
        return
    }
    val motion = MaterialTheme.motionScheme
    val hasParent = folder.path != "/" && !isStorageRoot(folder.path, storageRoot)
    val offset = if (hasParent) 1 else 0
    val saved = scrollMemory[folder.path]
    val cameFromIndex = folder.cameFrom?.let { from -> folder.items.indexOfFirst { it.path == from } } ?: -1
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = saved?.first ?: (cameFromIndex + offset - 2).coerceAtLeast(0),
        initialFirstVisibleItemScrollOffset = saved?.second ?: 0,
    )
    DisposableEffect(folder.path) {
        onDispose { scrollMemory[folder.path] = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
    }

    var flash by remember(folder.path) {
        mutableStateOf(if (folder.cameFrom != null) Flash(setOf(folder.cameFrom), seq = 1, pulses = 1) else Flash.NONE)
    }
    val items by rememberUpdatedState(folder.items)

    LaunchedEffect(folder.path, folder.revealSeq) {
        val targets = folder.reveal
        if (targets.isEmpty()) return@LaunchedEffect
        withFrameNanos { }
        val index = items.indexOfFirst { it.path in targets }
        if (index >= 0) {
            val row = index + offset
            val layout = listState.layoutInfo
            val fullyVisible = layout.visibleItemsInfo.any {
                it.index == row && it.offset >= layout.viewportStartOffset && it.offset + it.size <= layout.viewportEndOffset
            }
            if (!fullyVisible) {
                val shown = layout.visibleItemsInfo.size.coerceAtLeast(1)
                listState.animateScrollToItem((row - shown / 2).coerceAtLeast(0))
            }
        }
        flash = Flash(targets, seq = flash.seq + 1, pulses = FLASH_PULSES)
        delay(flashDurationMs(FLASH_PULSES))
        flash = Flash(emptySet(), flash.seq, FLASH_PULSES)
    }
    LaunchedEffect(folder.path) {
        if (folder.cameFrom != null && folder.reveal.isEmpty()) {
            delay(flashDurationMs(1))
            flash = Flash(emptySet(), flash.seq, 1)
        }
    }

    val selecting = selected.isNotEmpty()

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
    val links = rememberDragLinks(drag, pane, folder, selected)
    DisposableEffect(drag, listState) {
        onDispose { if (drag?.scrollers?.get(pane.ordinal)?.owner === listState) drag.scrollers.remove(pane.ordinal) }
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .then(if (drag != null) Modifier.onGloballyPositioned { drag.scrollers[pane.ordinal] = PaneScroller(it.boundsInRoot(), listState) { d -> listState.scrollBy(d) } } else Modifier),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (hasParent) {
            item(key = PARENT_KEY, contentType = PARENT_KEY) {
                ParentRow(onClick = onUp, look = links.parent())
            }
        }
        if (folder.items.isEmpty()) {
            item(key = "empty", contentType = "empty") { EmptyFolder(folder.error) }
        }
        items(folder.items, key = { it.path }, contentType = { "file" }) { item ->
            FileRow(
                item = item,
                selected = item.path in selected,
                flashKey = if (item.path in flash.paths) flash.seq else 0,
                flashPulses = flash.pulses,
                showThumbnail = !folder.viaRoot && !RemoteUri.isRemote(folder.path),
                onClick = { if (selecting) onToggleSelect(item) else onOpen(item) },
                onLongClick = { onItemMenu(item) },
                onIconClick = { onToggleSelect(item) },
                modifier = Modifier.animateItem(
                    fadeInSpec = motion.defaultEffectsSpec(),
                    placementSpec = motion.defaultSpatialSpec(),
                    fadeOutSpec = motion.fastEffectsSpec(),
                ),
                drop = links.look(item),
                onDragStart = links.start(item),
            )
        }
    }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FolderGrid(
    folder: FolderPage,
    selected: Set<String>,
    scrollMemory: MutableMap<String, Pair<Int, Int>>,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onUp: () -> Unit,
    onOpen: (FileItem) -> Unit,
    onToggleSelect: (FileItem) -> Unit,
    onItemMenu: (FileItem) -> Unit,
    pane: Pane,
    storageRoot: String,
    drag: FileDragState?,
) {
    val motion = MaterialTheme.motionScheme
    val hasParent = folder.path != "/" && !isStorageRoot(folder.path, storageRoot)
    val offset = if (hasParent) 1 else 0
    val saved = scrollMemory[folder.path]
    val cameFromIndex = folder.cameFrom?.let { from -> folder.items.indexOfFirst { it.path == from } } ?: -1
    val gridState = rememberLazyGridState(
        initialFirstVisibleItemIndex = saved?.first ?: (cameFromIndex + offset - 2).coerceAtLeast(0),
        initialFirstVisibleItemScrollOffset = saved?.second ?: 0,
    )
    DisposableEffect(folder.path) {
        onDispose { scrollMemory[folder.path] = gridState.firstVisibleItemIndex to gridState.firstVisibleItemScrollOffset }
    }
    var flash by remember(folder.path) {
        mutableStateOf(if (folder.cameFrom != null) Flash(setOf(folder.cameFrom), seq = 1, pulses = 1) else Flash.NONE)
    }
    val items by rememberUpdatedState(folder.items)
    LaunchedEffect(folder.path, folder.revealSeq) {
        val targets = folder.reveal
        if (targets.isEmpty()) return@LaunchedEffect
        withFrameNanos { }
        val index = items.indexOfFirst { it.path in targets }
        if (index >= 0) {
            val cell = index + offset
            val layout = gridState.layoutInfo
            val fullyVisible = layout.visibleItemsInfo.any {
                it.index == cell && it.offset.y >= layout.viewportStartOffset && it.offset.y + it.size.height <= layout.viewportEndOffset
            }
            if (!fullyVisible) gridState.animateScrollToItem(cell)
        }
        flash = Flash(targets, seq = flash.seq + 1, pulses = FLASH_PULSES)
        delay(flashDurationMs(FLASH_PULSES))
        flash = Flash(emptySet(), flash.seq, FLASH_PULSES)
    }
    LaunchedEffect(folder.path) {
        if (folder.cameFrom != null && folder.reveal.isEmpty()) {
            delay(flashDurationMs(1))
            flash = Flash(emptySet(), flash.seq, 1)
        }
    }
    val selecting = selected.isNotEmpty()
    val links = rememberDragLinks(drag, pane, folder, selected)
    DisposableEffect(drag, gridState) {
        onDispose { if (drag?.scrollers?.get(pane.ordinal)?.owner === gridState) drag.scrollers.remove(pane.ordinal) }
    }
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = LocalSettings.current.list.gridColumns.let { if (it == 4) GridCells.Fixed(4) else GridCells.Adaptive(minSize = 92.dp) },
            state = gridState,
            modifier = Modifier
                .fillMaxSize()
                .testTag("file-grid")
                .then(if (drag != null) Modifier.onGloballyPositioned { drag.scrollers[pane.ordinal] = PaneScroller(it.boundsInRoot(), gridState) { d -> gridState.scrollBy(d) } } else Modifier),
            contentPadding = PaddingValues(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (hasParent) {
                item(key = PARENT_KEY, contentType = PARENT_KEY) { ParentTile(onClick = onUp, look = links.parent()) }
            }
            if (folder.items.isEmpty()) {
                item(key = "empty", contentType = "empty", span = { GridItemSpan(maxLineSpan) }) { EmptyFolder(folder.error) }
            }
            items(folder.items, key = { it.path }, contentType = { "file" }) { item ->
                FileTile(
                    item = item,
                    selected = item.path in selected,
                    selecting = selecting,
                    flashKey = if (item.path in flash.paths) flash.seq else 0,
                    flashPulses = flash.pulses,
                    showThumbnail = !folder.viaRoot && !RemoteUri.isRemote(folder.path),
                    onClick = { if (selecting) onToggleSelect(item) else onOpen(item) },
                    onLongClick = { onItemMenu(item) },
                    onIconClick = { onToggleSelect(item) },
                    modifier = Modifier.animateItem(
                        fadeInSpec = motion.defaultEffectsSpec(),
                        placementSpec = motion.defaultSpatialSpec(),
                        fadeOutSpec = motion.fastEffectsSpec(),
                    ),
                    drop = links.look(item),
                    onDragStart = links.start(item),
                )
            }
        }
    }
}

@Composable
private fun ParentTile(onClick: () -> Unit, look: DropLook = DropLook.NONE) {
    val colors = MaterialTheme.colorScheme
    val anim = rememberDropAnimation(look)
    val list = LocalSettings.current.list
    val framed = list.tileFrame
    val iconSize = if (list.gridColumns == 4) 48.dp else 56.dp
    Column(
        Modifier
            .then(look.spot)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(if (look.hovered) colors.primaryContainer else if (framed) colors.surfaceContainerLow else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClickLabel = stringResource(R.string.explorer_parent_folder), onClick = onClick)
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.fillMaxWidth().then(if (framed) Modifier.aspectRatio(1f) else Modifier.height(iconSize + 8.dp)).then(look.anchor).dropHaloBehind(anim, reach = 0.72f),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(iconSize).clip(MaterialTheme.shapes.large).background(colors.surfaceVariant), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.ArrowUpward, contentDescription = null, tint = colors.onSurfaceVariant)
            }
        }
        Text("..", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = if (framed) 6.dp else 2.dp))
    }
}

@Composable
private fun ParentRow(onClick: () -> Unit, look: DropLook = DropLook.NONE) {
    val colors = MaterialTheme.colorScheme
    val anim = rememberDropAnimation(look)
    Row(
        Modifier
            .then(look.spot)
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (look.hovered) colors.primaryContainer.copy(alpha = 0.6f) else Color.Transparent)
            .clickable(onClickLabel = stringResource(R.string.explorer_parent_folder), onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DropHalo(anim, look.anchor) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(colors.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.ArrowUpward, contentDescription = null, tint = colors.onSurfaceVariant)
            }
        }
        Text(
            "..",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 12.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PathHeader(
    path: String,
    display: String?,
    rootPath: String,
    highlighted: Boolean,
    loading: Boolean,
    viaRoot: Boolean,
    onLongPress: () -> Unit,
    readOnly: Boolean = false,
) {
    val motion = MaterialTheme.motionScheme
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (highlighted) colors.primaryContainer else colors.surfaceContainerHigh,
        motion.defaultEffectsSpec(),
        label = "headerColor",
    )
    val content by animateColorAsState(
        if (highlighted) colors.onPrimaryContainer else colors.onSurfaceVariant,
        motion.defaultEffectsSpec(),
        label = "headerContent",
    )
    val title = when {
        display != null -> RemoteUri.name(path).ifEmpty { display.substringBefore(':') }
        path == rootPath -> stringResource(R.string.explorer_internal_storage)
        path == "/" -> stringResource(R.string.explorer_root)
        else -> File(path).name
    }
    val shownPath = display ?: if (path.startsWith(rootPath)) "/sdcard" + path.removePrefix(rootPath) else path

    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp, vertical = 4.dp)
            .combinedClickable(onClick = {}, onLongClick = onLongPress, onLongClickLabel = stringResource(R.string.explorer_jump_to_path)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Column(Modifier.weight(1f)) {
                Text(isolated(title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(shownPath, style = MaterialTheme.typography.labelSmall.asPath(), maxLines = 1, overflow = TextOverflow.StartEllipsis)
            }
            AnimatedVisibility(visible = viaRoot, enter = fadeIn(motion.fastEffectsSpec()), exit = fadeOut(motion.fastEffectsSpec())) {
                Text(
                    stringResource(R.string.explorer_badge_root),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.onTertiary,
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.tertiary)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            AnimatedVisibility(visible = readOnly, enter = fadeIn(motion.fastEffectsSpec()), exit = fadeOut(motion.fastEffectsSpec())) {
                Text(
                    stringResource(R.string.explorer_badge_read_only),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSecondaryContainer,
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.secondaryContainer)
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                        .testTag("read-only-badge"),
                )
            }
            AnimatedVisibility(visible = loading, enter = fadeIn(motion.fastEffectsSpec()), exit = fadeOut(motion.fastEffectsSpec())) {
                CircularProgressIndicator(Modifier.padding(start = 6.dp).size(16.dp), strokeWidth = 2.dp)
            }
        }
    }
}

@Composable
private fun EmptyFolder(error: String?) {
    val modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 32.dp)
    if (error != null) {
        ExpressiveEmptyState(error, modifier, icon = Icons.Rounded.FolderOff, tone = ArtTone.ERROR, maxHero = 120.dp, shapes = HeroShapes.alert)
    } else {
        ExpressiveEmptyState(stringResource(R.string.explorer_empty_folder), modifier, art = Art.EMPTY_FOLDER, maxHero = 148.dp)
    }
}

private class DragLinks(val drag: FileDragState?, val pane: Pane, val folder: FolderPage, val selected: Set<String>) {
    private val paths = drag?.paths ?: emptySet()
    private val hoverKey = drag?.hover?.key
    private val gulp = drag?.gulp

    @Composable
    fun look(item: FileItem): DropLook {
        val d = drag ?: return DropLook.NONE
        val key = "${pane.ordinal}:${item.path}"
        val target = item.isDirectory
        return DropLook(
            hovered = target && hoverKey == key,
            ghosted = item.path in paths,
            gulp = if (gulp?.first == key) gulp.second else 0,
            onSpringOpen = if (target) {
                { d.spots[key]?.let(d::spring) }
            } else {
                null
            },
            spot = if (target) Modifier.dropSpot(d, key, item.path, DropKind.FOLDER, pane.ordinal) else Modifier,
            anchor = if (target) Modifier.dropAnchor(d, key) else Modifier,
        )
    }

    @Composable
    fun parent(): DropLook {
        val d = drag ?: return DropLook.NONE
        val dir = DropRules.parentOf(folder.path)
        if (!dir.startsWith("/") && !RemoteUri.isRemote(dir)) return DropLook.NONE
        val key = "${pane.ordinal}:.."
        return DropLook(
            hovered = hoverKey == key,
            ghosted = false,
            gulp = if (gulp?.first == key) gulp.second else 0,
            onSpringOpen = null,
            spot = Modifier.dropSpot(d, key, dir, DropKind.PARENT, pane.ordinal),
            anchor = Modifier.dropAnchor(d, key),
        )
    }

    fun start(item: FileItem): ((Offset, PointerId) -> Boolean)? {
        val d = drag ?: return null
        return { at, id ->
            val items = if (item.path in selected) folder.items.filter { it.path in selected } else listOf(item)
            d.begin(DragSession(pane, items, item, id, Rect(at, at)), at)
            true
        }
    }
}

@Composable
private fun rememberDragLinks(drag: FileDragState?, pane: Pane, folder: FolderPage, selected: Set<String>): DragLinks =
    DragLinks(drag, pane, folder, selected)
