package dev.cluvex.strata.feature.explorer.components

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * Two-pane mode with "swipe to open the menu" on: a swipe towards the menu side (right in LTR, left in RTL), made
 * in the pane that is not next to the menu, hands focus to the pane that is. Only a further swipe, made while that
 * pane is already active, opens the menu (see gesturesEnabled in AppShell).
 */
@Composable
fun Modifier.swipeToMenuSidePane(enabled: Boolean, onSwipe: () -> Unit): Modifier {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val threshold = with(LocalDensity.current) { 56.dp.toPx() }
    val action by rememberUpdatedState(onSwipe)
    // Keyed on enabled: while it is off the block installs no detector at all, so horizontal drags are not consumed here
    // and reach the side menu's own swipe.
    return pointerInput(rtl, threshold, enabled) {
        if (!enabled) return@pointerInput
        var total = 0f
        var fired = false
        detectHorizontalDragGestures(
            onDragStart = { total = 0f; fired = false },
            onHorizontalDrag = { _, dx ->
                if (!fired) {
                    total += if (rtl) -dx else dx
                    if (total > threshold) {
                        fired = true
                        action()
                    }
                }
            },
        )
    }
}
