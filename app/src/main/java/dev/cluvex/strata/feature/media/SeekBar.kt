package dev.cluvex.strata.feature.media

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import android.os.SystemClock
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
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Seek bar for the audio and video players. While the finger moves along the bar, the time under it is
 * shown live in the left-hand time label; the player seeks on release.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun SeekBar(
    player: Player?,
    modifier: Modifier = Modifier,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    inactiveColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    textColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    onSeekingChange: (Boolean) -> Unit = {},
    compact: Boolean = false,
    livePreview: Boolean = false,
) {
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    var trackWidth by remember { mutableIntStateOf(0) }
    // Live preview: while the finger is down the video is paused and follows it frame by frame.
    var wasPlaying by remember { mutableStateOf(false) }
    var lastSeekAt by remember { mutableLongStateOf(0L) }
    var savedParams by remember { mutableStateOf<SeekParameters?>(null) }

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
        // Times sit above the bar. Compact (video): the bar is inset 40dp on both sides and each time stands
        // right above its end of the bar. Otherwise (audio): times at the edges of the full-width bar.
        val inset = if (compact) 40.dp else 0.dp
        Row(Modifier.fillMaxWidth().padding(horizontal = inset).padding(bottom = 2.dp)) {
            val labelStyle = MaterialTheme.typography.labelLarge
            Text(formatTime(shownMs), color = textColor, style = labelStyle)
            Spacer(Modifier.weight(1f))
            Text(formatTime(duration), color = textColor, style = labelStyle)
        }
        // The Slider is taller than its track; in compact it is lifted a little so the times sit closer to the track.
        Box(
            Modifier.fillMaxWidth().padding(horizontal = inset)
                .offset(y = if (compact) (-4).dp else 0.dp)
                .onSizeChanged { trackWidth = it.width },
        ) {
            Slider(
                value = fraction,
                onValueChange = {
                    if (dragFraction == null) {
                        onSeekingChange(true)
                        if (livePreview && player != null) {
                            wasPlaying = player.playWhenReady
                            player.pause()
                            // Nearest keyframe is much faster than an exact seek, so the picture keeps up with the finger.
                            (player as? ExoPlayer)?.let { exo ->
                                savedParams = exo.seekParameters
                                exo.setSeekParameters(SeekParameters.CLOSEST_SYNC)
                            }
                        }
                    }
                    dragFraction = it
                    if (livePreview && player != null && duration > 0) {
                        val now = SystemClock.uptimeMillis()
                        if (now - lastSeekAt >= 80L) {
                            lastSeekAt = now
                            player.seekTo((it * duration).toLong())
                        }
                    }
                },
                onValueChangeFinished = {
                    if (livePreview) {
                        (player as? ExoPlayer)?.setSeekParameters(savedParams ?: SeekParameters.EXACT)
                        savedParams = null
                    }
                    dragFraction?.let { f -> player?.seekTo((f * duration).toLong()) }
                    if (livePreview && wasPlaying) player?.play()
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
