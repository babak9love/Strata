package dev.cluvex.strata.feature.media

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.unit.IntOffset
import androidx.media3.common.C
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Seek bar for the audio and video players. While the finger moves along the bar, the time under it is
 * shown live (in a bubble above the thumb and in the left-hand time label); the player seeks on release.
 */
@Composable
fun SeekBar(
    player: Player?,
    modifier: Modifier = Modifier,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    inactiveColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    textColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onSeekingChange: (Boolean) -> Unit = {},
) {
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var trackWidth by remember { mutableIntStateOf(0) }
    var bubbleWidth by remember { mutableIntStateOf(0) }
    var bubbleHeight by remember { mutableIntStateOf(0) }

    LaunchedEffect(player) {
        while (true) {
            if (player != null && dragFraction == null) {
                position = player.currentPosition.coerceAtLeast(0L)
                duration = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L
            }
            delay(200)
        }
    }

    val fraction = dragFraction ?: if (duration > 0) (position / duration.toFloat()).coerceIn(0f, 1f) else 0f
    val shownMs = if (dragFraction != null) (fraction * duration).toLong() else position
    val density = LocalDensity.current
    val gap = with(density) { 6.dp.roundToPx() }
    val thumbInset = with(density) { 10.dp.toPx() }

    Column(modifier) {
        Box(Modifier.fillMaxWidth().onSizeChanged { trackWidth = it.width }) {
            Slider(
                value = fraction,
                onValueChange = {
                    if (dragFraction == null) onSeekingChange(true)
                    dragFraction = it
                },
                onValueChangeFinished = {
                    dragFraction?.let { f -> player?.seekTo((f * duration).toLong()) }
                    position = (fraction * duration).toLong()
                    dragFraction = null
                    onSeekingChange(false)
                },
                enabled = player != null && duration > 0,
                colors = SliderDefaults.colors(
                    thumbColor = activeColor,
                    activeTrackColor = activeColor,
                    inactiveTrackColor = inactiveColor,
                    disabledThumbColor = activeColor.copy(alpha = 0.4f),
                    disabledActiveTrackColor = activeColor.copy(alpha = 0.4f),
                    disabledInactiveTrackColor = inactiveColor.copy(alpha = 0.4f),
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            if (dragFraction != null) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xE6202020),
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .onSizeChanged { bubbleWidth = it.width; bubbleHeight = it.height }
                        .offset {
                            val usable = (trackWidth - 2 * thumbInset).coerceAtLeast(0f)
                            val x = (thumbInset + fraction * usable - bubbleWidth / 2f).roundToInt()
                                .coerceIn(0, (trackWidth - bubbleWidth).coerceAtLeast(0))
                            IntOffset(x, -bubbleHeight - gap)
                        },
                ) {
                    Text(
                        formatTime(shownMs),
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            Text(formatTime(shownMs), color = textColor, style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.weight(1f))
            Text(formatTime(duration), color = textColor, style = MaterialTheme.typography.labelMedium)
        }
    }
}

internal fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%02d:%02d", m, s)
}
