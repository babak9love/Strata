package dev.cluvex.strata.feature.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.cluvex.strata.R
import dev.cluvex.strata.core.util.Formatters
import androidx.compose.ui.zIndex
import androidx.graphics.shapes.RoundedPolygon
import dev.cluvex.strata.core.model.MotionStyle
import dev.cluvex.strata.core.model.ZoneSlot
import dev.cluvex.strata.core.model.Zoned
import dev.cluvex.strata.core.model.spilledBy
import dev.cluvex.strata.ui.expressive.MorphShape
import dev.cluvex.strata.ui.expressive.rememberPolygonMorph
import dev.cluvex.strata.ui.theme.LocalAppearance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class ZoneStyle(val title: String, val empty: String, val shape: RoundedPolygon, val tint: Tint)

class ZoneButton(val icon: ImageVector, val description: (String) -> String, val zone: Int, val atEnd: Boolean)

private class ZoneDrag<T : Enum<T>>(
    val scope: CoroutineScope,
    val haptics: HapticFeedback,
) {
    var zoned: Zoned<T> = Zoned(emptyList())
    var caps: List<Int> = emptyList()
    var onChange: (Zoned<T>) -> Unit = {}
    var onSpill: (List<T>) -> Unit = {}
    var gap = 0f
    var dragged by mutableStateOf<T?>(null)
    var startTop by mutableFloatStateOf(0f)
    var dy by mutableFloatStateOf(0f)
    var moved by mutableFloatStateOf(0f)
    var target by mutableIntStateOf(-1)
    val heights = HashMap<Any, Int>()
    val tops = HashMap<Any, Animatable<Float, AnimationVector1D>>()

    val shown: Zoned<T>
        get() {
            val d = dragged ?: return zoned
            return if (target >= 0) zoned.dropAt(d, target, caps) else zoned
        }

    fun start(item: T) {
        startTop = tops[item]?.value ?: 0f
        dy = 0f
        moved = 0f
        target = zoned.flat().indexOfFirst { it is ZoneSlot.Item && it.value == item }
        dragged = item
        haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
    }

    fun drag(delta: Float) {
        if (dragged == null) return
        dy += delta
        moved += delta
        retarget()
    }

    fun follow(scrolled: Float) {
        if (dragged == null) return
        dy += scrolled
        retarget()
    }

    fun retarget() {
        val d = dragged ?: return
        val rest = zoned.flat().filterNot { it is ZoneSlot.Item && it.value == d }
        val center = startTop + dy + (heights[d] ?: 0) / 2f
        var y = 0f
        var t = 0
        rest.forEachIndexed { i, slot ->
            val h = heights[keyOf(slot)] ?: 0
            if (center > y + h / 2f) t = i + 1
            y += h + gap
        }
        t = t.coerceIn(1, rest.size)
        if (t != target) {
            val before = zoneAt(rest, target)
            target = t
            val after = zoneAt(rest, t)
            haptics.performHapticFeedback(if (before != after) HapticFeedbackType.ToggleOn else HapticFeedbackType.SegmentTick)
        }
    }

    fun drop() {
        val d = dragged ?: return
        val result = shown
        val visual = startTop + dy
        tops[d]?.let { anim -> scope.launch(start = CoroutineStart.UNDISPATCHED) { anim.snapTo(visual) } }
        dragged = null
        target = -1
        dy = 0f
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        if (result != zoned) commit(d, result)
    }

    private fun commit(item: T, result: Zoned<T>) {
        val spilled = zoned.spilledBy(item, result)
        onChange(result)
        if (spilled.isNotEmpty()) onSpill(spilled)
    }

    fun move(item: T, zone: Int, atEnd: Boolean) {
        val index = if (atEnd) Int.MAX_VALUE else 0
        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        commit(item, zoned.moveTo(item, zone, index, caps))
    }

    fun step(item: T, by: Int): Boolean {
        val flat = zoned.flat()
        val at = flat.indexOfFirst { it is ZoneSlot.Item && it.value == item }
        if (at < 0) return false
        val next = zoned.dropAt(item, at + by, caps)
        if (next == zoned) return false
        commit(item, next)
        return true
    }

    private fun zoneAt(rest: List<ZoneSlot<T>>, insertion: Int): Int {
        var zone = 0
        for (i in 0 until insertion.coerceIn(0, rest.size)) {
            val slot = rest[i]
            if (slot is ZoneSlot.Header) zone = slot.zone
        }
        return zone
    }
}

private fun keyOf(slot: ZoneSlot<*>): Any = when (slot) {
    is ZoneSlot.Header -> "zone-${slot.zone}"
    is ZoneSlot.Item -> slot.value as Any
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T : Enum<T>> ZoneEditor(
    zoned: Zoned<T>,
    caps: List<Int>,
    styles: List<ZoneStyle>,
    buttons: (Int) -> List<ZoneButton>,
    label: (T) -> String,
    icon: (T) -> ImageVector,
    scroll: ScrollState,
    onPreview: (Zoned<T>) -> Unit,
    onChange: (Zoned<T>) -> Unit,
    modifier: Modifier = Modifier,
    hint: (T) -> String? = { null },
    onSpill: (List<T>) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val reduced = LocalAppearance.current.motion == MotionStyle.REDUCED
    val drag = remember(haptics) { ZoneDrag<T>(scope, haptics) }
    drag.zoned = zoned
    drag.caps = caps
    drag.onChange = onChange
    drag.onSpill = onSpill
    drag.gap = with(density) { 3.dp.toPx() }
    val shown = drag.shown
    LaunchedEffect(shown) { onPreview(shown) }

    var columnTop by remember { mutableFloatStateOf(0f) }
    val window = LocalWindowInfo.current.containerSize
    val topInset = WindowInsets.statusBars.getTop(density) + with(density) { 72.dp.toPx() }
    val bottomInset = WindowInsets.navigationBars.getBottom(density).toFloat()
    LaunchedEffect(drag) {
        val edge = with(density) { 80.dp.toPx() }
        val speed = with(density) { 12.dp.toPx() }
        val travel = with(density) { 24.dp.toPx() }
        fun velocity(): Float {
            val d = drag.dragged ?: return 0f
            val top = columnTop + drag.startTop + drag.dy
            val bottom = top + (drag.heights[d] ?: 0)
            val low = window.height - bottomInset - edge
            return when {
                drag.moved < -travel && top < topInset + edge && scroll.canScrollBackward -> -((topInset + edge - top) / edge).coerceIn(0f, 1f) * speed
                drag.moved > travel && bottom > low && scroll.canScrollForward -> ((bottom - low) / edge).coerceIn(0f, 1f) * speed
                else -> 0f
            }
        }
        snapshotFlow { velocity() != 0f }.collectLatest { scrolling ->
            while (scrolling && isActive) {
                val v = velocity()
                if (v == 0f) break
                androidx.compose.runtime.withFrameNanos { }
                val used = scroll.scrollBy(v)
                if (used == 0f) break
                drag.follow(used)
            }
        }
    }

    val spring: AnimationSpec<Float> = if (reduced) snap() else spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow)
    val flat = shown.flat()
    val zoneOfItem = HashMap<Any, Int>().apply { flat.forEach { if (it is ZoneSlot.Item) put(it.value as Any, it.zone) } }
    val items = zoned.zones.flatten().sortedBy { it.ordinal }
    val moveUp = stringResource(R.string.settings_zone_move_up)
    val moveDown = stringResource(R.string.settings_zone_move_down)

    Layout(
        modifier = modifier
            .fillMaxWidth()
            .onGloballyPositioned { columnTop = it.positionInRoot().y }
            .testTag("zone-editor"),
        content = {
            styles.forEachIndexed { zone, style ->
                key("zone-$zone") {
                    Box(Modifier.layoutId("zone-$zone")) {
                        ZoneHeader(
                            style = style,
                            count = shown.zones.getOrNull(zone)?.size ?: 0,
                            cap = caps.getOrNull(zone)?.takeIf { it != Int.MAX_VALUE },
                            highlighted = drag.dragged != null && zoneOfItem[drag.dragged as Any] == zone,
                        )
                    }
                }
            }
            items.forEach { item ->
                key(item) {
                    val zone = zoneOfItem[item] ?: 0
                    val lifted = drag.dragged == item
                    Box(
                        Modifier
                            .layoutId(item)
                            .zIndex(if (lifted) 1f else 0f)
                            .pointerInput(item) {
                                detectDragGesturesAfterLongPress(
                                    onDragStart = { drag.start(item) },
                                    onDrag = { change, amount -> change.consume(); drag.drag(amount.y) },
                                    onDragEnd = { drag.drop() },
                                    onDragCancel = { drag.drop() },
                                )
                            }
                            .semantics {
                                stateDescription = styles[zone].title
                                customActions = listOf(
                                    CustomAccessibilityAction(moveUp) { drag.step(item, -1) },
                                    CustomAccessibilityAction(moveDown) { drag.step(item, 1) },
                                )
                            },
                    ) {
                        ZoneRow(
                            label = label(item),
                            hint = hint(item),
                            icon = icon(item),
                            style = styles[zone],
                            lifted = lifted,
                            reduced = reduced,
                            buttons = buttons(zone),
                            onButton = { b -> drag.move(item, b.zone, b.atEnd) },
                            handle = Modifier.pointerInput(item) {
                                detectDragGestures(
                                    onDragStart = { drag.start(item) },
                                    onDrag = { change, amount -> change.consume(); drag.drag(amount.y) },
                                    onDragEnd = { drag.drop() },
                                    onDragCancel = { drag.drop() },
                                )
                            },
                        )
                    }
                }
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val child = Constraints(minWidth = width, maxWidth = width)
        val placeables = measurables.associate { m -> m.layoutId to m.measure(child) }
        placeables.forEach { (k, p) -> if (k != null) drag.heights[k] = p.height }
        val desired = LinkedHashMap<Any, Float>()
        var y = 0f
        flat.forEach { slot ->
            val k = keyOf(slot)
            desired[k] = y
            y += (placeables[k]?.height ?: 0) + drag.gap
        }
        val total = (y - drag.gap).coerceAtLeast(0f).roundToInt()
        layout(width, total) {
            desired.forEach { (k, top) ->
                val p = placeables[k] ?: return@forEach
                val anim = drag.tops.getOrPut(k) { Animatable(top) }
                if (k == drag.dragged) {
                    p.place(0, (drag.startTop + drag.dy).roundToInt(), zIndex = 1f)
                } else {
                    if (anim.targetValue != top) scope.launch { anim.animateTo(top, spring) }
                    p.place(0, anim.value.roundToInt())
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ZoneHeader(style: ZoneStyle, count: Int, cap: Int?, highlighted: Boolean) {
    val colors = MaterialTheme.colorScheme
    val glow by animateColorAsState(if (highlighted) colors.primary.copy(alpha = 0.10f) else Color.Transparent)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 20.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(glow)
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                style.title,
                style = MaterialTheme.typography.titleSmallEmphasized,
                color = colors.primary,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            AnimatedContent(
                targetState = count,
                transitionSpec = {
                    val up = targetState > initialState
                    (slideInVertically { if (up) it else -it } + fadeIn()).togetherWith(slideOutVertically { if (up) -it else it } + fadeOut())
                },
            ) { n ->
                Text(
                    if (cap != null) stringResource(R.string.settings_zone_count, n, cap) else Formatters.number(n.toLong()),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        if (cap != null) {
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(cap) { i ->
                    val filled = i < count
                    val w by animateFloatAsState(if (filled) 22f else 10f, spring(dampingRatio = 0.5f, stiffness = 500f))
                    Box(
                        Modifier
                            .height(6.dp)
                            .width(w.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (filled) colors.primary else colors.outlineVariant),
                    )
                }
            }
        }
        if (count == 0) {
            val dash = colors.outline
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .heightIn(min = 52.dp)
                    .drawBehind {
                        drawRoundRect(
                            color = dash,
                            cornerRadius = CornerRadius(18.dp.toPx()),
                            style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(style.empty, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ZoneRow(
    label: String,
    hint: String?,
    icon: ImageVector,
    style: ZoneStyle,
    lifted: Boolean,
    reduced: Boolean,
    buttons: List<ZoneButton>,
    onButton: (ZoneButton) -> Unit,
    handle: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val lift by animateFloatAsState(
        if (lifted) 1f else 0f,
        if (reduced) snap() else spring(dampingRatio = 0.55f, stiffness = 420f),
    )
    var from by remember { mutableStateOf(style.shape) }
    var to by remember { mutableStateOf(style.shape) }
    val morphProgress = remember { Animatable(1f) }
    LaunchedEffect(style.shape) {
        if (style.shape != to) {
            from = to
            to = style.shape
            morphProgress.snapTo(0f)
            morphProgress.animateTo(1f, if (reduced) snap() else spring(dampingRatio = 0.6f, stiffness = 260f))
        }
    }
    val morph = rememberPolygonMorph(from, to)
    val spin = if (lifted && !reduced) {
        val t = rememberInfiniteTransition()
        t.animateFloat(0f, 360f, infiniteRepeatable(tween(5200, easing = LinearEasing))).value
    } else {
        0f
    }
    val (container, content) = when (style.tint) {
        Tint.PRIMARY -> colors.primaryContainer to colors.onPrimaryContainer
        Tint.SECONDARY -> colors.secondaryContainer to colors.onSecondaryContainer
        Tint.TERTIARY -> colors.tertiaryContainer to colors.onTertiaryContainer
        Tint.ERROR -> colors.errorContainer to colors.onErrorContainer
        Tint.NEUTRAL -> colors.outlineVariant to colors.onSurface
    }
    Surface(
        shape = RoundedCornerShape((14 + 18 * lift).dp),
        color = lerp(colors.surfaceBright, colors.primaryContainer, lift * 0.85f),
        contentColor = colors.onSurface,
        shadowElevation = (10 * lift).dp,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                val s = 1f + 0.035f * lift
                scaleX = s
                scaleY = s
                rotationZ = -1.2f * lift
            },
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .graphicsLayer { rotationZ = spin }
                    .clip(MorphShape(morph, morphProgress.value))
                    .background(container),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(22.dp).graphicsLayer { rotationZ = -spin })
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (hint != null) {
                    Text(
                        hint,
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            buttons.forEach { b ->
                IconButton(onClick = { onButton(b) }, shapes = IconButtonDefaults.shapes()) {
                    Icon(b.icon, contentDescription = b.description(label))
                }
            }
            Box(handle.size(44.dp), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.DragIndicator, contentDescription = stringResource(R.string.settings_zone_drag, label), tint = colors.onSurfaceVariant)
            }
        }
    }
}
