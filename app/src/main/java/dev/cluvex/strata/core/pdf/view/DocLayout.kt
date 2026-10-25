package dev.cluvex.strata.core.pdf.view

import kotlin.math.max
import kotlin.math.min

data class Viewport(val zoom: Float, val x: Float, val y: Float)

data class PagePoint(val page: Int, val x: Float, val y: Float)

class DocLayout(
    private val sizes: FloatArray,
    val viewportWidth: Float,
    val gap: Float,
    val margin: Float,
    val topInset: Float = 0f,
    val bottomInset: Float = 0f,
) {

    val pageCount: Int = sizes.size / 2
    private val maxWidth: Float = (0 until pageCount).maxOfOrNull { sizes[it * 2] }?.coerceAtLeast(1f) ?: 1f
    val scale: Float = ((viewportWidth - 2 * margin) / maxWidth).coerceAtLeast(0.01f)
    private val tops = FloatArray(pageCount)
    private val widths = FloatArray(pageCount)
    private val heights = FloatArray(pageCount)
    val contentHeight: Float

    init {
        var y = margin + topInset
        for (i in 0 until pageCount) {
            widths[i] = sizes[i * 2] * scale
            heights[i] = sizes[i * 2 + 1] * scale
            tops[i] = y
            y += heights[i] + gap
        }
        contentHeight = if (pageCount == 0) 0f else y - gap + margin + bottomInset
    }

    fun top(page: Int): Float = tops[page]
    fun width(page: Int): Float = widths[page]
    fun height(page: Int): Float = heights[page]
    fun left(page: Int): Float = (viewportWidth - widths[page]) / 2
    fun bottom(page: Int): Float = tops[page] + heights[page]

    fun pageAt(y: Float): Int {
        if (pageCount == 0) return 0
        var lo = 0
        var hi = pageCount - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (tops[mid] <= y) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun visible(top: Float, bottom: Float): IntRange {
        if (pageCount == 0) return IntRange.EMPTY
        var first = pageAt(top)
        if (bottom(first) < top && first + 1 < pageCount) first++
        var last = pageAt(bottom)
        if (tops[last] > bottom && last > first) last--
        return first..max(first, last)
    }

    fun pointAt(contentX: Float, contentY: Float): PagePoint? {
        if (pageCount == 0) return null
        val page = pageAt(contentY)
        val px = (contentX - left(page)) / scale
        val py = (contentY - tops[page]) / scale
        if (contentY > bottom(page) + gap / 2 || contentY < tops[page] - gap / 2) return null
        return PagePoint(page, px, py)
    }

    fun contentX(page: Int, pointX: Float): Float = left(page) + pointX * scale
    fun contentY(page: Int, pointY: Float): Float = tops[page] + pointY * scale

    fun clamp(v: Viewport, width: Float, height: Float, minZoom: Float = MIN_ZOOM, maxZoom: Float = MAX_ZOOM): Viewport {
        val zoom = v.zoom.coerceIn(minZoom, maxZoom)
        val contentW = viewportWidth * zoom
        val contentH = contentHeight * zoom
        val x = if (contentW <= width) (contentW - width) / 2 else v.x.coerceIn(0f, contentW - width)
        val y = if (contentH <= height) (contentH - height) / 2 else v.y.coerceIn(0f, contentH - height)
        return Viewport(zoom, x, y)
    }

    fun zoomAround(v: Viewport, zoom: Float, focusX: Float, focusY: Float): Viewport {
        val ratio = zoom / v.zoom
        return Viewport(zoom, (v.x + focusX) * ratio - focusX, (v.y + focusY) * ratio - focusY)
    }

    fun currentPage(v: Viewport, height: Float): Int = pageAt((v.y + topInset + (height - topInset - bottomInset) / 3f) / v.zoom)

    fun position(v: Viewport): Triple<Int, Float, Float> {
        val top = max(0f, (v.y + topInset) / v.zoom)
        var page = pageAt(top)
        if (top > bottom(page) && page + 1 < pageCount) page++
        val fraction = if (heights[page] > 0f) ((top - tops[page]) / heights[page]).coerceIn(-0.5f, 1f) else 0f
        return Triple(page, fraction, v.zoom)
    }

    fun viewportFor(page: Int, fraction: Float, zoom: Float, x: Float = 0f): Viewport {
        val p = page.coerceIn(0, max(0, pageCount - 1))
        val y = if (pageCount == 0) 0f else (tops[p] + fraction * heights[p]) * zoom - topInset
        return Viewport(zoom, x, y)
    }

    fun reveal(v: Viewport, width: Float, height: Float, page: Int, box: Box, margin: Float = 48f): Viewport {
        val l = contentX(page, box.left) * v.zoom
        val r = contentX(page, box.right) * v.zoom
        val t = contentY(page, box.top) * v.zoom
        val b = contentY(page, box.bottom) * v.zoom
        var x = v.x
        var y = v.y
        if (l < x + margin || r > x + width - margin) x = min(l - margin, (l + r) / 2 - width / 2)
        if (t < y + topInset + margin || b > y + height - bottomInset - margin) y = t - topInset - (height - topInset - bottomInset) / 3
        return Viewport(v.zoom, x, y)
    }

    companion object {
        const val MIN_ZOOM = 0.5f
        const val MAX_ZOOM = 12f
        val TILE_LEVELS = floatArrayOf(1.5f, 2f, 3f, 4f, 6f, 8f, 12f)

        fun tileLevel(zoom: Float): Float? {
            if (zoom <= 1.15f) return null
            for (l in TILE_LEVELS) if (l >= zoom - 0.01f) return l
            return TILE_LEVELS.last()
        }
    }
}
