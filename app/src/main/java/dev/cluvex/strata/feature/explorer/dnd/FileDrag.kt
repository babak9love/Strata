package dev.cluvex.strata.feature.explorer.dnd

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.semantics
import dev.cluvex.strata.core.fs.DropKind
import dev.cluvex.strata.core.fs.DropRules
import dev.cluvex.strata.core.fs.DropSpot
import dev.cluvex.strata.core.model.FileItem
import dev.cluvex.strata.feature.explorer.Pane

class DragSession(
    val pane: Pane,
    val items: List<FileItem>,
    val primary: FileItem,
    val pointerId: PointerId,
    val origin: Rect,
)

class Landing(val point: Offset, val into: Boolean, val seq: Int)

class PaneScroller(val bounds: Rect, val owner: Any, val scrollBy: suspend (Float) -> Float)

@Stable
class FileDragState {
    var session by mutableStateOf<DragSession?>(null)
        private set
    var ghost by mutableStateOf<DragSession?>(null)
    var pointer by mutableStateOf(Offset.Zero)
        private set
    var hover by mutableStateOf<DropSpot?>(null)
        private set
    var landing by mutableStateOf<Landing?>(null)
    var gulp by mutableStateOf<Pair<String, Int>?>(null)
        private set
    private var seq by mutableIntStateOf(0)

    val spots = HashMap<String, DropSpot>()
    val anchors = HashMap<String, Offset>()
    val scrollers = HashMap<Int, PaneScroller>()

    var haptics: HapticFeedback? = null
    var onDrop: (DragSession, DropSpot) -> Unit = { _, _ -> }
    var onSpring: (DropSpot) -> Unit = {}

    val paths: Set<String> get() = session?.items?.mapTo(HashSet()) { it.path } ?: emptySet()

    fun begin(s: DragSession, at: Offset) {
        pointer = at
        hover = null
        landing = null
        session = s
        ghost = s
        haptics?.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
    }

    fun move(at: Offset) {
        pointer = at
        retarget()
    }

    fun retarget() {
        val s = session ?: return
        val spot = DropRules.pick(spots.values, pointer.x, pointer.y, s.items.map { it.path })
        if (spot?.key != hover?.key) {
            hover = spot
            if (spot != null) haptics?.performHapticFeedback(HapticFeedbackType.SegmentTick)
        }
    }

    fun anchorOf(spot: DropSpot): Offset = anchors[spot.key] ?: Offset((spot.left + spot.right) / 2f, (spot.top + spot.bottom) / 2f)

    fun end() {
        val s = session ?: return
        val spot = hover
        seq++
        if (spot != null) {
            val into = spot.kind != DropKind.PANE
            landing = Landing(if (into) anchorOf(spot) else Offset((spot.left + spot.right) / 2f, spot.top + 96f), into = true, seq = seq)
            if (into) gulp = spot.key to seq
            haptics?.performHapticFeedback(HapticFeedbackType.Confirm)
            session = null
            hover = null
            onDrop(s, spot)
        } else {
            landing = Landing(s.origin.center, into = false, seq = seq)
            haptics?.performHapticFeedback(HapticFeedbackType.GestureEnd)
            session = null
            hover = null
        }
    }

    fun cancel() {
        val s = session ?: return
        seq++
        landing = Landing(s.origin.center, into = false, seq = seq)
        session = null
        hover = null
    }

    fun spring(spot: DropSpot) {
        if (session == null || hover?.key != spot.key) return
        haptics?.performHapticFeedback(HapticFeedbackType.ToggleOn)
        hover = null
        onSpring(spot)
    }
}

val LocalFileDrag = staticCompositionLocalOf<FileDragState?> { null }

@Composable
fun Modifier.dropSpot(state: FileDragState?, key: String, dir: String, kind: DropKind, pane: Int): Modifier {
    if (state == null) return this
    val bounds = remember(state, key) { arrayOfNulls<Rect>(1) }
    SideEffect {
        bounds[0]?.let { b -> state.spots[key] = DropSpot(key, dir, kind, pane, b.left, b.top, b.right, b.bottom) }
    }
    DisposableEffect(state, key) {
        onDispose {
            state.spots.remove(key)
            state.anchors.remove(key)
        }
    }
    return onGloballyPositioned { c ->
        val b = c.boundsInRoot()
        bounds[0] = b
        state.spots[key] = DropSpot(key, dir, kind, pane, b.left, b.top, b.right, b.bottom)
    }
}

@Composable
fun Modifier.dropAnchor(state: FileDragState?, key: String?): Modifier {
    if (state == null || key == null) return this
    return onGloballyPositioned { c -> state.anchors[key] = c.boundsInRoot().center }
}

fun Modifier.dragHost(state: FileDragState, coordinates: () -> LayoutCoordinates?): Modifier = pointerInput(state) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val s = state.session
                if (s != null) {
                    val change = event.changes.firstOrNull { it.id == s.pointerId }
                    if (change == null || !change.pressed) {
                        event.changes.forEach { it.consume() }
                        state.end()
                        break
                    }
                    val root = coordinates()?.localToRoot(change.position) ?: change.position
                    state.move(root)
                    event.changes.forEach { it.consume() }
                } else if (event.changes.none { it.pressed }) {
                    break
                }
            }
        } finally {
            if (state.session != null) state.cancel()
        }
    }
}

@Composable
fun Modifier.fileGestures(
    interaction: MutableInteractionSource,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDragStart: ((Offset, PointerId) -> Boolean)?,
): Modifier {
    val click by rememberUpdatedState(onClick)
    val longClick by rememberUpdatedState(onLongClick)
    val dragStart by rememberUpdatedState(onDragStart)
    val haptics = LocalHapticFeedback.current
    val holder = remember { arrayOfNulls<LayoutCoordinates>(1) }
    return this
        .onGloballyPositioned { holder[0] = it }
        .indication(interaction, ripple())
        .semantics(mergeDescendants = true) {
            onClick { click(); true }
            onLongClick { longClick(); true }
        }
        .pointerInput(interaction) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val press = PressInteraction.Press(down.position)
                interaction.tryEmit(press)
                val slop = viewConfiguration.touchSlop
                var up: PointerInputChange? = null
                var cancelled = false
                val long = try {
                    withTimeout(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val event = awaitPointerEvent()
                            val c = event.changes.firstOrNull { it.id == down.id }
                            if (c == null) {
                                cancelled = true
                                break
                            }
                            if (c.changedToUp()) {
                                up = c
                                break
                            }
                            if (c.changedToUpIgnoreConsumed() || c.isConsumed || (c.position - down.position).getDistance() > slop) {
                                cancelled = true
                                break
                            }
                        }
                        false
                    }
                } catch (e: PointerEventTimeoutCancellationException) {
                    true
                }
                if (!long) {
                    val u = up
                    if (u != null && !cancelled) {
                        interaction.tryEmit(PressInteraction.Release(press))
                        u.consume()
                        click()
                    } else {
                        interaction.tryEmit(PressInteraction.Cancel(press))
                    }
                    return@awaitEachGesture
                }
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                while (true) {
                    val event = awaitPointerEvent()
                    val c = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (c.changedToUpIgnoreConsumed()) {
                        interaction.tryEmit(PressInteraction.Release(press))
                        if (!c.isConsumed) {
                            c.consume()
                            longClick()
                        }
                        break
                    }
                    if ((c.position - down.position).getDistance() > slop) {
                        val start = dragStart
                        val root = holder[0]?.localToRoot(c.position)
                        if (start != null && root != null && start(root, c.id)) {
                            interaction.tryEmit(PressInteraction.Cancel(press))
                            c.consume()
                            break
                        }
                    }
                    c.consume()
                }
            }
        }
}
