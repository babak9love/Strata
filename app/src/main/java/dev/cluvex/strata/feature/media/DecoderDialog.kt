package dev.cluvex.strata.feature.media

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.util.UnstableApi
import dev.cluvex.strata.R
import dev.cluvex.strata.ui.expressive.popupBorder
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderManager
import io.github.anilbeesetti.nextlib.media3ext.ffdecoder.DecoderMode

/**
 * Lets the user pick the video and the audio decoder separately, with the same four modes nextlib offers:
 * Auto (hardware, then software, then FFmpeg), hardware only, software only (MediaCodec) and FFmpeg only.
 */
@OptIn(UnstableApi::class)
@Composable
fun DecoderDialog(manager: DecoderManager, onDismiss: () -> Unit) {
    var video by remember { mutableStateOf(manager.videoMode) }
    var audio by remember { mutableStateOf(manager.audioMode) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.width(fixedDialogWidth()),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = PANEL_ALPHA),
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = popupBorder(),
        ) {
            Column(Modifier.padding(vertical = 12.dp)) {
                Row(Modifier.padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.media_decoder_title),
                        style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.common_close))
                    }
                }
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SectionTitle(stringResource(R.string.media_decoder_video))
                    DecoderMode.entries.forEach { mode ->
                        DecoderRow(modeLabel(mode), modeHint(mode), video == mode) {
                            manager.selectVideoDecoder(mode)
                            video = mode
                        }
                    }
                    SectionTitle(stringResource(R.string.media_decoder_audio))
                    DecoderMode.entries.forEach { mode ->
                        DecoderRow(modeLabel(mode), modeHint(mode), audio == mode) {
                            manager.selectAudioDecoder(mode)
                            audio = mode
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun modeLabel(mode: DecoderMode): String = stringResource(
    when (mode) {
        DecoderMode.AUTO -> R.string.media_decoder_auto
        DecoderMode.HARDWARE -> R.string.media_decoder_hw
        DecoderMode.SOFTWARE -> R.string.media_decoder_sw
        DecoderMode.FFMPEG -> R.string.media_decoder_ffmpeg
    },
)

@Composable
private fun modeHint(mode: DecoderMode): String = stringResource(
    when (mode) {
        DecoderMode.AUTO -> R.string.media_decoder_auto_hint
        DecoderMode.HARDWARE -> R.string.media_decoder_hw_hint
        DecoderMode.SOFTWARE -> R.string.media_decoder_sw_hint
        DecoderMode.FFMPEG -> R.string.media_decoder_ffmpeg_hint
    },
)

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun DecoderRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
