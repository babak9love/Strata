package dev.cluvex.strata.feature.explorer.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.cluvex.strata.core.bookmarks.Bookmark
import dev.cluvex.strata.core.bookmarks.Bookmarks
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Index of the bookmark the current folder belongs to: the exact match, otherwise the bookmark whose path is
 * the closest parent of [path]. -1 when the folder is outside every bookmark.
 */
fun currentBookmarkIndex(bookmarks: List<Bookmark>, path: String): Int {
    val current = Bookmarks.normalize(path)
    var best = -1
    var bestLength = -1
    bookmarks.forEachIndexed { i, b ->
        val p = Bookmarks.normalize(b.path)
        val inside = p == current || current.startsWith(if (p == "/") p else "$p/")
        if (inside && p.length > bestLength) {
            best = i
            bestLength = p.length
        }
    }
    return best
}

/**
 * Swipe left or right to move to the next or previous bookmark, like paging through a gallery.
 * The content follows the finger; past about a quarter of the width (or on a quick flick) [onSwipe] is called
 * and the content settles back while the new folder slides in. When there is no bookmark to go to ([canPrevious]
 * or [canNext] is false) the content only gives a little and springs back.
 *
 * At the first bookmark a swipe towards the previous one has nothing to go to. When [passBackToDrawer] is on,
 * that swipe is left alone (not consumed) so the side menu can take it; otherwise it just gives a little.
 */
@Composable
fun Modifier.bookmarkSwipe(
    enabled: Boolean,
    canPrevious: Boolean,
    canNext: Boolean,
    passBackToDrawer: Boolean,
    onSwipe: (forward: Boolean) -> Unit,
): Modifier {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = LocalDensity.current
    val maxPull = with(density) { 48.dp.toPx() }
    val flick = with(density) { 400.dp.toPx() }
    val scope = rememberCoroutineScope()
    var width by remember { mutableFloatStateOf(1f) }
    var drag by remember { mutableFloatStateOf(0f) }
    var settle by remember { mutableStateOf<Job?>(null) }

    val isEnabled by rememberUpdatedState(enabled)
    val hasPrevious by rememberUpdatedState(canPrevious)
    val hasNext by rememberUpdatedState(canNext)
    val passBack by rememberUpdatedState(passBackToDrawer)
    val swipe by rememberUpdatedState(onSwipe)

    // Finger moving towards the start of the line (left in LTR) means the next bookmark.
    fun isForward(delta: Float) = if (rtl) delta > 0f else delta < 0f
    fun allowed(delta: Float) = if (isForward(delta)) hasNext else hasPrevious

    return this
        .onSizeChanged { width = it.width.toFloat().coerceAtLeast(1f) }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (!isEnabled) return@awaitEachGesture
                settle?.cancel()
                val tracker = VelocityTracker()
                tracker.addPosition(down.uptimeMillis, down.position)
                val first = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                    // Backwards at the first bookmark: leave it to the side menu when that is on.
                    val toDrawer = passBack && !isForward(over) && !hasPrevious
                    if (!toDrawer) {
                        change.consume()
                        drag += over
                    }
                } ?: return@awaitEachGesture
                tracker.addPosition(first.uptimeMillis, first.position)
                horizontalDrag(first.id) { change ->
                    drag += change.positionChange().x
                    tracker.addPosition(change.uptimeMillis, change.position)
                    change.consume()
                }
                val velocity = tracker.calculateVelocity().x
                val raw = drag
                val committed = when {
                    abs(raw) > width * 0.25f -> raw
                    abs(velocity) > flick && raw != 0f && (velocity > 0f) == (raw > 0f) -> raw
                    else -> 0f
                }
                if (committed != 0f && allowed(committed)) swipe(isForward(committed))
                settle = scope.launch {
                    animate(
                        initialValue = drag,
                        targetValue = 0f,
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    ) { value, _ -> drag = value }
                }
            }
        }
        .graphicsLayer {
            val d = drag
            translationX = if (d == 0f || allowed(d)) d else (d * 0.25f).coerceIn(-maxPull, maxPull)
        }
}
