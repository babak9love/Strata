package dev.cluvex.strata.feature.media

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tap and hold gestures for the video area, split into three vertical zones (left, middle, right).
 *
 * - Short tap in the middle zone: [onMiddleTap] (show or hide the controls).
 * - Short tap in the left or right zone: [onSideTap] (show or hide the controls).
 * - Press and hold in the left or right zone: [onHoldStart] right away once the long-press time
 *   passes, and [onHoldEnd] when the finger lifts, the gesture is cancelled or the composable leaves.
 *
 * While holding, the touch is consumed so the brightness and volume swipe and the pan gesture
 * do not react to small finger movements.
 */
fun Modifier.videoTapGestures(
    onMiddleTap: State<() -> Unit>,
    onSideTap: State<() -> Unit>,
    onHoldStart: State<() -> Unit>,
    onHoldEnd: State<() -> Unit>,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val width = size.width
        val inMiddle = down.position.x >= width / 3f && down.position.x <= width * 2f / 3f
        val slop = viewConfiguration.touchSlop

        // 0 = still undecided, 1 = tap, 2 = something else (swipe, pinch, consumed by another handler)
        val outcome = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
            var result = 0
            while (result == 0) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id }
                result = when {
                    change == null || event.changes.any { it.isConsumed } -> 2
                    event.changes.count { it.pressed } > 1 -> 2
                    !change.pressed -> {
                        change.consume()
                        1
                    }
                    (change.position - down.position).getDistance() > slop -> 2
                    else -> 0
                }
            }
            result
        }

        when (outcome) {
            1 -> if (inMiddle) onMiddleTap.value() else onSideTap.value()
            null -> if (!inMiddle) {
                onHoldStart.value()
                try {
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                } finally {
                    onHoldEnd.value()
                }
            }
            else -> Unit
        }
    }
}
