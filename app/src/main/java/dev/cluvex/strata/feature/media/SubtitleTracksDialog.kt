package dev.cluvex.strata.feature.media

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Slider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import dev.cluvex.strata.feature.color.ColorPickerDialog
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import dev.cluvex.strata.R
import java.util.Locale

/**
 * Lists every subtitle track of the playing video (embedded ones and files found next to it), plays the one the
 * user picks or turns subtitles off, loads a subtitle file from storage, and sets their size, position, text colour and background.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SubtitleTracksDialog(
    player: Player,
    style: SubtitleStyle,
    onStyleChange: (SubtitleStyle) -> Unit,
    externalOffsets: Map<String, Int>,
    onApplySync: (label: String, offsetMs: Int) -> Unit,
    onLoadFile: () -> Unit,
    onDismiss: () -> Unit,
) {
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) { tick++ }
            override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) { tick++ }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    val groups = remember(tick) { player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT } }
    val disabled = remember(tick) { C.TRACK_TYPE_TEXT in player.trackSelectionParameters.disabledTrackTypes }
    // Sync works on subtitle files; the selected track is one when its label is among them.
    val selectedLabel = if (disabled) null else groups.firstOrNull { it.isTrackSelected(0) }?.getTrackFormat(0)?.label
    val selectedOffset = selectedLabel?.let { externalOffsets[it] }
    val sizeNames = listOf(
        stringResource(R.string.media_subtitle_size_s),
        stringResource(R.string.media_subtitle_size_m),
        stringResource(R.string.media_subtitle_size_l),
        stringResource(R.string.media_subtitle_size_xl),
    )

    var pickText by remember { mutableStateOf(false) }
    var pickBackground by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(vertical = 12.dp)) {
                Row(Modifier.padding(start = 24.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.media_subtitles), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.common_close)) }
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    SubtitleRow(stringResource(R.string.media_subtitle_off), null, disabled || groups.none { it.isSelected }, true) {
                        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                            .build()
                        onDismiss()
                    }
                    groups.forEachIndexed { i, group ->
                        val format = group.getTrackFormat(0)
                        val name = format.label?.trim()?.takeIf { it.isNotEmpty() }
                        val language = format.language?.takeIf { it.isNotBlank() && it != "und" }
                            ?.let { Locale.forLanguageTag(it).displayLanguage.takeIf { n -> n.isNotBlank() } }
                        val kind = kindOf(format.sampleMimeType)
                        // Picture based subtitles (PGS, DVB, VobSub) can't be drawn by the overlay.
                        val drawable = !isImageBased(format.sampleMimeType)
                        SubtitleRow(
                            name ?: stringResource(R.string.media_subtitle_track_n, i + 1),
                            listOfNotNull(language, kind).distinct().joinToString(" · ").ifEmpty { null },
                            !disabled && group.isTrackSelected(0),
                            drawable && group.isTrackSupported(0),
                        ) {
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, 0))
                                .apply { format.language?.takeIf { it.isNotBlank() && it != "und" }?.let { setPreferredTextLanguage(it) } }
                                .build()
                            onDismiss()
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().clickable(onClick = onLoadFile).padding(horizontal = 24.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Rounded.FileOpen, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(stringResource(R.string.media_subtitle_load), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge)
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
                    if (selectedLabel != null && selectedOffset != null) {
                        SyncSection(selectedOffset) { ms ->
                            onApplySync(selectedLabel, ms)
                            onDismiss()
                        }
                        HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
                    }
                    Text(
                        stringResource(R.string.media_subtitle_size),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 4.dp),
                    )
                    FlowRow(
                        Modifier.padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        SUBTITLE_SCALES.forEachIndexed { i, scale ->
                            FilterChip(
                                selected = style.scale == scale,
                                onClick = { onStyleChange(style.copy(scale = scale)) },
                                label = { Text(sizeNames[i]) },
                            )
                        }
                    }
                    // Any size in between: the slider moves in 5% steps.
                    SliderRow(
                        valueText = "${(style.scale * 100).roundToInt()}%",
                        value = style.scale,
                        range = SUBTITLE_SCALE_MIN..SUBTITLE_SCALE_MAX,
                        steps = ((SUBTITLE_SCALE_MAX - SUBTITLE_SCALE_MIN) / 0.05f).roundToInt() - 1,
                        onChange = { onStyleChange(style.copy(scale = (it / 0.05f).roundToInt() * 0.05f)) },
                    )
                    Text(
                        stringResource(R.string.media_subtitle_position),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp),
                    )
                    Text(
                        stringResource(R.string.media_subtitle_position_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 2.dp, bottom = 4.dp),
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
                    ColorRow(stringResource(R.string.media_subtitle_text_color), style.textColor) { pickText = true }
                    Row(
                        Modifier.fillMaxWidth().clickable { onStyleChange(style.copy(background = !style.background)) }.padding(horizontal = 24.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(stringResource(R.string.media_subtitle_background), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Switch(checked = style.background, onCheckedChange = null)
                    }
                    if (style.background) {
                        ColorRow(stringResource(R.string.media_subtitle_background_color), style.backgroundColor) { pickBackground = true }
                    }
                    TextButton(
                        onClick = { onStyleChange(SubtitleStyle()) },
                        enabled = style != SubtitleStyle(),
                        modifier = Modifier.align(Alignment.End).padding(end = 12.dp),
                    ) { Text(stringResource(R.string.media_subtitle_style_reset)) }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End).padding(end = 12.dp)) {
                    Text(stringResource(R.string.common_close))
                }
            }
        }
    }
    if (pickText) {
        ColorPickerDialog(
            initial = style.textColor,
            title = stringResource(R.string.media_subtitle_text_color),
            onPick = { onStyleChange(style.copy(textColor = it)); pickText = false },
            onDismiss = { pickText = false },
        )
    }
    if (pickBackground) {
        ColorPickerDialog(
            initial = style.backgroundColor,
            title = stringResource(R.string.media_subtitle_background_color),
            onPick = { onStyleChange(style.copy(backgroundColor = it, background = true)); pickBackground = false },
            onDismiss = { pickBackground = false },
        )
    }
}

private fun isImageBased(mime: String?): Boolean =
    mime == MimeTypes.APPLICATION_PGS || mime == MimeTypes.APPLICATION_DVBSUBS || mime == MimeTypes.APPLICATION_VOBSUB

private fun kindOf(mime: String?): String? = when (mime) {
    MimeTypes.APPLICATION_SUBRIP -> "SRT"
    MimeTypes.TEXT_SSA -> "ASS"
    MimeTypes.TEXT_VTT -> "VTT"
    MimeTypes.APPLICATION_PGS -> "PGS"
    MimeTypes.APPLICATION_DVBSUBS -> "DVB"
    MimeTypes.APPLICATION_VOBSUB -> "VobSub"
    else -> null
}

@Composable
private fun SubtitleRow(title: String, subtitle: String?, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
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

private const val MAX_OFFSET_MS = 600_000
private const val STEP_FINE_MS = 100
private const val STEP_COARSE_MS = 1_000

/** Moves the subtitles earlier or later in steps; nothing is changed until Apply, because applying reloads the video. */
@Composable
internal fun SyncSection(
    initialMs: Int,
    title: String = stringResource(R.string.media_subtitle_sync),
    hint: String = stringResource(R.string.media_subtitle_sync_hint),
    maxMs: Int = MAX_OFFSET_MS,
    onApply: (Int) -> Unit,
) {
    var value by remember(initialMs) { mutableIntStateOf(initialMs) }
    fun move(by: Int) { value = (value + by).coerceIn(-maxMs, maxMs) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            hint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Numbers and signs always read left to right, even in the Persian layout.
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    String.format(Locale.US, "%+.1f s", value / 1000f),
                    style = MaterialTheme.typography.headlineSmall,
                    color = if (value != initialMs) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    StepButton("-1") { move(-STEP_COARSE_MS) }
                    StepButton("-0.1") { move(-STEP_FINE_MS) }
                    StepButton("+0.1") { move(STEP_FINE_MS) }
                    StepButton("+1") { move(STEP_COARSE_MS) }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { value = 0 }, enabled = value != 0) { Text(stringResource(R.string.media_subtitle_sync_reset)) }
            TextButton(onClick = { onApply(value) }, enabled = value != initialMs) { Text(stringResource(R.string.media_subtitle_sync_apply)) }
        }
    }
}

@Composable
private fun StepButton(text: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.height(40.dp).widthIn(min = 52.dp),
    ) {
        Box(Modifier.padding(horizontal = 8.dp), contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable
private fun SliderRow(valueText: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps.coerceAtLeast(0), modifier = Modifier.weight(1f))
        Text(
            valueText,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(start = 12.dp).widthIn(min = 52.dp),
            textAlign = TextAlign.End,
        )
    }
}

/** A line with a round swatch of the colour on the right; tapping it opens the colour picker. */
@Composable
private fun ColorRow(title: String, argb: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Box(
            Modifier
                .size(28.dp)
                .border(1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
                .padding(3.dp)
                .background(Color(argb), CircleShape),
        )
    }
}
