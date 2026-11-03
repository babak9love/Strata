package dev.cluvex.strata.feature.explorer.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.cluvex.strata.core.media.VideoDurations
import dev.cluvex.strata.core.model.FileItem

/** Small play icon and the video's length in a rounded box, like the Samsung gallery's badge. Draws nothing until the length is known (or if it cannot be read). */
@Composable
fun VideoBadge(item: FileItem, modifier: Modifier = Modifier, compact: Boolean = false) = MediaBadge(item, Icons.Rounded.PlayArrow, modifier, compact, dropIconWhenLong = false)

/**
 * The same badge on a music cover: a music note and the track's length, so a picture is read as sound.
 * An hour-long length drops the note and shows only the time, like the two-pane list's bar, so it still fits the cover.
 */
@Composable
fun AudioBadge(item: FileItem, modifier: Modifier = Modifier, compact: Boolean = false) = MediaBadge(item, Icons.Rounded.MusicNote, modifier, compact, dropIconWhenLong = true)

/** [compact]: the smaller badge for the 4-column grid, whose thumbnails are small. [dropIconWhenLong]: no icon when the length is an hour or more. */
@Composable
private fun MediaBadge(item: FileItem, icon: ImageVector, modifier: Modifier, compact: Boolean, dropIconWhenLong: Boolean) {
    val ms by produceState<Long?>(null, item.path, item.lastModified) {
        value = VideoDurations.of(item.path, item.lastModified)
    }
    val length = ms ?: return
    val showIcon = !(dropIconWhenLong && length >= 3_600_000L)
    // A small rounded box, coloured like the path box above the list and the bookmark "box" style (not a capsule).
    val colors = MaterialTheme.colorScheme
    Row(
        modifier
            .clip(RoundedCornerShape(if (compact) 6.dp else 8.dp))
            .background(colors.surfaceContainerHigh.copy(alpha = 0.88f))
            .padding(
                // Without the icon the time alone gets even padding on both sides.
                start = if (!showIcon) (if (compact) 4.dp else 6.dp) else if (compact) 2.dp else 4.dp,
                end = if (compact) 4.dp else 6.dp,
                top = if (compact) 1.dp else 3.dp,
                bottom = if (compact) 1.dp else 3.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showIcon) Icon(icon, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(if (compact) 10.dp else 14.dp))
        Text(
            VideoDurations.format(length),
            style = MaterialTheme.typography.labelSmall,
            fontSize = if (compact) 9.sp else androidx.compose.ui.unit.TextUnit.Unspecified,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** Only the icon, in the same small box, scaled with the thumbnail by [scale]: for list rows, whose thumbnails are too small for icon and time (the time is in the info line there). */
@Composable
fun MediaIconBadge(audio: Boolean, modifier: Modifier = Modifier, scale: Float = 1f) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp * scale))
            .background(colors.surfaceContainerHigh.copy(alpha = 0.88f))
            .padding(2.dp * scale),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (audio) Icons.Rounded.MusicNote else Icons.Rounded.PlayArrow,
            contentDescription = null,
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(12.dp * scale),
        )
    }
}

/**
 * Two panes, list rows: a bar across the bottom of the thumbnail, flush with its edges (the caller clips it to the thumbnail's shape).
 * An hour-long length drops the icon so the time still fits the narrow thumbnail, unless [dropIconWhenLong] is false (the icon always stays).
 * [scale]: icon, time and bar height grow / shrink with the thumbnail (the file icon size setting / 100).
 */
@Composable
fun MediaBadgeBar(item: FileItem, audio: Boolean, modifier: Modifier = Modifier, dropIconWhenLong: Boolean = true, scale: Float = 1f) {
    val ms by produceState<Long?>(null, item.path, item.lastModified) {
        value = VideoDurations.of(item.path, item.lastModified)
    }
    val length = ms ?: return
    val colors = MaterialTheme.colorScheme
    Row(
        modifier
            .heightIn(min = 14.dp * scale)
            .background(colors.surfaceContainerHigh.copy(alpha = 0.92f))
            .padding(vertical = 1.dp * scale),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!dropIconWhenLong || length < 3_600_000L) {
            Icon(
                if (audio) Icons.Rounded.MusicNote else Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = colors.onSurfaceVariant,
                modifier = Modifier.size(10.dp * scale),
            )
        }
        Text(
            VideoDurations.format(length),
            // labelSmall's 16sp line height is taller than a 9sp text: it pushed the digits down against the bottom edge. Centre the glyphs in a tight line.
            style = MaterialTheme.typography.labelSmall.copy(
                fontSize = 9.sp * scale,
                lineHeight = 11.sp * scale,
                lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                    alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                    trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.Both,
                ),
            ),
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
        )
    }
}
