package dev.cluvex.strata.feature.shell

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.cluvex.strata.R
import dev.cluvex.strata.core.model.MotionStyle
import dev.cluvex.strata.ui.theme.LocalAppearance
import kotlin.math.abs
import kotlin.math.roundToInt

/** How far (as a share of the row's width) a swipe has to go before it counts. */
private const val TRIGGER_FRACTION = 0.3f

/** The row can't be pulled further than this share of its width. */
private const val MAX_FRACTION = 0.5f

/**
 * A row that can be swiped sideways, like a mail in Gmail: to the right it shows a red Delete background, to the left
 * an Edit one. Letting go past the trigger distance runs the action; the row always springs back, so the action
 * itself decides what happens (here: a confirmation, or the editor). Directions are physical, also in right-to-left
 * languages.
 */
@Composable
internal fun SwipeActionRow(
    enabled: Boolean,
    shape: Shape,
    onSwipeRight: () -> Unit,
    onSwipeLeft: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val reduced = LocalAppearance.current.motion == MotionStyle.REDUCED
    val right by rememberUpdatedState(onSwipeRight)
    val left by rememberUpdatedState(onSwipeLeft)
    var width by remember { mutableFloatStateOf(0f) }
    var pull by remember { mutableFloatStateOf(0f) }
    val trigger = width * TRIGGER_FRACTION
    val armed = trigger > 0f && abs(pull) >= trigger
    // One short buzz when the swipe becomes long enough.
    LaunchedEffect(armed) { if (armed) haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
    LaunchedEffect(enabled) { if (!enabled) pull = 0f }
    val iconScale by animateFloatAsState(if (armed) 1.25f else 0.9f, label = "swipeIcon")
    val drag = rememberDraggableState { delta ->
        val limit = width * MAX_FRACTION
        pull = (pull + delta).coerceIn(-limit, limit)
    }

    Box(modifier.fillMaxWidth().onSizeChanged { width = it.width.toFloat() }) {
        if (pull != 0f) {
            val toRight = pull > 0f
            val background = when {
                toRight && armed -> colors.error
                toRight -> colors.errorContainer
                armed -> colors.primary
                else -> colors.primaryContainer
            }
            val tint = when {
                toRight && armed -> colors.onError
                toRight -> colors.onErrorContainer
                armed -> colors.onPrimary
                else -> colors.onPrimaryContainer
            }
            // The background is revealed on the side the row moved away from.
            val side = if (toRight != rtl) Alignment.CenterStart else Alignment.CenterEnd
            Box(Modifier.matchParentSize().clip(shape).background(background), contentAlignment = side) {
                Icon(
                    if (toRight) Icons.Rounded.Delete else Icons.Rounded.Edit,
                    contentDescription = stringResource(if (toRight) R.string.common_delete else R.string.common_edit),
                    tint = tint,
                    modifier = Modifier.padding(horizontal = 28.dp).size(24.dp).scale(iconScale),
                )
            }
        }
        Box(
            Modifier
                .absoluteOffset { IntOffset(pull.roundToInt(), 0) }
                .draggable(
                    state = drag,
                    orientation = Orientation.Horizontal,
                    enabled = enabled,
                    onDragStopped = {
                        val fired = when {
                            pull >= trigger && trigger > 0f -> right
                            pull <= -trigger && trigger > 0f -> left
                            else -> null
                        }
                        fired?.invoke()
                        animate(pull, 0f, animationSpec = if (reduced) snap() else spring(stiffness = 500f)) { value, _ -> pull = value }
                    },
                ),
        ) {
            content()
        }
    }
}
