package dev.cluvex.strata.feature.media

import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import dev.cluvex.strata.R
import java.util.Locale

/** Lists every audio stream of the playing video (plus "Disable") and plays the one the user picks. */
@Composable
fun AudioTracksDialog(player: Player, onDismiss: () -> Unit) {
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) { tick++ }
            override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) { tick++ }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    val groups = remember(tick) { player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO } }
    val disabled = remember(tick) { C.TRACK_TYPE_AUDIO in player.trackSelectionParameters.disabledTrackTypes }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.width(fixedDialogWidth()), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = PANEL_ALPHA), contentColor = MaterialTheme.colorScheme.onSurface) {
            Column(Modifier.padding(vertical = 12.dp)) {
                Row(Modifier.padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.media_audio_tracks), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.common_close)) }
                }
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    TrackRow(stringResource(R.string.media_audio_track_disable), null, disabled, true) {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                            .build()
                        onDismiss()
                    }
                    groups.forEachIndexed { i, group ->
                        val format = group.getTrackFormat(0)
                        // Prefer the stream's own name; fall back to "Track N" when it has none.
                        val name = format.label?.trim()?.takeIf { it.isNotEmpty() }
                        val details = listOfNotNull(
                            format.language?.takeIf { it.isNotBlank() && it != "und" }?.let { Locale.forLanguageTag(it).displayLanguage.takeIf { n -> n.isNotBlank() } },
                            format.channelCount.takeIf { it > 0 }?.let { "$it ch" },
                        ).distinct().joinToString(" · ").ifEmpty { null }
                        TrackRow(
                            name ?: stringResource(R.string.media_audio_track_n, i + 1),
                            details,
                            !disabled && group.isTrackSelected(0),
                            group.isTrackSupported(0),
                        ) {
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                                .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
                                .build()
                            onDismiss()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackRow(title: String, subtitle: String?, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.padding(start = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
