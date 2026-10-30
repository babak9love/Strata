package dev.cluvex.strata.feature.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
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

/**
 * Repeat button drawn like the other tool buttons (same size of the filled square). A tap goes off, repeat one, repeat
 * all, and off again; it is "on" for both repeat modes.
 */
@Composable
fun RepeatToggleButton(
    player: androidx.media3.common.Player?,
    modifier: Modifier = Modifier,
    idleTint: Color,
    activeContainer: Color,
    activeTint: Color,
) {
    var mode by androidx.compose.runtime.remember(player) {
        androidx.compose.runtime.mutableIntStateOf(player?.repeatMode ?: androidx.media3.common.Player.REPEAT_MODE_OFF)
    }
    androidx.compose.runtime.DisposableEffect(player) {
        if (player == null) {
            onDispose { }
        } else {
            val listener = object : androidx.media3.common.Player.Listener {
                override fun onRepeatModeChanged(repeatMode: Int) {
                    mode = repeatMode
                }
            }
            mode = player.repeatMode
            player.addListener(listener)
            onDispose { player.removeListener(listener) }
        }
    }
    ActiveIconButton(
        active = mode != androidx.media3.common.Player.REPEAT_MODE_OFF,
        onClick = {
            player?.repeatMode = when (mode) {
                androidx.media3.common.Player.REPEAT_MODE_OFF -> androidx.media3.common.Player.REPEAT_MODE_ONE
                androidx.media3.common.Player.REPEAT_MODE_ONE -> androidx.media3.common.Player.REPEAT_MODE_ALL
                else -> androidx.media3.common.Player.REPEAT_MODE_OFF
            }
        },
        icon = if (mode == androidx.media3.common.Player.REPEAT_MODE_ONE) androidx.compose.material.icons.Icons.Rounded.RepeatOne else androidx.compose.material.icons.Icons.Rounded.Repeat,
        contentDescription = null,
        idleTint = idleTint,
        activeContainer = activeContainer,
        activeTint = activeTint,
        modifier = modifier,
        enabled = player != null,
    )
}

/** Shuffle button drawn like the other tool buttons: on while the playlist is shuffled. */
@Composable
fun ShuffleToggleButton(
    player: androidx.media3.common.Player?,
    modifier: Modifier = Modifier,
    idleTint: Color,
    activeContainer: Color,
    activeTint: Color,
) {
    var on by androidx.compose.runtime.remember(player) {
        androidx.compose.runtime.mutableStateOf(player?.shuffleModeEnabled ?: false)
    }
    androidx.compose.runtime.DisposableEffect(player) {
        if (player == null) {
            onDispose { }
        } else {
            val listener = object : androidx.media3.common.Player.Listener {
                override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                    on = shuffleModeEnabled
                }
            }
            on = player.shuffleModeEnabled
            player.addListener(listener)
            onDispose { player.removeListener(listener) }
        }
    }
    ActiveIconButton(
        active = on,
        onClick = { player?.shuffleModeEnabled = !on },
        icon = androidx.compose.material.icons.Icons.Rounded.Shuffle,
        contentDescription = null,
        idleTint = idleTint,
        activeContainer = activeContainer,
        activeTint = activeTint,
        modifier = modifier,
        enabled = player != null,
    )
}
