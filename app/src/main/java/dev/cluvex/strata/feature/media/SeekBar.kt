package dev.cluvex.strata.feature.media

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Seek bar for the audio and video players. While the finger moves along the bar, the time under it is
 * shown live in the left-hand time label; the player seeks on release.
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

    Column(modifier) {
        // Times sit above the bar so they stay readable near the bottom edge of the screen.
        Row(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
            Text(formatTime(shownMs), color = textColor, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.weight(1f))
            Text(formatTime(duration), color = textColor, style = MaterialTheme.typography.labelLarge)
        }
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
