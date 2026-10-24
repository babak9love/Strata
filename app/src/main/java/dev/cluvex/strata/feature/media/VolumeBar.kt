package dev.cluvex.strata.feature.media

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import dev.cluvex.strata.R
import kotlin.math.abs
import kotlin.math.roundToInt

/** Volume goes from 0 to 200%; 100% is the limit, anything above it is a boost. */
internal const val VOLUME_MAX_LEVEL = 2f

private val VolumeBlue = Color(0xFF3B82F6)
private val VolumeYellow = Color(0xFFF7C948)
private val VolumeRed = Color(0xFFFF5A4F)

/** Blue up to 100%, yellow at 150%, red at 200%: one gradient over the whole bar, from [bottom] up to [top]. */
internal fun volumeBrush(top: Float, bottom: Float): Brush = Brush.verticalGradient(
    colorStops = arrayOf(0f to VolumeRed, 0.25f to VolumeYellow, 0.5f to VolumeBlue, 1f to VolumeBlue),
    startY = top,
    endY = bottom,
)

/** The colour of the gradient at [level] (0 to 2). */
internal fun volumeColorAt(level: Float): Color = when {
    level <= 1f -> VolumeBlue
    level <= 1.5f -> lerp(VolumeBlue, VolumeYellow, (level - 1f) / 0.5f)
    else -> lerp(VolumeYellow, VolumeRed, ((level - 1.5f) / 0.5f).coerceAtMost(1f))
}

/**
 * The music player's own volume as a small vertical bar: percentage on top, the bar, and a speaker button
 * (mute / un-mute) below. It is independent of the phone's volume; above 100% the service boosts the sound.
 */
@Composable
internal fun VolumeBar(player: MediaController, onVolumeChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    // 0 to 1 is the player's volume; 1 to 2 is a boost done by the service.
    var boost by remember(player) { mutableFloatStateOf(player.sessionExtras.getFloat(PlaybackService.EXTRA_BOOST, 1f)) }
    var level by remember(player) { mutableFloatStateOf(if (boost > 1f && player.volume >= 1f) boost else player.volume) }
    // The last audible level, so un-muting goes back to it.
    var lastLevel by remember(player) { mutableFloatStateOf(if (level > 0f) level else 1f) }
    var dragging by remember(player) { mutableStateOf(false) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onVolumeChanged(volume: Float) {
                if (dragging) return
                level = if (boost > 1f && volume >= 1f) boost else volume
                if (level > 0f) lastLevel = level
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    fun apply(v: Float) {
        // A light snap to exactly 100%, the limit.
        val l = v.coerceIn(0f, VOLUME_MAX_LEVEL).let { if (abs(it - 1f) < 0.03f) 1f else it }
        level = l
        player.volume = minOf(l, 1f)
        if (l > 1f || boost > 1f) {
            boost = maxOf(l, 1f)
            PlaybackService.sendBoost(player, boost)
        }
        if (l > 0f) lastLevel = l
    }

    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val onSurface = MaterialTheme.colorScheme.onSurface
    Column(
        modifier.width(44.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text("${(level * 100).roundToInt()}%", fontSize = 11.sp, color = onSurface, maxLines = 1)
        Box(
            Modifier
                .width(32.dp)
                .height(112.dp)
                .pointerInput(player) {
                    val inset = 6.dp.toPx()
                    fun levelAt(y: Float): Float {
                        val top = inset
                        val bottom = size.height - inset
                        return ((bottom - y) / (bottom - top)).coerceIn(0f, 1f) * VOLUME_MAX_LEVEL
                    }
                    detectTapGestures(onTap = { p ->
                        apply(levelAt(p.y))
                        if (level > 0f) onVolumeChange(level)
                    })
                }
                .pointerInput(player) {
                    val inset = 6.dp.toPx()
                    fun levelAt(y: Float): Float {
                        val bottom = size.height - inset
                        return ((bottom - y) / (bottom - inset)).coerceIn(0f, 1f) * VOLUME_MAX_LEVEL
                    }
                    detectVerticalDragGestures(
                        onDragStart = { p ->
                            dragging = true
                            apply(levelAt(p.y))
                        },
                        onVerticalDrag = { change, _ -> apply(levelAt(change.position.y)) },
                        onDragEnd = {
                            dragging = false
                            if (level > 0f) onVolumeChange(level)
                        },
                        onDragCancel = { dragging = false },
                    )
                },
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val inset = 6.dp.toPx()
                val top = inset
                val bottom = size.height - inset
                val barW = 12.dp.toPx()
                val x0 = (w - barW) / 2f
                val frac = (level / VOLUME_MAX_LEVEL).coerceIn(0f, 1f)
                val yThumb = bottom - (bottom - top) * frac
                val gap = 5.dp.toPx()
                val corner = CornerRadius(barW / 2f)
                // The rest of the track above the thumb, then the coloured part below it, with a gap around the thumb.
                if (yThumb - gap > top) drawRoundRect(trackColor, Offset(x0, top), Size(barW, yThumb - gap - top), corner)
                if (bottom > yThumb + gap) {
                    drawRoundRect(volumeBrush(top, bottom), Offset(x0, yThumb + gap), Size(barW, bottom - (yThumb + gap)), corner)
                }
                val thumbW = 26.dp.toPx()
                val thumbH = 4.dp.toPx()
                drawRoundRect(volumeColorAt(level), Offset((w - thumbW) / 2f, yThumb - thumbH / 2f), Size(thumbW, thumbH), CornerRadius(thumbH / 2f))
            }
        }
        Icon(
            if (level <= 0f) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
            contentDescription = stringResource(R.string.media_volume),
            tint = onSurface,
            modifier = Modifier
                .size(28.dp)
                .clickable {
                    if (level > 0f) {
                        player.volume = 0f
                        level = 0f
                    } else {
                        apply(lastLevel)
                        onVolumeChange(lastLevel)
                    }
                },
        )
    }
}
