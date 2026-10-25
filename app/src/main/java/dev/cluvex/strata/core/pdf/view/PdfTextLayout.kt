package dev.cluvex.strata.core.pdf.view

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class PageText(
    val text: String,
    private val boxes: FloatArray,
    val lineStarts: IntArray,
    val lineRtl: BooleanArray,
    val softHyphens: IntArray,
) {
    val length: Int get() = text.length

    fun box(i: Int): Box = Box(boxes[i * 4], boxes[i * 4 + 1], boxes[i * 4 + 2], boxes[i * 4 + 3])

    fun hasBox(i: Int): Boolean = boxes[i * 4 + 2] > boxes[i * 4] && boxes[i * 4 + 3] > boxes[i * 4 + 1]

    fun line(i: Int): Int {
        var lo = 0
        var hi = lineStarts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (lineStarts[mid] <= i) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun lineEnd(line: Int): Int = if (line + 1 < lineStarts.size) lineStarts[line + 1] - 1 else text.length

    fun rects(start: Int, end: Int): List<Box> {
        val out = ArrayList<Box>()
        if (start >= end) return out
        var i = start.coerceAtLeast(0)
        val stop = end.coerceAtMost(text.length)
        while (i < stop) {
            val line = line(i)
            val lineStop = min(stop, lineEnd(line))
            val parts = ArrayList<Box>()
            for (k in i until lineStop) if (hasBox(k)) parts += box(k)
            parts.sortBy { it.left }
            var current: Box? = null
            for (p in parts) {
                val c = current
                current = if (c == null) {
                    p
                } else if (p.left <= c.right + 0.35f * max(c.height, p.height)) {
                    Box(min(c.left, p.left), min(c.top, p.top), max(c.right, p.right), max(c.bottom, p.bottom))
                } else {
                    out += c
                    p
                }
            }
            current?.let { out += it }
            i = max(lineStop, i + 1)
            while (i < stop && i < text.length && text[i] == '\n') i++
        }
        return out
    }

    fun indexAt(x: Float, y: Float, slop: Float = 0f): Int? {
        if (text.isEmpty()) return null
        var bestLine = -1
        var bestDistance = Float.MAX_VALUE
        for (line in lineStarts.indices) {
            var top = Float.MAX_VALUE
            var bottom = -Float.MAX_VALUE
            for (k in lineStarts[line] until lineEnd(line)) {
                if (!hasBox(k)) continue
                top = min(top, boxes[k * 4 + 1])
                bottom = max(bottom, boxes[k * 4 + 3])
            }
            if (top > bottom) continue
            val d = if (y < top) top - y else if (y > bottom) y - bottom else 0f
            if (d < bestDistance) {
                bestDistance = d
                bestLine = line
            }
        }
        if (bestLine < 0 || bestDistance > slop) return null
        var best = -1
        var bestDx = Float.MAX_VALUE
        for (k in lineStarts[bestLine] until lineEnd(bestLine)) {
            if (!hasBox(k)) continue
            val l = boxes[k * 4]
            val r = boxes[k * 4 + 2]
            val d = if (x < l) l - x else if (x > r) x - r else 0f
            if (d < bestDx) {
                bestDx = d
                best = k
            }
        }
        return best.takeIf { it >= 0 }
    }

    companion object {
        val EMPTY = PageText("", FloatArray(0), intArrayOf(0), booleanArrayOf(false), IntArray(0))
    }
}

object PdfTextLayout {

    private class Glyph(
        val text: String,
        val content: Int,
        var l: Float,
        var t: Float,
        var r: Float,
        var b: Float,
        var u0: Float,
        var u1: Float,
        val vb: Float,
        val size: Float,
        val angle: Int,
        val hyphen: Boolean,
        val mark: Boolean,
        val kind: Char,
    ) {
        val uc: Float get() = (u0 + u1) / 2
        var marks: StringBuilder? = null
        fun width(): Float = u1 - u0
        fun absorb(o: Glyph) {
            l = min(l, o.l)
            t = min(t, o.t)
            r = max(r, o.r)
            b = max(b, o.b)
            u0 = min(u0, o.u0)
            u1 = max(u1, o.u1)
        }
    }

    private class Hint(val u: Float, val vb: Float, val angle: Int)

    private class Line(first: Glyph) {
        val glyphs = arrayListOf(first)
        val angle = first.angle
        var baseline = first.vb
        var size = first.size

        var left = first.l
        var right = first.r
        var rtlLetters = if (first.kind == 'R') 1 else 0
        var ltrLetters = if (first.kind == 'L') 1 else 0

        fun accepts(g: Glyph): Boolean {
            if (abs(g.angle - angle) > 2) return false
            return abs(g.vb - baseline) < 0.6f * max(g.size, size)
        }

        fun add(g: Glyph) {
            glyphs += g
            val n = glyphs.size
            baseline += (g.vb - baseline) / n
            size += (g.size - size) / n
            left = min(left, g.l)
            right = max(right, g.r)
            if (g.kind == 'R') rtlLetters++ else if (g.kind == 'L') ltrLetters++
        }
    }

    private enum class Align { LEFT, RIGHT }

    private class Unit(val text: String, val l: Float, val t: Float, val r: Float, val b: Float, val kind: Char, val hyphen: Boolean)

    private fun kindOf(cp: Int): Char = when (Character.getDirectionality(cp)) {
        Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> 'R'
        Character.DIRECTIONALITY_LEFT_TO_RIGHT -> 'L'
        Character.DIRECTIONALITY_EUROPEAN_NUMBER, Character.DIRECTIONALITY_ARABIC_NUMBER -> 'D'
        else -> 'N'
    }

    private fun isMark(cp: Int): Boolean {
        val t = Character.getType(cp)
        return t == Character.NON_SPACING_MARK.toInt() || t == Character.ENCLOSING_MARK.toInt()
    }

    private fun isSpace(cp: Int): Boolean = cp == 0x20 || cp == 0xA0 || Character.isWhitespace(cp) || Character.getType(cp) == Character.SPACE_SEPARATOR.toInt()

    private fun ignorable(cp: Int): Boolean =
        cp < 0x20 || cp in 0x7F..0x9F || cp == 0xFFFD || cp == 0xFEFF || cp == 0x200B || cp == 0x200C || cp == 0x200D ||
            cp == 0x200E || cp == 0x200F || cp == 0x061C || cp in 0x202A..0x202E || cp in 0x2066..0x2069 || cp == 0

    fun build(chars: PageChars): PageText {
        val glyphs = ArrayList<Glyph>(chars.count)
        val hints = ArrayList<Hint>()
        var rtlCount = 0
        var ltrCount = 0
        for (i in 0 until chars.count) {
            val flags = chars.flags(i)
            if (flags and PageChars.GENERATED != 0) continue
            val cp = chars.codepoint(i)
            if (cp < 0 || cp > 0x10FFFF) continue
            var dx = chars.dirX(i)
            var dy = chars.dirY(i)
            if (dx.isNaN() || dy.isNaN() || (dx == 0f && dy == 0f)) {
                dx = 1f
                dy = 0f
            }
            val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).roundToInt()
            val ox = chars.originX(i)
            val oy = chars.originY(i)
            val vb = -ox * dy + oy * dx
            if (isSpace(cp)) {
                val width = abs((chars.right(i) - chars.left(i)) * dx + (chars.bottom(i) - chars.top(i)) * dy)
                val size = chars.size(i)
                if (width > 0.1f * (if (size.isNaN() || size < 0.5f) 10f else size)) {
                    hints += Hint((chars.left(i) + chars.right(i)) / 2 * dx + (chars.top(i) + chars.bottom(i)) / 2 * dy, vb, angle)
                }
                continue
            }
            if (ignorable(cp)) continue
            val l = chars.left(i)
            val t = chars.top(i)
            val r = chars.right(i)
            val b = chars.bottom(i)
            val us = floatArrayOf(l * dx + t * dy, r * dx + t * dy, l * dx + b * dy, r * dx + b * dy)
            val vs = floatArrayOf(-l * dy + t * dx, -r * dy + t * dx, -l * dy + b * dx, -r * dy + b * dx)
            var size = chars.size(i)
            if (size.isNaN() || size < 0.5f) size = ((vs.max() - vs.min()) * 0.8f).coerceAtLeast(1f)
            val kind = kindOf(cp)
            if (kind == 'R') rtlCount++ else if (kind == 'L') ltrCount++
            glyphs += Glyph(
                text = String(Character.toChars(cp)),
                content = i,
                l = min(l, r), t = min(t, b), r = max(l, r), b = max(t, b),
                u0 = us.min(), u1 = us.max(),
                vb = vb,
                size = size,
                angle = angle,
                hyphen = flags and PageChars.HYPHEN != 0,
                mark = isMark(cp),
                kind = kind,
            )
        }
        if (glyphs.isEmpty()) return PageText.EMPTY
        val pageRtl = rtlCount > 0 && rtlCount >= ltrCount

        val lines = ArrayList<Line>()
        for (g in glyphs) {
            var target: Line? = null
            for (k in lines.size - 1 downTo max(0, lines.size - 3)) {
                if (lines[k].accepts(g)) {
                    target = lines[k]
                    break
                }
            }
            if (target != null) target.add(g) else lines += Line(g)
        }

        val upright = lines.filter { abs(it.angle) <= 2 && it.glyphs.size >= 3 }
        val leftMargin = upright.minOfOrNull { it.left } ?: 0f
        val rightMargin = upright.maxOfOrNull { it.right } ?: 0f
        fun align(line: Line): Align? {
            if (abs(line.angle) > 2 || upright.size < 2) return null
            val tolerance = max(2f, 0.5f * line.size)
            val atRight = rightMargin - line.right < tolerance
            val atLeft = line.left - leftMargin < tolerance
            return if (atRight && !atLeft) Align.RIGHT else if (atLeft && !atRight) Align.LEFT else null
        }
        fun style(pure: List<Line>): Align? {
            val seen = pure.mapNotNull { align(it) }
            if (seen.isEmpty()) return null
            val right = seen.count { it == Align.RIGHT }
            return when {
                right * 3 >= seen.size * 2 -> Align.RIGHT
                (seen.size - right) * 3 >= seen.size * 2 -> Align.LEFT
                else -> null
            }
        }
        val rtlStyle = style(upright.filter { it.rtlLetters > 0 && it.ltrLetters == 0 })
        val ltrStyle = style(upright.filter { it.ltrLetters > 0 && it.rtlLetters == 0 })

        val text = StringBuilder()
        val boxes = FloatArrayBuilder()
        val starts = ArrayList<Int>()
        val rtl = ArrayList<Boolean>()
        val hyphens = ArrayList<Int>()
        for ((index, line) in lines.withIndex()) {
            if (index > 0) {
                text.append('\n')
                boxes.add(0f, 0f, 0f, 0f)
            }
            starts += text.length
            val units = units(line, hints)
            val a = align(line)
            val hinted = when {
                a == null -> null
                rtlStyle == a && ltrStyle != a -> true
                ltrStyle == a && rtlStyle != a -> false
                rtlStyle != null && rtlStyle != a && ltrStyle == null -> false
                ltrStyle != null && ltrStyle != a && rtlStyle == null -> true
                else -> null
            }
            val lineRtl = direction(units, hinted ?: pageRtl)
            rtl += lineRtl
            val ordered = reorder(units, lineRtl)
            val lineStart = text.length
            for (u in ordered) {
                if (u.hyphen) hyphens += text.length
                for (ch in u.text) {
                    text.append(ch)
                    boxes.add(u.l, u.t, u.r, u.b)
                }
            }
            if (lineRtl) mirrorBrackets(text, lineStart)
            pairTwins(text, lineStart)
        }
        return PageText(text.toString(), boxes.toArray(), starts.toIntArray(), rtl.toBooleanArray(), hyphens.toIntArray())
    }

    private val ligatures: Set<String> by lazy {
        val out = HashSet<String>()
        for (cp in (0xFB50..0xFDFF) + (0xFE70..0xFEFF)) {
            if (!Character.isDefined(cp)) continue
            val single = String(Character.toChars(cp))
            for (form in listOf(Normalizer.Form.NFKC, Normalizer.Form.NFKD)) {
                val d = Normalizer.normalize(single, form)
                if (d.length >= 2 && d.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.ARABIC }) out += d
            }
        }
        out
    }

    private fun sameBox(a: Glyph, b: Glyph): Boolean =
        abs(a.l - b.l) < 0.01f && abs(a.r - b.r) < 0.01f && abs(a.t - b.t) < 0.01f && abs(a.b - b.b) < 0.01f

    private fun orderClusters(bases: MutableList<Glyph>) {
        var i = 0
        while (i < bases.size) {
            var j = i
            while (j + 1 < bases.size && sameBox(bases[i], bases[j + 1])) j++
            if (j > i && bases[i].kind == 'R') {
                val group = bases.subList(i, j + 1).sortedBy { it.content }
                val logical = when {
                    group.joinToString("") { it.text } in ligatures -> group
                    group.reversed().joinToString("") { it.text } in ligatures -> group.reversed()
                    else -> null
                }
                if (logical != null) logical.reversed().forEachIndexed { k, g -> bases[i + k] = g }
            }
            i = j + 1
        }
    }

    private fun screenOrdered(glyphs: List<Glyph>): Boolean {
        var right = 0
        var left = 0
        for (k in 1 until glyphs.size) {
            val a = glyphs[k - 1]
            val b = glyphs[k]
            if (a.kind != 'R' || b.kind != 'R') continue
            val step = b.uc - a.uc
            if (step > 0.5f) right++ else if (step < -0.5f) left++
        }
        return right > left
    }

    private fun units(line: Line, hints: List<Hint>): List<Unit> {
        val byPlace = compareBy<Glyph> { it.uc }
        val sorted = line.glyphs.sortedWith(if (screenOrdered(line.glyphs)) byPlace.thenByDescending { it.content } else byPlace.thenBy { it.content })
        val kept = ArrayList<Glyph>(sorted.size)
        for (g in sorted) {
            var merged = false
            for (k in kept.size - 1 downTo max(0, kept.size - 4)) {
                val o = kept[k]
                if (o.text == g.text && abs(o.vb - g.vb) < 0.8f * max(o.size, g.size) && !(sameBox(o, g) && abs(o.content - g.content) == 1)) {
                    val narrow = min(o.width(), g.width())
                    val overlap = min(o.u1, g.u1) - max(o.u0, g.u0)
                    if (narrow > 0f && overlap >= 0.7f * narrow) {
                        o.absorb(g)
                        merged = true
                        break
                    }
                }
            }
            if (!merged) kept += g
        }
        val bases = kept.filter { !it.mark }.toMutableList()
        orderClusters(bases)
        if (bases.isEmpty()) {
            return kept.map { Unit(it.text, it.l, it.t, it.r, it.b, 'N', it.hyphen) }
        }
        for (m in kept) {
            if (!m.mark) continue
            var best = bases[0]
            var bestD = Float.MAX_VALUE
            for (b in bases) {
                val d = if (m.uc < b.u0) b.u0 - m.uc else if (m.uc > b.u1) m.uc - b.u1 else 0f
                if (d < bestD) {
                    bestD = d
                    best = b
                }
            }
            (best.marks ?: StringBuilder().also { best.marks = it }).append(m.text)
            best.absorb(m)
        }
        val size = line.size.coerceAtLeast(1f)
        val units = ArrayList<Unit>(bases.size * 2)
        var prev: Glyph? = null
        for (g in bases) {
            val p = prev
            if (p != null) {
                val gap = g.u0 - p.u1
                val spaced = gap > 0.18f * size || (gap > 0.04f * size && hints.any { h ->
                    abs(h.angle - line.angle) <= 2 && abs(h.vb - line.baseline) < 0.6f * size && h.u >= p.u1 - 0.05f * size && h.u <= g.u0 + 0.05f * size
                })
                if (spaced) {
                    units += Unit(" ", min(p.r, g.l), min(p.t, g.t), max(p.r, g.l), max(p.b, g.b), 'N', false)
                }
            }
            val text = g.marks?.let { g.text + it } ?: g.text
            units += Unit(text, g.l, g.t, g.r, g.b, g.kind, g.hyphen)
            prev = g
        }
        return units
    }

    private fun direction(units: List<Unit>, pageRtl: Boolean): Boolean {
        var r = 0
        var l = 0
        for (u in units) {
            if (u.kind == 'R') r++ else if (u.kind == 'L') l++
        }
        return when {
            r > 0 && l == 0 -> true
            l > 0 && r == 0 -> false
            else -> pageRtl
        }
    }

    private fun reorder(units: List<Unit>, rtl: Boolean): List<Unit> {
        val n = units.size
        if (n <= 1) return units
        val kinds = CharArray(n) { units[it].kind }
        for (i in 1 until n - 1) {
            if (kinds[i] == 'N' && kinds[i - 1] == 'D' && kinds[i + 1] == 'D' && units[i].text.length == 1 && units[i].text[0] in NUMBER_SEPARATORS) {
                kinds[i] = 'D'
            }
        }
        fun strongLeft(i: Int): Char? {
            for (k in i - 1 downTo 0) if (kinds[k] == 'R' || kinds[k] == 'L') return kinds[k]
            return null
        }
        fun strongRight(i: Int): Char? {
            for (k in i + 1 until n) if (kinds[k] == 'R' || kinds[k] == 'L') return kinds[k]
            return null
        }
        val resolved = CharArray(n)
        val levels = IntArray(n)
        var i = 0
        while (i < n) {
            if (kinds[i] != 'D') {
                i++
                continue
            }
            var j = i
            while (j + 1 < n && kinds[j + 1] == 'D') j++
            val left = strongLeft(i)
            val right = strongRight(j)
            val asLtr = if (rtl) left == 'L' else !(right == 'R' && (left == 'R' || left == null))
            for (k in i..j) {
                resolved[k] = if (asLtr) 'L' else 'R'
                levels[k] = if (rtl) 2 else if (asLtr) 0 else 2
            }
            i = j + 1
        }
        for (k in 0 until n) {
            when (kinds[k]) {
                'R' -> {
                    resolved[k] = 'R'
                    levels[k] = 1
                }
                'L' -> {
                    resolved[k] = 'L'
                    levels[k] = if (rtl) 2 else 0
                }
            }
        }
        for (k in 0 until n) {
            if (kinds[k] != 'N') continue
            var a = k - 1
            while (a >= 0 && kinds[a] == 'N') a--
            var b = k + 1
            while (b < n && kinds[b] == 'N') b++
            val left = if (a >= 0) resolved[a] else null
            val right = if (b < n) resolved[b] else null
            val dir = if (left != null && left == right) left else if (rtl) 'R' else 'L'
            resolved[k] = dir
            levels[k] = if (dir == 'R') 1 else if (rtl) 2 else 0
        }
        val order = IntArray(n) { it }
        val maxLevel = levels.max()
        for (level in maxLevel downTo 1) {
            var s = 0
            while (s < n) {
                if (levels[order[s]] < level) {
                    s++
                    continue
                }
                var e = s
                while (e + 1 < n && levels[order[e + 1]] >= level) e++
                var x = s
                var y = e
                while (x < y) {
                    val tmp = order[x]
                    order[x] = order[y]
                    order[y] = tmp
                    x++
                    y--
                }
                s = e + 1
            }
        }
        return order.map { units[it] }
    }

    private fun mirrorBrackets(text: StringBuilder, from: Int) {
        for ((open, close) in BRACKETS) {
            var opens = 0
            var closes = 0
            var first: Char? = null
            for (k in from until text.length) {
                val c = text[k]
                if (c == open || c == close) {
                    if (first == null) first = c
                    if (c == open) opens++ else closes++
                }
            }
            if (first == close && opens == closes && opens > 0) {
                for (k in from until text.length) {
                    when (text[k]) {
                        open -> text.setCharAt(k, close)
                        close -> text.setCharAt(k, open)
                    }
                }
            }
        }
    }

    private fun pairTwins(text: StringBuilder, from: Int) {
        for ((open, close) in BRACKETS) {
            val opens = ArrayList<Int>()
            val closes = ArrayList<Int>()
            for (k in from until text.length) {
                when (text[k]) {
                    open -> opens += k
                    close -> closes += k
                }
            }
            if (opens.size == 2 && closes.isEmpty()) text.setCharAt(opens[1], close)
            if (closes.size == 2 && opens.isEmpty()) text.setCharAt(closes[0], open)
        }
    }

    private val NUMBER_SEPARATORS = setOf('.', ',', ':', '/', '٫', '٬')

    private val BRACKETS = listOf('(' to ')', '[' to ']', '{' to '}', '<' to '>', '«' to '»', '‹' to '›')

    private class FloatArrayBuilder {
        private var data = FloatArray(256)
        private var size = 0
        fun add(a: Float, b: Float, c: Float, d: Float) {
            if (size + 4 > data.size) data = data.copyOf(max(data.size * 2, size + 4))
            data[size++] = a
            data[size++] = b
            data[size++] = c
            data[size++] = d
        }
        fun toArray(): FloatArray = data.copyOf(size)
    }
}
