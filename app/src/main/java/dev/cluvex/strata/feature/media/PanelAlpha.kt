package dev.cluvex.strata.feature.media

import androidx.compose.ui.unit.dp

/** The cards and dialogs of the players are theme colored and 95% opaque, so the video or cover shows faintly through. */
const val PANEL_ALPHA = 0.95f

/**
 * One width for the player's dialogs in both orientations: the narrow side of the screen minus the side margins, so a
 * dialog keeps the size it has upright when the phone is turned sideways.
 */
@androidx.compose.runtime.Composable
fun fixedDialogWidth(): androidx.compose.ui.unit.Dp {
    val c = androidx.compose.ui.platform.LocalConfiguration.current
    return (minOf(c.screenWidthDp, c.screenHeightDp) - 56).dp.coerceAtMost(560.dp)
}
