package dev.cluvex.strata.feature.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * A tool button that looks like the repeat button: a plain icon while it is off; while it is on the icon turns dark
 * (or light, in the music player) inside a filled rounded square.
 */
@Composable
fun ActiveIconButton(
    active: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String?,
    idleTint: Color,
    activeContainer: Color,
    activeTint: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    IconButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        Box(
            Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (active) activeContainer else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = contentDescription, tint = if (active) activeTint else idleTint)
        }
    }
}
