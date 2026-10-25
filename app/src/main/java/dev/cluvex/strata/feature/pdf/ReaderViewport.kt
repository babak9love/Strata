package dev.cluvex.strata.feature.pdf

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import dev.cluvex.strata.core.model.ReaderTheme
import dev.cluvex.strata.core.pdf.view.Box
import dev.cluvex.strata.core.pdf.view.DocLayout
import dev.cluvex.strata.core.pdf.view.PagePoint
import dev.cluvex.strata.core.pdf.view.Viewport
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Stable
class ReaderViewportState(private val sizes: FloatArray) {
    var viewport by mutableStateOf(Viewport(1f, 0f, 0f))
        private set
    var size by mutableStateOf(IntSize.Zero)
        private set
    var layout by mutableStateOf<DocLayout?>(null)
        private set
    var moving by mutableStateOf(false)
    var settledZoom by mutableStateOf(1f)
        private set
    private var pending: Triple<Int, Float, Float>? = null

    val pageCount: Int get() = sizes.size / 2

    fun resize(width: Int, height: Int, gap: Float, margin: Float, top: Float, bottom: Float) {
        if (width <= 0 || height <= 0) return
        val current = layout
        if (size.width == width && size.height == height && current != null && current.topInset == top && current.bottomInset == bottom) return
        val keep = layout?.position(viewport)
        size = IntSize(width, height)
        val next = DocLayout(sizes, width.toFloat(), gap, margin, top, bottom)
        layout = next
        val target = pending ?: keep
        pending = null
        viewport = if (target != null) next.clamp(next.viewportFor(target.first, target.second, target.third), width.toFloat(), height.toFloat())
        else next.clamp(Viewport(1f, 0f, 0f), width.toFloat(), height.toFloat())
        settledZoom = viewport.zoom
    }

    fun restore(page: Int, fraction: Float, zoom: Float) {
        val l = layout
        if (l == null) {
            pending = Triple(page, fraction, zoom)
        } else {
            set(l.viewportFor(page, fraction, zoom))
            settledZoom = viewport.zoom
        }
    }

    fun set(v: Viewport) {
        val l = layout ?: return
        viewport = l.clamp(v, size.width.toFloat(), size.height.toFloat())
    }

    fun panBy(dx: Float, dy: Float) {
        val v = viewport
        set(Viewport(v.zoom, v.x - dx, v.y - dy))
    }

    fun zoomBy(factor: Float, fx: Float, fy: Float) {
        val l = layout ?: return
        val v = viewport
        val zoom = (v.zoom * factor).coerceIn(DocLayout.MIN_ZOOM, DocLayout.MAX_ZOOM)
        set(l.zoomAround(v, zoom, fx, fy))
    }

    fun settle() {
        settledZoom = viewport.zoom
    }

    suspend fun fling(velocity: Offset, decay: DecayAnimationSpec<Offset>) {
        moving = true
        try {
            var last = Offset.Zero
            AnimationState(Offset.VectorConverter, Offset.Zero, velocity).animateDecay(decay) {
                val delta = value - last
                last = value
                val before = viewport
                panBy(delta.x, delta.y)
                if (viewport == before) cancelAnimation()
            }
        } finally {
            moving = false
        }
    }

    suspend fun animateTo(target: Viewport) {
        val l = layout ?: return
        val clamped = l.clamp(target, size.width.toFloat(), size.height.toFloat())
        val start = viewport
        moving = true
        try {
            AnimationState(0f).animateTo(1f, spring(dampingRatio = 0.9f, stiffness = 450f)) {
                val t = value
                viewport = Viewport(start.zoom + (clamped.zoom - start.zoom) * t, start.x + (clamped.x - start.x) * t, start.y + (clamped.y - start.y) * t)
            }
            viewport = clamped
        } finally {
            moving = false
            settledZoom = viewport.zoom
        }
    }

    fun toPage(screen: Offset): PagePoint? {
        val l = layout ?: return null
        val v = viewport
        return l.pointAt((screen.x + v.x) / v.zoom, (screen.y + v.y) / v.zoom)
    }

    fun toScreen(page: Int, x: Float, y: Float): Offset {
        val l = layout ?: return Offset.Zero
        val v = viewport
        return Offset(l.contentX(page, x) * v.zoom - v.x, l.contentY(page, y) * v.zoom - v.y)
    }

    fun boxOnScreen(page: Int, box: Box): Pair<Offset, Offset> = toScreen(page, box.left, box.top) to toScreen(page, box.right, box.bottom)

    fun currentPage(): Int = layout?.currentPage(viewport, size.height.toFloat()) ?: 0

    fun visiblePages(): IntRange {
        val l = layout ?: return IntRange.EMPTY
        val v = viewport
        return l.visible(max(0f, v.y / v.zoom), (v.y + size.height) / v.zoom)
    }

    fun position(): Triple<Int, Float, Float>? = layout?.position(viewport)

    fun target(page: Int, y: Float?, keepZoom: Boolean = true): Viewport? {
        val l = layout ?: return null
        val v = viewport
        val zoom = if (keepZoom) v.zoom else 1f
        val contentY = if (y != null) l.contentY(page, y) * zoom - l.topInset - 24f else l.top(page) * zoom - l.topInset - l.gap / 2
        return Viewport(zoom, v.x, contentY)
    }

    fun wanted(images: PageImages, includeTiles: Boolean): List<ImageKey> {
        val l = layout ?: return emptyList()
        val v = viewport
        val range = visiblePages()
        if (range.isEmpty()) return emptyList()
        val center = (v.y + size.height / 2f) / v.zoom
        val visible = range.sortedBy { abs((l.top(it) + l.height(it) / 2) - center) }
        val shrink = v.zoom < 0.75f
        fun whole(p: Int): WholeKey {
            val base = l.width(p).roundToInt().coerceAtLeast(1)
            return WholeKey(p, if (shrink) (base / 2).coerceAtLeast(1) else base)
        }
        val keys = ArrayList<ImageKey>()
        visible.forEach { keys += whole(it) }
        val level = if (includeTiles) DocLayout.tileLevel(v.zoom) else null
        if (level != null) {
            val tiles = ArrayList<Pair<Float, TileKey>>()
            for (p in visible) {
                val base = l.width(p).roundToInt().coerceAtLeast(1)
                val scale = level / v.zoom
                val left = l.left(p) * v.zoom - v.x
                val top = l.top(p) * v.zoom - v.y
                val x0 = max(0f, -left) * scale
                val y0 = max(0f, -top) * scale
                val x1 = (min(size.width.toFloat(), left + l.width(p) * v.zoom) - left) * scale
                val y1 = (min(size.height.toFloat(), top + l.height(p) * v.zoom) - top) * scale
                if (x1 <= x0 || y1 <= y0) continue
                val tile = PageImages.TILE.toFloat()
                for (row in floor(y0 / tile).toInt()..floor((y1 - 0.5f) / tile).toInt()) {
                    for (col in floor(x0 / tile).toInt()..floor((x1 - 0.5f) / tile).toInt()) {
                        val cx = left + (col + 0.5f) * tile / scale - size.width / 2f
                        val cy = top + (row + 0.5f) * tile / scale - size.height / 2f
                        tiles += (cx * cx + cy * cy) to TileKey(p, base, level, col, row)
                    }
                }
            }
            tiles.sortBy { it.first }
            tiles.forEach { keys += it.second }
        }
        val after = (range.last + 1..min(pageCount - 1, range.last + 2))
        val before = (max(0, range.first - 1) until range.first)
        after.forEach { keys += whole(it) }
        before.forEach { keys += whole(it) }
        return keys
    }

    companion object {
        val NIGHT = ColorFilter.colorMatrix(
            ColorMatrix(
                floatArrayOf(
                    0.437f, -1.088f, -0.110f, 0f, 224f,
                    -0.324f, -0.327f, -0.110f, 0f, 224f,
                    -0.324f, -1.088f, 0.651f, 0f, 224f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
        val SEPIA = ColorFilter.colorMatrix(
            ColorMatrix(
                floatArrayOf(
                    0.957f, 0f, 0f, 0f, 0f,
                    0f, 0.925f, 0f, 0f, 0f,
                    0f, 0f, 0.847f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )

        fun filter(theme: ReaderTheme): ColorFilter? = when (theme) {
            ReaderTheme.PAPER -> null
            ReaderTheme.SEPIA -> SEPIA
            ReaderTheme.NIGHT -> NIGHT
        }

        fun paper(theme: ReaderTheme): Color = when (theme) {
            ReaderTheme.PAPER -> Color.White
            ReaderTheme.SEPIA -> Color(0xFFF4ECD8)
            ReaderTheme.NIGHT -> Color(0xFF1E1E1E)
        }
    }
}

class ReaderHighlights(
    val all: Map<Int, List<Box>> = emptyMap(),
    val current: Pair<Int, List<Box>>? = null,
    val selection: Pair<Int, List<Box>>? = null,
    val flash: Pair<Int, Box>? = null,
)

fun DrawScope.drawPages(
    state: ReaderViewportState,
    images: PageImages,
    theme: ReaderTheme,
    shadow: Color,
    highlights: ReaderHighlights,
    hitColor: Color,
    currentColor: Color,
    selectionColor: Color,
) {
    val l = state.layout ?: return
    val v = state.viewport
    val filter = ReaderViewportState.filter(theme)
    val paper = ReaderViewportState.paper(theme)
    val level = DocLayout.tileLevel(state.settledZoom)
    for (p in state.visiblePages()) {
        val left = l.left(p) * v.zoom - v.x
        val top = l.top(p) * v.zoom - v.y
        val right = left + l.width(p) * v.zoom
        val bottom = top + l.height(p) * v.zoom
        val x0 = left.roundToInt()
        val y0 = top.roundToInt()
        val x1 = right.roundToInt()
        val y1 = bottom.roundToInt()
        drawRect(shadow, Offset(x0.toFloat() - 1f, y0.toFloat() - 1f), androidx.compose.ui.geometry.Size((x1 - x0 + 2).toFloat(), (y1 - y0 + 2).toFloat()))
        drawRect(paper, Offset(x0.toFloat(), y0.toFloat()), androidx.compose.ui.geometry.Size((x1 - x0).toFloat(), (y1 - y0).toFloat()))
        val base = l.width(p).roundToInt().coerceAtLeast(1)
        val whole = images.get(WholeKey(p, base)) ?: images.get(WholeKey(p, (base / 2).coerceAtLeast(1))) ?: images.bestWhole(p)
        if (whole != null) {
            drawImage(whole, dstOffset = IntOffset(x0, y0), dstSize = IntSize(x1 - x0, y1 - y0), filterQuality = FilterQuality.Medium, colorFilter = filter)
        }
        if (level != null && abs(state.settledZoom - v.zoom) < 0.001f) {
            val scale = v.zoom / level
            val tile = PageImages.TILE
            val cols = ((base * level) / tile).toInt() + 1
            val rows = ((base * level * l.height(p) / l.width(p)) / tile).toInt() + 1
            val c0 = max(0, floor(((0f - left) / scale) / tile).toInt())
            val c1 = min(cols - 1, floor(((size.width - left) / scale) / tile).toInt())
            val r0 = max(0, floor(((0f - top) / scale) / tile).toInt())
            val r1 = min(rows - 1, floor(((size.height - top) / scale) / tile).toInt())
            for (row in r0..r1) {
                for (col in c0..c1) {
                    val img = images.get(TileKey(p, base, level, col, row)) ?: continue
                    val tx0 = (left + col * tile * scale).roundToInt()
                    val ty0 = (top + row * tile * scale).roundToInt()
                    val tx1 = (left + (col * tile + img.width) * scale).roundToInt()
                    val ty1 = (top + (row * tile + img.height) * scale).roundToInt()
                    drawImage(img, dstOffset = IntOffset(tx0, ty0), dstSize = IntSize(tx1 - tx0, ty1 - ty0), filterQuality = FilterQuality.Low, colorFilter = filter)
                }
            }
        }
        highlights.all[p]?.forEach { box -> fillBox(state, p, box, hitColor) }
        highlights.current?.takeIf { it.first == p }?.second?.forEach { box -> fillBox(state, p, box, currentColor) }
        highlights.selection?.takeIf { it.first == p }?.second?.forEach { box -> fillBox(state, p, box, selectionColor) }
        highlights.flash?.takeIf { it.first == p }?.let { fillBox(state, p, it.second, selectionColor) }
    }
}

private fun DrawScope.fillBox(state: ReaderViewportState, page: Int, box: Box, color: Color) {
    val (a, b) = state.boxOnScreen(page, box)
    drawRect(color, a, androidx.compose.ui.geometry.Size(b.x - a.x, b.y - a.y))
}

interface ReaderGestures {
    fun onTap(position: Offset)
    fun onDoubleTap(position: Offset)
    fun onLongPress(position: Offset)
    fun onSelectionDrag(position: Offset)
    fun onSelectionEnd()
    fun handleAt(position: Offset): Int
    fun onHandleDrag(handle: Int, position: Offset)
    fun onGestureStart()
    fun onGestureEnd(velocity: Offset, flung: Boolean)
}

fun Modifier.readerGestures(state: ReaderViewportState, callbacks: ReaderGestures): Modifier = pointerInput(state, callbacks) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        callbacks.onGestureStart()
        val handle = callbacks.handleAt(down.position)
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        val slop = viewConfiguration.touchSlop
        var panned = false
        var zoomed = false
        var selecting = false
        var travel = Offset.Zero
        var lastPosition = down.position
        var lastTime = down.uptimeMillis
        val longPressAt = down.uptimeMillis + viewConfiguration.longPressTimeoutMillis
        while (true) {
            val waiting = handle == 0 && !panned && !zoomed && !selecting
            val event = if (waiting) {
                val remaining = longPressAt - lastTime
                if (remaining <= 0) null else withTimeoutOrNull(remaining) { awaitPointerEvent() }
            } else {
                awaitPointerEvent()
            }
            if (event == null) {
                selecting = true
                callbacks.onLongPress(lastPosition)
                continue
            }
            val primary = event.changes.first()
            lastTime = primary.uptimeMillis
            val pressed = event.changes.filter { it.pressed }
            if (pressed.isEmpty()) break
            lastPosition = primary.position
            if (pressed.size == 1) tracker.addPosition(primary.uptimeMillis, primary.position)
            when {
                handle != 0 -> {
                    callbacks.onHandleDrag(handle, primary.position)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
                selecting -> {
                    callbacks.onSelectionDrag(primary.position)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
                else -> {
                    val zoom = event.calculateZoom()
                    val pan = event.calculatePan()
                    val centroid = event.calculateCentroid(useCurrent = true)
                    if (pressed.size > 1) zoomed = true
                    if (!panned) {
                        travel += pan
                        if (travel.getDistance() > slop) panned = true
                    }
                    if (panned || zoomed) {
                        if (pressed.size > 1 && zoom != 1f) state.zoomBy(zoom, centroid.x, centroid.y)
                        state.panBy(pan.x, pan.y)
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                }
            }
        }
        when {
            handle != 0 || selecting -> callbacks.onSelectionEnd()
            panned || zoomed -> {
                val v = tracker.calculateVelocity()
                callbacks.onGestureEnd(Offset(v.x, v.y), !zoomed)
            }
            else -> {
                val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) { awaitFirstDown(requireUnconsumed = false) }
                if (second == null) {
                    callbacks.onTap(down.position)
                } else {
                    while (awaitPointerEvent(PointerEventPass.Main).changes.any { it.pressed }) Unit
                    callbacks.onDoubleTap(second.position)
                }
                callbacks.onGestureEnd(Offset.Zero, false)
            }
        }
    }
}
