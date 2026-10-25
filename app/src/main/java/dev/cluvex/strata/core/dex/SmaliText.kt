package dev.cluvex.strata.core.dex

object SmaliText {

    fun readable(smali: String): String {
        if (!smali.contains("\\u")) return smali
        val out = StringBuilder(smali.length)
        var i = 0
        var inString = false
        var inComment = false
        while (i < smali.length) {
            val c = smali[i]
            when {
                c == '\n' -> {
                    inString = false
                    inComment = false
                    out.append(c)
                    i++
                }
                inComment -> {
                    out.append(c)
                    i++
                }
                !inString && c == '#' -> {
                    inComment = true
                    out.append(c)
                    i++
                }
                !inString && c == '"' -> {
                    inString = true
                    out.append(c)
                    i++
                }
                inString && c == '"' -> {
                    inString = false
                    out.append(c)
                    i++
                }
                inString && c == '\\' && i + 1 < smali.length -> {
                    val decoded = if (smali[i + 1] == 'u') decodeAt(smali, i) else null
                    if (decoded != null) {
                        out.append(decoded.first)
                        i += decoded.second
                    } else {
                        out.append(c).append(smali[i + 1])
                        i += 2
                    }
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    private fun decodeAt(s: String, at: Int): Pair<String, Int>? {
        val unit = hex4(s, at + 2) ?: return null
        val c = unit.toChar()
        if (Character.isHighSurrogate(c)) {
            if (at + 12 <= s.length && s.startsWith("\\u", at + 6)) {
                val low = hex4(s, at + 8)?.toChar() ?: return null
                if (!Character.isLowSurrogate(low)) return null
                val cp = Character.toCodePoint(c, low)
                return if (visible(cp)) String(Character.toChars(cp)) to 12 else null
            }
            return null
        }
        if (Character.isLowSurrogate(c)) return null
        return if (visible(unit)) c.toString() to 6 else null
    }

    private fun hex4(s: String, from: Int): Int? {
        if (from + 4 > s.length) return null
        var v = 0
        for (k in from until from + 4) {
            val d = Character.digit(s[k], 16)
            if (d < 0) return null
            v = v * 16 + d
        }
        return v
    }

    fun visible(cp: Int): Boolean {
        if (cp < 0xA0) return false
        if (!Character.isDefined(cp)) return false
        return when (Character.getType(cp).toByte()) {
            Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED,
            Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.SPACE_SEPARATOR -> false
            else -> true
        }
    }

    fun literals(line: String): List<IntRange> {
        val out = ArrayList<IntRange>()
        var i = 0
        var start = -1
        while (i < line.length) {
            val c = line[i]
            if (start < 0) {
                if (c == '#') break
                if (c == '"') start = i
                i++
            } else {
                when (c) {
                    '\\' -> i += 2
                    '"' -> {
                        out += start..i
                        start = -1
                        i++
                    }
                    else -> i++
                }
            }
        }
        return out
    }

    fun commentStart(line: String): Int {
        var inString = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inString && c == '\\' -> i++
                c == '"' -> inString = !inString
                !inString && c == '#' -> return i
            }
            i++
        }
        return -1
    }

    fun unescape(content: String): String {
        if (!content.contains('\\')) return content
        val out = StringBuilder(content.length)
        var i = 0
        while (i < content.length) {
            val c = content[i]
            if (c != '\\' || i + 1 >= content.length) {
                out.append(c)
                i++
                continue
            }
            when (val e = content[i + 1]) {
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'b' -> out.append('\b')
                'f' -> out.append('\u000c')
                '0' -> out.append('\u0000')
                'u' -> {
                    val v = hex4(content, i + 2)
                    if (v != null) {
                        out.append(v.toChar())
                        i += 6
                        continue
                    }
                    out.append(e)
                }
                else -> out.append(e)
            }
            i += 2
        }
        return out.toString()
    }
}
