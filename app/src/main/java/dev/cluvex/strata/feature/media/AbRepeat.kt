package dev.cluvex.strata.feature.media

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.materialIcon
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import dev.cluvex.strata.R
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Loop points of the A-B repeat in the video player; positions are in milliseconds. */
class AbRepeatState {
    var a by mutableStateOf<Long?>(null)
    var b by mutableStateOf<Long?>(null)

    /** True when both points are set and the loop is running. */
    val active: Boolean get() = a != null && b != null && b!! > a!!

    /** True when any point is set (the toolbar icon is highlighted). */
    val armed: Boolean get() = a != null || b != null

    fun setA(ms: Long) {
        a = ms
        // Keep a < b: if the new A is past B, B is cleared so the loop is set again.
        if (b != null && ms >= b!!) b = null
    }

    fun setB(ms: Long) {
        val start = a
        when {
            // Same spot as A: a loop of zero length would throw playback back to A all the time, so B is not set.
            start != null && ms == start -> Unit
            // B before A: the two points swap so the loop is always forward.
            start != null && ms < start -> {
                b = start
                a = ms
            }
            else -> b = ms
        }
    }

    fun clear() {
        a = null
        b = null
    }
}

private const val RETURN_WINDOW_MS = 1500L

/** While both points are set, jumps back to A each time playback reaches B (or falls outside the loop after B). */
@Composable
fun AbRepeatEffect(player: Player?, state: AbRepeatState) {
    // The points belong to one video: another video starts with a clean slate.
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = state.clear()
        }
        player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }
    val a = state.a
    val b = state.b
    LaunchedEffect(player, a, b) {
        if (player == null || a == null || b == null || b <= a) return@LaunchedEffect
        while (true) {
            // Only the moment playback runs past B sends it back to A. A seek far beyond B (or before A) is left alone,
            // so the seek bar can be dragged anywhere.
            val position = player.currentPosition
            if (position >= b && position < b + RETURN_WINDOW_MS) player.seekTo(a)
            delay(40)
        }
    }
}

/** Theme colors of the A-B card. */
class AbThemeColors(val container: Color, val content: Color, val button: Color, val buttonBorder: Color)

/**
 * The card follows the theme. In the light theme the A and B buttons are white with a thin outline, so they stand out
 * from the light card (the card and a "highest" surface are almost the same gray there); the dark theme keeps its look.
 */
@Composable
fun abThemeColors(): AbThemeColors {
    val scheme = androidx.compose.material3.MaterialTheme.colorScheme
    val light = scheme.surface.luminance() > 0.5f
    return AbThemeColors(
        container = scheme.surfaceContainerHigh.copy(alpha = PANEL_ALPHA),
        content = scheme.onSurface,
        button = if (light) scheme.surfaceContainerLowest else scheme.surfaceContainerHighest,
        buttonBorder = if (light) scheme.outline.copy(alpha = 0.45f) else Color.Transparent,
    )
}

/**
 * The compact "A - B Repeat" card: tap A / B to take the current position; tap a button that already holds a time
 * to clear it again (back to the plain "A" / "B" label) and pick a new point. X only closes the card (the loop keeps running).
 * The colors default to the dark look over video; the music sheet passes theme colors.
 */
@Composable
fun AbRepeatPanel(
    player: Player?,
    state: AbRepeatState,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = Color(0xF22B2629),
    content: Color = Color.White,
    button: Color = Color(0xFF3A3A3A),
    buttonBorder: Color = Color.Transparent,
    draggable: Boolean = false,
) {
    // The card can be dragged anywhere inside the area it sits in (the player, or the music sheet) to reach what is underneath.
    var drag by remember { mutableStateOf(Offset.Zero) }
    var pos by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var parentPos by remember { mutableStateOf(Offset.Zero) }
    var parentSize by remember { mutableStateOf(IntSize.Zero) }
    val dragModifier = if (draggable) {
        Modifier
            .absoluteOffset { IntOffset(drag.x.roundToInt(), drag.y.roundToInt()) }
            .onGloballyPositioned {
                pos = it.positionInWindow()
                size = it.size
                it.parentLayoutCoordinates?.let { parent ->
                    parentPos = parent.positionInWindow()
                    parentSize = parent.size
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, amount ->
                    change.consume()
                    val nx = (pos.x + amount.x).coerceIn(parentPos.x, parentPos.x + (parentSize.width - size.width).coerceAtLeast(0))
                    val ny = (pos.y + amount.y).coerceIn(parentPos.y, parentPos.y + (parentSize.height - size.height).coerceAtLeast(0))
                    val delta = Offset(nx - pos.x, ny - pos.y)
                    pos += delta
                    drag += delta
                }
            }
    } else Modifier
    AbRepeatCard(player, state, onClose, modifier.then(dragModifier), container, content, button, buttonBorder)
}

@Composable
private fun AbRepeatCard(
    player: Player?,
    state: AbRepeatState,
    onClose: () -> Unit,
    modifier: Modifier,
    container: Color,
    content: Color,
    button: Color,
    buttonBorder: Color,
) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Surface(
            modifier = modifier.widthIn(max = 250.dp),
            shape = RoundedCornerShape(10.dp),
            color = container,
            border = BorderStroke(1.dp, content.copy(alpha = 0.35f)),
        ) {
            Column(Modifier.padding(start = 12.dp, end = 2.dp, top = 4.dp, bottom = 10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.media_ab_repeat),
                        color = content,
                        style = androidx.compose.material3.MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onClose, modifier = Modifier.size(38.dp)) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.common_close),
                            tint = content.copy(alpha = 0.7f),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    PointButton("A", state.a, button, content, buttonBorder, Modifier.weight(1f)) {
                        // A point that already has a time goes back to the plain label so a new one can be taken.
                        if (state.a != null) state.a = null else player?.let { state.setA(it.currentPosition) }
                    }
                    Icon(
                        Icons.Rounded.SwapHoriz,
                        contentDescription = null,
                        tint = content.copy(alpha = 0.45f),
                        modifier = Modifier.size(20.dp),
                    )
                    PointButton("B", state.b, button, content, buttonBorder, Modifier.weight(1f)) {
                        if (state.b != null) state.b = null else player?.let { state.setB(it.currentPosition) }
                    }
                }
            }
        }
    }
}

@Composable
private fun PointButton(label: String, ms: Long?, container: Color, content: Color, border: Color, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(6.dp),
        color = container,
        border = BorderStroke(1.dp, border),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                if (ms != null) formatTime(ms) else label,
                color = content,
                fontWeight = FontWeight.Bold,
                style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
            )
        }
    }
}

/** Toolbar icon: "A →" over "← B", drawn like the other Material icons so it takes the same tint and size. */
val AbRepeatIcon: ImageVector by lazy {
    materialIcon(name = "Rounded.AbRepeat") {
        val stroke = SolidColor(Color.Black)
        fun line(build: PathBuilder.() -> Unit) = path(
            fill = null,
            stroke = stroke,
            strokeLineWidth = 2.2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = build,
        )
        // A
        line { moveTo(2.4f, 11.2f); lineTo(6.2f, 2.8f); lineTo(10f, 11.2f); moveTo(3.9f, 8.4f); horizontalLineTo(8.5f) }
        // arrow pointing right
        line { moveTo(12.8f, 7f); horizontalLineTo(21.4f); moveTo(18.4f, 4f); lineTo(21.4f, 7f); lineTo(18.4f, 10f) }
        // arrow pointing left
        line { moveTo(11.2f, 17f); horizontalLineTo(2.6f); moveTo(5.6f, 14f); lineTo(2.6f, 17f); lineTo(5.6f, 20f) }
        // B
        line {
            moveTo(15.2f, 13f); verticalLineTo(21f)
            moveTo(15.2f, 13f); horizontalLineTo(18.3f)
            curveTo(20.1f, 13f, 21f, 14f, 21f, 15f)
            curveTo(21f, 16.1f, 20.1f, 17f, 18.3f, 17f)
            horizontalLineTo(15.2f)
            moveTo(18.6f, 17f)
            curveTo(20.4f, 17f, 21.6f, 17.9f, 21.6f, 19f)
            curveTo(21.6f, 20.1f, 20.4f, 21f, 18.6f, 21f)
            horizontalLineTo(15.2f)
        }
    }
}
