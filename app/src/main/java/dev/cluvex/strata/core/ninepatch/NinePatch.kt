package dev.cluvex.strata.core.ninepatch

import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class Insets(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    companion object {
        val ZERO = Insets(0, 0, 0, 0)
    }
}

class NinePatchException(message: String, val x: Int = -1, val y: Int = -1) : IllegalArgumentException(message)

class NinePatch(
    val width: Int,
    val height: Int,
    val pixels: IntArray,
    val xDivs: IntArray,
    val yDivs: IntArray,
    val padding: Insets,
    val explicitPadding: Boolean = true,
    val layoutBounds: Insets? = null,
) {
    init {
        require(width > 0 && height > 0 && pixels.size == width * height) { "Bad picture size" }
        checkDivs(xDivs, width, horizontal = true)
        checkDivs(yDivs, height, horizontal = false)
    }

    fun copy(
        xDivs: IntArray = this.xDivs,
        yDivs: IntArray = this.yDivs,
        padding: Insets = this.padding,
        explicitPadding: Boolean = this.explicitPadding,
        layoutBounds: Insets? = this.layoutBounds,
    ) = NinePatch(width, height, pixels, xDivs, yDivs, padding, explicitPadding, layoutBounds)

    fun sameAs(other: NinePatch): Boolean =
        width == other.width && height == other.height && xDivs.contentEquals(other.xDivs) && yDivs.contentEquals(other.yDivs) &&
            padding == other.padding && explicitPadding == other.explicitPadding && layoutBounds == other.layoutBounds &&
            (pixels === other.pixels || pixels.contentEquals(other.pixels))

    fun toSource(): IntArray {
        val w = width + 2
        val h = height + 2
        val out = IntArray(w * h)
        for (y in 0 until height) System.arraycopy(pixels, y * width, out, (y + 1) * w + 1, width)
        fun top(x: Int) = x + 1
        fun bottomRow(x: Int) = (h - 1) * w + x + 1
        fun left(y: Int) = (y + 1) * w
        fun rightCol(y: Int) = (y + 1) * w + w - 1
        for (i in xDivs.indices step 2) for (x in xDivs[i] until xDivs[i + 1]) out[top(x)] = BLACK
        for (i in yDivs.indices step 2) for (y in yDivs[i] until yDivs[i + 1]) out[left(y)] = BLACK
        layoutBounds?.let { lb ->
            for (x in 0 until lb.left) out[bottomRow(x)] = RED
            for (x in width - lb.right until width) out[bottomRow(x)] = RED
            for (y in 0 until lb.top) out[rightCol(y)] = RED
            for (y in height - lb.bottom until height) out[rightCol(y)] = RED
        }
        if (explicitPadding) {
            for (x in padding.left until width - padding.right) out[bottomRow(x)] = BLACK
            for (y in padding.top until height - padding.bottom) out[rightCol(y)] = BLACK
        }
        return out
    }

    fun npTc(): ByteArray {
        val colors = regionColors()
        val nx = xDivs.size
        val ny = yDivs.size
        val nc = colors.size
        require(nx <= 255 && ny <= 255 && nc <= 255) { tr(R.string.ninepatch_too_many_regions) }
        val buf = ByteBuffer.allocate(32 + 4 * (nx + ny + nc))
        buf.put(0).put(nx.toByte()).put(ny.toByte()).put(nc.toByte())
        buf.order(ByteOrder.LITTLE_ENDIAN).putInt(32).putInt(32 + 4 * nx)
        buf.order(ByteOrder.BIG_ENDIAN).putInt(padding.left).putInt(padding.right).putInt(padding.top).putInt(padding.bottom)
        buf.order(ByteOrder.LITTLE_ENDIAN).putInt(32 + 4 * (nx + ny))
        buf.order(ByteOrder.BIG_ENDIAN)
        xDivs.forEach { buf.putInt(it) }
        yDivs.forEach { buf.putInt(it) }
        colors.forEach { buf.putInt(it) }
        return buf.array()
    }

    fun npLb(): ByteArray? = layoutBounds?.takeIf { it != Insets.ZERO }?.let { lb ->
        ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putInt(lb.left).putInt(lb.top).putInt(lb.right).putInt(lb.bottom).array()
    }

    internal fun regionColors(): IntArray {
        val xs = segments(xDivs, width)
        val ys = segments(yDivs, height)
        val out = ArrayList<Int>()
        for ((y0, y1) in ys) for ((x0, x1) in xs) {
            var color = pixels[y0 * width + x0]
            var uniform = true
            loop@ for (y in y0 until y1) for (x in x0 until x1) {
                val p = pixels[y * width + x]
                if (p != color && !(alpha(p) == 0 && alpha(color) == 0)) { uniform = false; break@loop }
            }
            if (uniform && alpha(color) == 0) color = TRANSPARENT_COLOR
            out += if (uniform) color else NO_COLOR
        }
        return out.toIntArray()
    }

    data class Piece(val srcLeft: Int, val srcTop: Int, val srcRight: Int, val srcBottom: Int, val left: Float, val top: Float, val right: Float, val bottom: Float)

    fun layout(targetW: Float, targetH: Float): List<Piece> {
        val xs = axis(segments(xDivs, width), xDivs, targetW)
        val ys = axis(segments(yDivs, height), yDivs, targetH)
        val out = ArrayList<Piece>()
        for ((ySrc, yDst) in ys) for ((xSrc, xDst) in xs) {
            out += Piece(xSrc.first, ySrc.first, xSrc.second, ySrc.second, xDst.first, yDst.first, xDst.second, yDst.second)
        }
        return out
    }

    private fun axis(segs: List<Pair<Int, Int>>, divs: IntArray, target: Float): List<Pair<Pair<Int, Int>, Pair<Float, Float>>> {
        fun stretchable(s: Pair<Int, Int>) = (divs.indices step 2).any { s.first >= divs[it] && s.second <= divs[it + 1] }
        val fixed = segs.filterNot(::stretchable).sumOf { it.second - it.first }
        val stretch = segs.filter(::stretchable).sumOf { it.second - it.first }
        val extra = target - fixed
        var pos = 0f
        return segs.map { s ->
            val len = (s.second - s.first).toFloat()
            val size = when {
                extra < 0f -> if (stretchable(s)) 0f else len * target / fixed
                stretchable(s) -> if (stretch == 0) 0f else extra * len / stretch
                else -> len
            }
            val dst = pos to pos + size
            pos += size
            s to dst
        }
    }

    companion object {
        const val BLACK = 0xFF000000.toInt()
        const val RED = 0xFFFF0000.toInt()
        const val NO_COLOR = 0x00000001
        const val TRANSPARENT_COLOR = 0x00000000
        private const val WHITE = 0xFFFFFFFF.toInt()

        private fun alpha(p: Int) = p ushr 24

        private fun checkDivs(divs: IntArray, size: Int, horizontal: Boolean) {
            if (divs.size % 2 != 0) throw NinePatchException(tr(if (horizontal) R.string.ninepatch_unpaired_horizontal else R.string.ninepatch_unpaired_vertical))
            var last = -1
            for (i in divs.indices step 2) {
                val s = divs[i]
                val e = divs[i + 1]
                if (s < 0 || e > size || s >= e || s < last) {
                    throw NinePatchException(tr(if (horizontal) R.string.ninepatch_bad_horizontal else R.string.ninepatch_bad_vertical, s, e))
                }
                last = e
            }
        }

        internal fun segments(divs: IntArray, size: Int): List<Pair<Int, Int>> {
            val cuts = (listOf(0) + divs.toList() + listOf(size)).distinct().sorted()
            return cuts.zipWithNext().filter { it.first < it.second }
        }

        fun fromSource(argb: IntArray, w: Int, h: Int): NinePatch {
            if (w < 3 || h < 3) throw NinePatchException(tr(R.string.ninepatch_too_small, 3, 3))
            require(argb.size >= w * h) { "Not a $w×$h picture" }
            val corner = argb[0]
            val neutral: (Int) -> Boolean = when {
                alpha(corner) == 0 -> { p -> alpha(p) == 0 }
                corner == WHITE -> { p -> p == WHITE }
                else -> throw NinePatchException(tr(R.string.ninepatch_corner_pixel), 0, 0)
            }
            val width = w - 2
            val height = h - 2

            fun line(at: (Int) -> Int, n: Int, xy: (Int) -> Pair<Int, Int>, redAllowed: Boolean): IntArray {
                val kinds = IntArray(n)
                for (i in 0 until n) {
                    val p = argb[at(i)]
                    kinds[i] = when {
                        neutral(p) -> 0
                        p == BLACK -> 1
                        p == RED && redAllowed -> 2
                        else -> {
                            val (x, y) = xy(i)
                            throw NinePatchException(
                                tr(if (p == RED) R.string.ninepatch_red_misplaced else R.string.ninepatch_border_pixel),
                                x, y,
                            )
                        }
                    }
                }
                return kinds
            }

            fun runs(kinds: IntArray, kind: Int): List<Pair<Int, Int>> {
                val out = ArrayList<Pair<Int, Int>>()
                var i = 0
                while (i < kinds.size) {
                    if (kinds[i] == kind) {
                        val s = i
                        while (i < kinds.size && kinds[i] == kind) i++
                        out += s to i
                    } else i++
                }
                return out
            }

            val top = line({ 1 + it }, width, { (1 + it) to 0 }, redAllowed = false)
            val left = line({ (1 + it) * w }, height, { 0 to (1 + it) }, redAllowed = false)
            val bottom = line({ (h - 1) * w + 1 + it }, width, { (1 + it) to (h - 1) }, redAllowed = true)
            val right = line({ (1 + it) * w + w - 1 }, height, { (w - 1) to (1 + it) }, redAllowed = true)

            val xDivs = runs(top, 1).flatMap { listOf(it.first, it.second) }.toIntArray()
            val yDivs = runs(left, 1).flatMap { listOf(it.first, it.second) }.toIntArray()

            val padX = runs(bottom, 1)
            val padY = runs(right, 1)
            if (padX.size > 1) throw NinePatchException(tr(R.string.ninepatch_content_lines_bottom), 1 + padX[1].first, h - 1)
            if (padY.size > 1) throw NinePatchException(tr(R.string.ninepatch_content_lines_right), w - 1, 1 + padY[1].first)

            fun bounds(kinds: IntArray, n: Int, bottomEdge: Boolean): Pair<Int, Int> {
                val red = runs(kinds, 2)
                if (red.size > 2) throw NinePatchException(tr(if (bottomEdge) R.string.ninepatch_bounds_lines_bottom else R.string.ninepatch_bounds_lines_right))
                var start = 0
                var end = 0
                for ((s, e) in red) when {
                    s == 0 -> start = e
                    e == n -> end = n - s
                    else -> throw NinePatchException(tr(if (bottomEdge) R.string.ninepatch_bounds_touch_bottom else R.string.ninepatch_bounds_touch_right))
                }
                return start to end
            }
            val (lbLeft, lbRight) = bounds(bottom, width, bottomEdge = true)
            val (lbTop, lbBottom) = bounds(right, height, bottomEdge = false)
            val layoutBounds = Insets(lbLeft, lbTop, lbRight, lbBottom).takeIf { it != Insets.ZERO }

            val explicit = padX.isNotEmpty() || padY.isNotEmpty()
            val (pl, pr) = padX.firstOrNull()?.let { it.first to width - it.second }
                ?: if (xDivs.isNotEmpty()) xDivs.first() to width - xDivs.last() else 0 to 0
            val (pt, pb) = padY.firstOrNull()?.let { it.first to height - it.second }
                ?: if (yDivs.isNotEmpty()) yDivs.first() to height - yDivs.last() else 0 to 0

            val pixels = IntArray(width * height)
            for (y in 0 until height) System.arraycopy(argb, (y + 1) * w + 1, pixels, y * width, width)
            return NinePatch(width, height, pixels, xDivs, yDivs, Insets(pl, pt, pr, pb), explicit, layoutBounds)
        }

        data class Chunk(val xDivs: IntArray, val yDivs: IntArray, val padding: Insets, val colors: IntArray)

        fun parseNpTc(data: ByteArray): Chunk {
            if (data.size < 32) throw NinePatchException(tr(R.string.ninepatch_nptc_short))
            val nx = data[1].toInt() and 0xFF
            val ny = data[2].toInt() and 0xFF
            val nc = data[3].toInt() and 0xFF
            if (data.size != 32 + 4 * (nx + ny + nc)) throw NinePatchException(tr(R.string.ninepatch_nptc_size))
            val b = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            val padding = Insets(b.getInt(12), b.getInt(20), b.getInt(16), b.getInt(24))
            b.position(32)
            return Chunk(IntArray(nx) { b.int }, IntArray(ny) { b.int }, padding, IntArray(nc) { b.int })
        }

        fun parseNpLb(data: ByteArray): Insets {
            if (data.size < 16) throw NinePatchException(tr(R.string.ninepatch_nplb_short))
            val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            return Insets(b.getInt(0), b.getInt(4), b.getInt(8), b.getInt(12))
        }

        fun fromCompiled(pixels: IntArray, width: Int, height: Int, npTc: ByteArray, npLb: ByteArray?): NinePatch {
            val c = parseNpTc(npTc)
            return NinePatch(width, height, pixels, c.xDivs, c.yDivs, c.padding, explicitPadding = true, layoutBounds = npLb?.let(::parseNpLb)?.takeIf { it != Insets.ZERO })
        }
    }
}
