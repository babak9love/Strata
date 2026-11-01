package dev.cluvex.strata.feature.explorer.components

import androidx.compose.foundation.background
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
fun VideoBadge(item: FileItem, modifier: Modifier = Modifier, compact: Boolean = false) = MediaBadge(item, Icons.Rounded.PlayArrow, modifier, compact)

/** The same badge on a music cover: a music note and the track's length, so a picture is read as sound. */
@Composable
fun AudioBadge(item: FileItem, modifier: Modifier = Modifier, compact: Boolean = false) = MediaBadge(item, Icons.Rounded.MusicNote, modifier, compact)

/** [compact]: the smaller badge for the 4-column grid, whose thumbnails are small. */
@Composable
private fun MediaBadge(item: FileItem, icon: ImageVector, modifier: Modifier, compact: Boolean) {
    val ms by produceState<Long?>(null, item.path, item.lastModified) {
        value = VideoDurations.of(item.path, item.lastModified)
    }
    val length = ms ?: return
    // A small rounded box, coloured like the path box above the list and the bookmark "box" style (not a capsule).
    val colors = MaterialTheme.colorScheme
    Row(
        modifier
            .clip(RoundedCornerShape(if (compact) 6.dp else 8.dp))
            .background(colors.surfaceContainerHigh.copy(alpha = 0.88f))
            .padding(
                start = if (compact) 2.dp else 4.dp,
                end = if (compact) 4.dp else 6.dp,
                top = if (compact) 1.dp else 3.dp,
                bottom = if (compact) 1.dp else 3.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.size(if (compact) 10.dp else 14.dp))
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
