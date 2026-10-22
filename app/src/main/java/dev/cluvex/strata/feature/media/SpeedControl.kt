package dev.cluvex.strata.feature.media

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.SlowMotionVideo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.media3.common.Player
import dev.cluvex.strata.R
import kotlin.math.roundToInt

private const val MIN_SPEED = 0.5f
private const val MAX_SPEED = 2.0f
private const val STEP = 0.05f

/** Playback-speed icon button; tapping it opens a ruler-style speed dialog. Works for any [Player] (video or audio). */
@Composable
fun SpeedButton(player: Player?, tint: Color = LocalContentColor.current) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, enabled = player != null) {
        Icon(Icons.Rounded.SlowMotionVideo, contentDescription = stringResource(R.string.media_speed), tint = tint)
    }
    if (open && player != null) SpeedDialog(player) { open = false }
}

@Composable
private fun SpeedDialog(player: Player, onDismiss: () -> Unit) {
    var speed by remember { mutableFloatStateOf(player.playbackParameters.speed) }
    val apply: (Float) -> Unit = { v ->
        val s = v.coerceIn(MIN_SPEED, MAX_SPEED)
        speed = s
        player.setPlaybackSpeed(s)
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(start = 24.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.media_speed), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    Text("${(speed * 100).roundToInt() / 100f}x", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.common_close)) }
                }
                Column(Modifier.padding(end = 12.dp, top = 8.dp)) { SpeedRuler(speed, apply) }
                Row(Modifier.fillMaxWidth().padding(end = 12.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { apply(1f) }) { Text(stringResource(R.string.media_speed_reset)) }
                }
            }
        }
    }
}

@Composable
private fun SpeedRuler(speed: Float, onChange: (Float) -> Unit) {
    val current by rememberUpdatedState(onChange)
    val tick = MaterialTheme.colorScheme.outline
    val thumb = MaterialTheme.colorScheme.primary
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column {
            Canvas(
                Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .pointerInput(Unit) {
                        val pad = 6.dp.toPx()
                        fun at(x: Float) {
                            val f = ((x - pad) / (size.width - 2 * pad)).coerceIn(0f, 1f)
                            val v = MIN_SPEED + f * (MAX_SPEED - MIN_SPEED)
                            current((v / STEP).roundToInt() * STEP)
                        }
                        detectTapGestures { at(it.x) }
                    }
                    .pointerInput(Unit) {
                        val pad = 6.dp.toPx()
                        fun at(x: Float) {
                            val f = ((x - pad) / (size.width - 2 * pad)).coerceIn(0f, 1f)
                            val v = MIN_SPEED + f * (MAX_SPEED - MIN_SPEED)
                            current((v / STEP).roundToInt() * STEP)
                        }
                        detectHorizontalDragGestures(onDragStart = { at(it.x) }) { change, _ ->
                            change.consume()
                            at(change.position.x)
                        }
                    },
            ) {
                val pad = 6.dp.toPx()
                val w = size.width - 2 * pad
                for (i in 0..15) {
                    val x = pad + w * i / 15f
                    val major = i % 5 == 0
                    drawLine(tick, Offset(x, 0f), Offset(x, if (major) 28.dp.toPx() else 16.dp.toPx()), strokeWidth = 1.5.dp.toPx())
                }
                val tx = pad + w * ((speed - MIN_SPEED) / (MAX_SPEED - MIN_SPEED)).coerceIn(0f, 1f)
                drawRoundRect(thumb, Offset(tx - 2.dp.toPx(), 0f), Size(4.dp.toPx(), 36.dp.toPx()), CornerRadius(2.dp.toPx()))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("0.5", "1.0", "1.5", "2.0").forEach {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}
