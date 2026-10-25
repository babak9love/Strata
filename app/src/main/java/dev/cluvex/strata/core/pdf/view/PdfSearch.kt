package dev.cluvex.strata.core.pdf.view

import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

data class SearchOptions(val matchCase: Boolean = false, val wholeWords: Boolean = false, val exactLetters: Boolean = false)

class FoldedText(val text: String, private val map: IntArray) {
    fun original(i: Int): Int = map[i]
    val length: Int get() = text.length
}

data class SearchHit(val page: Int, val start: Int, val end: Int)

data class Snippet(val text: String, val hitStart: Int, val hitEnd: Int)

object PdfSearch {

    private val decomposed = ConcurrentHashMap<Int, String>()

    fun fold(source: String, options: SearchOptions = SearchOptions(), softHyphens: IntArray = IntArray(0)): FoldedText {
        val out = StringBuilder(source.length)
        val map = IntArrayList(source.length)
        var lastSpace = true
        var i = 0
        var hyphen = 0
        while (i < source.length) {
            val cp = source.codePointAt(i)
            val width = Character.charCount(cp)
            while (hyphen < softHyphens.size && softHyphens[hyphen] < i) hyphen++
            if (hyphen < softHyphens.size && softHyphens[hyphen] == i) {
                val next = i + width
                if (next < source.length && source[next] == '\n') {
                    i = next + 1
                    continue
                }
            }
            if (dropped(cp)) {
                i += width
                continue
            }
            if (isSpace(cp)) {
                if (!lastSpace) {
                    out.append(' ')
                    map.add(i)
                    lastSpace = true
                }
                i += width
                continue
            }
            val parts = if (options.exactLetters) String(Character.toChars(cp)) else decompose(cp)
            var k = 0
            while (k < parts.length) {
                val c = parts.codePointAt(k)
                k += Character.charCount(c)
                if (!options.exactLetters && isMark(c)) continue
                val mapped = if (options.exactLetters) c else letter(c)
                val folded = if (options.matchCase) mapped else Character.toLowerCase(mapped)
                out.appendCodePoint(folded)
                repeat(Character.charCount(folded)) { map.add(i) }
                lastSpace = false
            }
            i += width
        }
        if (!options.exactLetters) return squeeze(out, map)
        return FoldedText(out.toString(), map.toArray())
    }

    private fun squeeze(text: StringBuilder, map: IntArrayList): FoldedText {
        val out = StringBuilder(text.length)
        val outMap = IntArrayList(text.length)
        for (k in text.indices) {
            val c = text[k]
            if (c == ' ') {
                val before = out.lastOrNull()
                val after = text.getOrNull(k + 1)
                if (before != null && after != null && arabicLetter(before.code) && arabicLetter(after.code)) continue
            }
            out.append(c)
            outMap.add(map[k])
        }
        return FoldedText(out.toString(), outMap.toArray())
    }

    fun find(page: Int, text: String, folded: FoldedText, query: FoldedText, options: SearchOptions): List<SearchHit> {
        val q = query.text.trim()
        if (q.isEmpty()) return emptyList()
        val hits = ArrayList<SearchHit>()
        var from = 0
        while (true) {
            val at = folded.text.indexOf(q, from)
            if (at < 0) break
            val start = folded.original(at)
            var end = folded.original(at + q.length - 1) + 1
            if (end < text.length && Character.isLowSurrogate(text[end])) end++
            while (end < text.length && trailing(text.codePointAt(end))) end += Character.charCount(text.codePointAt(end))
            if (!options.wholeWords || (boundary(text, start - 1) && boundary(text, end))) hits += SearchHit(page, start, end)
            from = at + q.length
        }
        return hits
    }

    fun snippet(text: String, hit: SearchHit, context: Int = 36): Snippet {
        var s = (hit.start - context).coerceAtLeast(0)
        var e = (hit.end + context).coerceAtMost(text.length)
        if (s > 0) {
            val space = text.indexOf(' ', s)
            if (space in s until hit.start) s = space + 1
        }
        if (e < text.length) {
            val space = text.lastIndexOf(' ', e)
            if (space >= hit.end) e = space
        }
        val raw = text.substring(s, e)
        val flat = raw.replace('\n', ' ')
        val prefix = if (s > 0) "…" else ""
        val suffix = if (e < text.length) "…" else ""
        return Snippet(prefix + flat + suffix, prefix.length + (hit.start - s), prefix.length + (hit.end - s))
    }

    private fun boundary(text: String, i: Int): Boolean {
        if (i < 0 || i >= text.length) return true
        val c = text[i]
        return !Character.isLetterOrDigit(c) && !isMark(c.code)
    }

    private fun trailing(cp: Int): Boolean = isMark(cp) || cp == 0x0640

    private fun decompose(cp: Int): String {
        if (cp < 0x80) return cp.toChar().toString()
        return decomposed.getOrPut(cp) { Normalizer.normalize(String(Character.toChars(cp)), Normalizer.Form.NFKD) }
    }

    private fun dropped(cp: Int): Boolean =
        cp == 0x200B || cp == 0x200C || cp == 0x200D || cp == 0x200E || cp == 0x200F || cp == 0x061C || cp == 0x00AD ||
            cp == 0xFEFF || cp == 0x0640 || cp in 0x202A..0x202E || cp in 0x2066..0x2069

    private fun isSpace(cp: Int): Boolean = Character.isWhitespace(cp) || Character.getType(cp) == Character.SPACE_SEPARATOR.toInt()

    private fun isMark(cp: Int): Boolean {
        val t = Character.getType(cp)
        return t == Character.NON_SPACING_MARK.toInt() || t == Character.ENCLOSING_MARK.toInt() || t == Character.COMBINING_SPACING_MARK.toInt()
    }

    private fun arabicLetter(cp: Int): Boolean = cp in 0x0600..0x06FF && Character.isLetter(cp) || cp in 0x0750..0x077F || cp in 0x08A0..0x08FF

    private fun letter(cp: Int): Int = when (cp) {
        0x064A, 0x0649, 0x06CC, 0x0626, 0x06D0, 0x06CD -> 0x06CC
        0x0643, 0x06AA, 0x06A9 -> 0x06A9
        0x0629, 0x06D5, 0x06C0, 0x06C1, 0x06BE -> 0x0647
        0x0671, 0x0672, 0x0673, 0x0675 -> 0x0627
        0x0624 -> 0x0648
        in 0x06F0..0x06F9 -> '0'.code + (cp - 0x06F0)
        in 0x0660..0x0669 -> '0'.code + (cp - 0x0660)
        0x066B -> '.'.code
        0x066C, 0x060C -> ','.code
        0x061B -> ';'.code
        0x061F -> '?'.code
        0x066A -> '%'.code
        0x00AB, 0x00BB, 0x201C, 0x201D, 0x201E, 0x201F -> '"'.code
        0x2018, 0x2019, 0x201A, 0x201B -> '\''.code
        0x2010, 0x2011, 0x2012, 0x2013, 0x2014, 0x2015, 0x2212 -> '-'.code
        else -> cp
    }

    private class IntArrayList(capacity: Int) {
        private var data = IntArray(capacity.coerceAtLeast(16))
        var size = 0
            private set
        fun add(v: Int) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = v
        }
        operator fun get(i: Int): Int = data[i]
        fun toArray(): IntArray = data.copyOf(size)
    }
}
