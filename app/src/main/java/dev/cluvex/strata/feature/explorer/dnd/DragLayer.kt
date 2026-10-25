package dev.cluvex.strata.feature.explorer.dnd

import dev.cluvex.strata.feature.explorer.components.FileNameText
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateValueAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import dev.cluvex.strata.R
import dev.cluvex.strata.core.fs.DropKind
import dev.cluvex.strata.core.model.FileItem
import dev.cluvex.strata.core.model.MotionStyle
import dev.cluvex.strata.core.util.FileKinds
import dev.cluvex.strata.core.util.Formatters
import dev.cluvex.strata.ui.LocalSettings
import dev.cluvex.strata.ui.expressive.MorphPainter
import dev.cluvex.strata.ui.expressive.rememberPolygonMorph
import dev.cluvex.strata.ui.theme.KindPalette
import dev.cluvex.strata.ui.theme.LocalAppearance
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

const val SPRING_OPEN_MS = 950

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun DragLayer(state: FileDragState, origin: () -> Offset) {
    val ghost = state.ghost ?: return
    val density = LocalDensity.current
    val reduced = LocalAppearance.current.motion == MotionStyle.REDUCED
    val landing = state.landing
    val hover = state.hover
    val live = state.session != null

    LaunchedEffect(ghost) {
        val edge = with(density) { 72.dp.toPx() }
        val speed = with(density) { 16.dp.toPx() }
        val travel = with(density) { 24.dp.toPx() }
        val origin = state.pointer
        fun edgeScroll(): Pair<PaneScroller, Float>? {
            if (state.session == null) return null
            val p = state.pointer
            val scroller = state.scrollers.values.firstOrNull {
                p.x >= it.bounds.left && p.x < it.bounds.right && p.y >= it.bounds.top - edge && p.y < it.bounds.bottom + edge
            } ?: return null
            val v = when {
                p.y < origin.y - travel && p.y < scroller.bounds.top + edge -> -((scroller.bounds.top + edge - p.y) / edge).coerceIn(0f, 1f) * speed
                p.y > origin.y + travel && p.y > scroller.bounds.bottom - edge -> ((p.y - (scroller.bounds.bottom - edge)) / edge).coerceIn(0f, 1f) * speed
                else -> 0f
            }
            return if (v == 0f) null else scroller to v
        }
        snapshotFlow { edgeScroll() != null }.collectLatest { scrolling ->
            while (scrolling && isActive) {
                val (scroller, v) = edgeScroll() ?: break
                androidx.compose.runtime.withFrameNanos { }
                if (scroller.scrollBy(v) != 0f) state.retarget()
            }
        }
    }

    var tiltTarget by remember(ghost) { mutableFloatStateOf(0f) }
    LaunchedEffect(ghost) {
        var last = state.pointer.x
        var settle: Job? = null
        snapshotFlow { state.pointer.x }.collect { x ->
            tiltTarget = ((x - last) * 0.9f).coerceIn(-14f, 14f)
            last = x
            settle?.cancel()
            settle = launch {
                delay(90)
                tiltTarget = 0f
            }
        }
    }
    LaunchedEffect(landing?.seq) {
        val l = landing ?: return@LaunchedEffect
        delay(if (l.into) 460 else 380)
        if (state.landing?.seq == l.seq) {
            state.ghost = null
            state.landing = null
        }
    }

    val target = when {
        landing != null -> landing.point
        hover != null && hover.kind != DropKind.PANE -> lerp(state.pointer, state.anchorOf(hover), 0.38f)
        else -> state.pointer
    }
    val follow: AnimationSpec<Offset> = if (reduced) {
        snap()
    } else if (landing != null) {
        spring(dampingRatio = 0.78f, stiffness = 320f)
    } else {
        spring(dampingRatio = 0.86f, stiffness = 2400f)
    }
    val at by animateValueAsState(target, Offset.VectorConverter, follow, label = "ghostAt")
    val scale by animateFloatAsState(
        when {
            landing?.into == true -> 0.1f
            landing != null -> 0.85f
            !live -> 1f
            hover != null && hover.kind != DropKind.PANE -> 0.7f
            else -> 1f
        },
        if (reduced) snap() else spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow),
        label = "ghostScale",
    )
    val lift by animateFloatAsState(if (live || landing != null) 1f else 0f, if (reduced) snap() else spring(dampingRatio = 0.45f, stiffness = 420f), label = "ghostLift")
    val fade by animateFloatAsState(if (landing != null) 0f else 1f, tween(if (landing?.into == true) 420 else 300), label = "ghostFade")
    val tilt by animateFloatAsState(if (reduced || landing != null) 0f else tiltTarget, spring(dampingRatio = 0.45f, stiffness = 260f), label = "ghostTilt")
    val above = with(density) { 52.dp.toPx() }
    val hint = when {
        hover == null -> if (ghost.items.size > 1) pluralStringResource(R.plurals.common_items, ghost.items.size, ghost.items.size) else stringResource(R.string.explorer_drop_on_folder)
        hover.kind == DropKind.PANE -> stringResource(R.string.explorer_move_here)
        hover.kind == DropKind.PARENT -> stringResource(R.string.explorer_up_one_folder)
        else -> stringResource(R.string.explorer_drop_into, hover.dir.substringAfterLast('/'))
    }

    Box(Modifier.fillMaxSize().zIndex(20f), contentAlignment = AbsoluteAlignment.TopLeft) {
        Box(
            Modifier
                .layout { m, c ->
                    val p = m.measure(c.copy(minWidth = 0, minHeight = 0))
                    val o = origin()
                    layout(p.width, p.height) {
                        p.place((at.x - o.x - p.width / 2f).roundToInt(), (at.y - o.y - p.height - above * (1f - (1f - scale) * 0.6f)).roundToInt())
                    }
                }
                .graphicsLayer {
                    val s = scale * (0.86f + 0.14f * lift)
                    scaleX = s
                    scaleY = s
                    rotationZ = tilt
                    alpha = fade
                    transformOrigin = TransformOrigin(0.5f, 1f)
                }
                .testTag("drag-ghost"),
        ) {
            GhostCard(ghost.primary, ghost.items.size, hint, reduced)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun GhostCard(item: FileItem, count: Int, hint: String, reduced: Boolean) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(26.dp)
    val fan = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1f else 1f
    Box {
        if (count > 1) {
            repeat(minOf(count - 1, 2)) { i ->
                Surface(
                    shape = shape,
                    color = colors.surfaceContainerHigh,
                    shadowElevation = 4.dp,
                    modifier = Modifier.matchParentSize().graphicsLayer {
                        translationX = fan * (i + 1) * 7.dp.toPx()
                        translationY = (i + 1) * 5.dp.toPx()
                        rotationZ = fan * (i + 1) * 4f
                    },
                ) {}
            }
        }
        Surface(shape = shape, color = colors.surfaceContainerHighest, shadowElevation = 14.dp, tonalElevation = 2.dp) {
            Row(Modifier.padding(start = 10.dp, end = 18.dp, top = 10.dp, bottom = 10.dp).widthIn(max = 260.dp), verticalAlignment = Alignment.CenterVertically) {
                GhostIcon(item, reduced)
                Column(Modifier.padding(start = 12.dp)) {
                    FileNameText(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text(hint, style = MaterialTheme.typography.labelMedium, color = colors.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (count > 1) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 8.dp, y = (-8).dp)
                    .size(28.dp)
                    .clip(MaterialShapes.Cookie7Sided.toShape())
                    .background(colors.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(Formatters.number(count.toLong()), style = MaterialTheme.typography.labelMedium, color = colors.onPrimary)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun GhostIcon(item: FileItem, reduced: Boolean) {
    val colors = MaterialTheme.colorScheme
    val settings = LocalSettings.current
    val kind = FileKinds.of(item)
    val tile = KindPalette.colors(kind, settings.appearance.colorfulIcons, colors, colors.background.luminance() < 0.5f)
    val morph = rememberPolygonMorph(MaterialShapes.Cookie9Sided, MaterialShapes.SoftBurst)
    val painter = remember { MorphPainter() }
    val (progress, spin) = if (reduced) {
        0f to 0f
    } else {
        val t = rememberInfiniteTransition(label = "ghostIcon")
        val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400), RepeatMode.Reverse), label = "ghostMorph")
        val r by t.animateFloat(0f, 360f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "ghostSpin")
        p to r
    }
    Box(
        Modifier
            .size(46.dp)
            .drawBehind { with(painter) { draw(morph, progress, tile.container, rotation = spin) } },
        contentAlignment = Alignment.Center,
    ) {
        Icon(kind.icon, contentDescription = null, tint = tile.content, modifier = Modifier.size(24.dp))
    }
}
