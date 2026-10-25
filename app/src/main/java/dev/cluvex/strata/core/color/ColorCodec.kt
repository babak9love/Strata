package dev.cluvex.strata.core.color

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object ColorCodec {
    data class Hsv(val h: Float, val s: Float, val v: Float)

    fun toHsv(argb: Int): Hsv {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val d = max - min
        val h = when {
            d == 0f -> 0f
            max == r -> 60f * (((g - b) / d) % 6f)
            max == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }.let { if (it < 0f) it + 360f else it }
        return Hsv(h, if (max == 0f) 0f else d / max, max)
    }

    fun fromHsv(hsv: Hsv, alpha: Int = 0xFF): Int {
        val h = ((hsv.h % 360f) + 360f) % 360f
        val s = hsv.s.coerceIn(0f, 1f)
        val v = hsv.v.coerceIn(0f, 1f)
        val c = v * s
        val x = c * (1f - abs((h / 60f) % 2f - 1f))
        val m = v - c
        val (r, g, b) = when ((h / 60f).toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return argb(alpha, ((r + m) * 255f).roundToInt(), ((g + m) * 255f).roundToInt(), ((b + m) * 255f).roundToInt())
    }

    fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a.coerceIn(0, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    fun alpha(argb: Int): Int = argb ushr 24
    fun red(argb: Int): Int = (argb shr 16) and 0xFF
    fun green(argb: Int): Int = (argb shr 8) and 0xFF
    fun blue(argb: Int): Int = argb and 0xFF

    fun onColor(argb: Int): Int {
        fun lin(c: Int): Double = (c / 255.0).let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
        val l = 0.2126 * lin(red(argb)) + 0.7152 * lin(green(argb)) + 0.0722 * lin(blue(argb))
        return if (l > 0.179) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }

    enum class Format(val label: String) {
        HEX_ARGB("#AARRGGBB"),
        HEX_RGB("#RRGGBB"),
        INT_ARGB("0xAARRGGBB"),
        COMPOSE("Color(0x…)"),
        CSS("rgb()/rgba()"),
    }

    fun format(argb: Int, format: Format): String = when (format) {
        Format.HEX_ARGB -> "#" + hex(argb, 8, upper = true)
        Format.HEX_RGB -> "#" + hex(argb, 6, upper = true)
        Format.INT_ARGB -> "0x" + hex(argb, 8, upper = true)
        Format.COMPOSE -> "Color(0x" + hex(argb, 8, upper = true) + ")"
        Format.CSS -> css(argb, forceAlpha = false)
    }

    fun androidHex(argb: Int): String = format(argb, if (alpha(argb) == 0xFF) Format.HEX_RGB else Format.HEX_ARGB)

    private fun hex(argb: Int, digits: Int, upper: Boolean): String {
        val s = when (digits) {
            3 -> "%X%X%X".format(Locale.ROOT, red(argb) shr 4, green(argb) shr 4, blue(argb) shr 4)
            4 -> "%X%X%X%X".format(Locale.ROOT, alpha(argb) shr 4, red(argb) shr 4, green(argb) shr 4, blue(argb) shr 4)
            6 -> "%06X".format(Locale.ROOT, argb and 0xFFFFFF)
            else -> "%08X".format(Locale.ROOT, argb)
        }
        return if (upper) s else s.lowercase(Locale.ROOT)
    }

    private fun css(argb: Int, forceAlpha: Boolean): String {
        val a = alpha(argb)
        return if (a == 0xFF && !forceAlpha) "rgb(${red(argb)}, ${green(argb)}, ${blue(argb)})"
        else "rgba(${red(argb)}, ${green(argb)}, ${blue(argb)}, ${cssAlpha(a)})"
    }

    private fun cssAlpha(a: Int): String {
        val v = (a / 255.0 * 100).roundToInt() / 100.0
        return if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
    }

    fun parse(text: String): Int? {
        val t = text.trim()
        val compose = Regex("^Color\\(\\s*(0[xX][0-9a-fA-F]{8})\\s*\\)$").find(t)
        val lit = literalAt(compose?.groupValues?.get(1) ?: t, 0) ?: return null
        val inner = compose?.groupValues?.get(1) ?: t
        return if (lit.start == 0 && lit.end == inner.length) lit.argb else null
    }

    sealed interface Style {
        data class Hash(val digits: Int, val upper: Boolean) : Style
        data class Int0x(val digits: Int, val upper: Boolean, val prefix: String) : Style
        data class Css(val withAlpha: Boolean) : Style
    }

    data class Literal(val start: Int, val end: Int, val argb: Int, val style: Style) {
        fun render(newArgb: Int): String {
            val opaque = alpha(newArgb) == 0xFF
            return when (val s = style) {
                is Style.Hash -> {
                    var digits = s.digits
                    if (!opaque && digits == 3) digits = 4
                    if (!opaque && digits == 6) digits = 8
                    if (digits <= 4 && !nibbled(newArgb)) digits = if (digits == 3) 6 else 8
                    "#" + hex(newArgb, digits, s.upper)
                }
                is Style.Int0x -> s.prefix + hex(newArgb, if (!opaque && s.digits == 6) 8 else s.digits, s.upper)
                is Style.Css -> css(newArgb, forceAlpha = s.withAlpha)
            }
        }
    }

    private fun nibbled(argb: Int): Boolean = (0 until 4).all { i -> ((argb ushr (i * 8)) and 0xFF).let { it shr 4 == it and 0xF } }

    fun literalAt(text: CharSequence, offset: Int): Literal? {
        if (offset < 0 || offset > text.length) return null
        val from = (offset - 40).coerceAtLeast(0)
        val to = (offset + 40).coerceAtMost(text.length)
        val window = text.subSequence(from, to).toString()
        for (m in LITERAL.findAll(window)) {
            val start = from + m.range.first
            val end = from + m.range.last + 1
            if (offset < start || offset > end) continue
            val before = if (start > 0) text[start - 1] else ' '
            val after = if (end < text.length) text[end] else ' '
            val lit = when {
                m.groups[1] != null -> hashLiteral(m.groupValues[1], start, end)
                m.groups[2] != null -> int0xLiteral(m.groupValues[2], m.value.substring(0, 2), start, end)
                else -> cssLiteral(m, start, end)
            } ?: continue
            if (lit.style !is Style.Css && (before.isWordChar() || after.isWordChar())) continue
            return lit
        }
        return null
    }

    private fun Char.isWordChar() = isLetterOrDigit() || this == '_'

    private fun hashLiteral(digits: String, start: Int, end: Int): Literal? {
        val upper = digits.any { it.isUpperCase() } && digits.none { it.isLowerCase() }
        val n = digits.length
        val argb = when (n) {
            3 -> (0xFF shl 24) or expand(digits)
            4 -> expand(digits)
            6 -> (0xFF shl 24) or digits.toInt(16)
            8 -> digits.toLong(16).toInt()
            else -> return null
        }
        return Literal(start, end, argb, Style.Hash(n, upper))
    }

    private fun expand(nibbles: String): Int = nibbles.fold(0) { acc, c -> val v = c.digitToInt(16); (acc shl 8) or (v shl 4) or v }

    private fun int0xLiteral(digits: String, prefix: String, start: Int, end: Int): Literal? {
        val upper = digits.any { it.isUpperCase() } && digits.none { it.isLowerCase() }
        val argb = when (digits.length) {
            6 -> (0xFF shl 24) or digits.toInt(16)
            8 -> digits.toLong(16).toInt()
            else -> return null
        }
        return Literal(start, end, argb, Style.Int0x(digits.length, upper, prefix))
    }

    private fun cssLiteral(m: MatchResult, start: Int, end: Int): Literal? {
        val name = m.groupValues[3].lowercase(Locale.ROOT)
        val parts = m.groupValues[4].split(Regex("\\s*[,/]\\s*|\\s+")).filter { it.isNotBlank() }
        if (parts.size !in 3..4) return null
        val rgb = parts.take(3).map { p ->
            if (p.endsWith("%")) p.dropLast(1).toDoubleOrNull()?.let { (it * 255.0 / 100.0).roundToInt() } else p.toDoubleOrNull()?.roundToInt()
        }
        if (rgb.any { it == null || it < 0 || it > 255 }) return null
        val a = if (parts.size == 4) {
            val p = parts[3]
            val v = if (p.endsWith("%")) p.dropLast(1).toDoubleOrNull()?.div(100) else p.toDoubleOrNull()
            if (v == null || v < 0 || v > 1) return null
            (v * 255).roundToInt()
        } else 0xFF
        return Literal(start, end, argb(a, rgb[0]!!, rgb[1]!!, rgb[2]!!), Style.Css(withAlpha = name == "rgba" || parts.size == 4))
    }

    private val LITERAL = Regex(
        "#([0-9a-fA-F]{8}|[0-9a-fA-F]{6}|[0-9a-fA-F]{4}|[0-9a-fA-F]{3})(?![0-9a-zA-Z_])" +
            "|0[xX]([0-9a-fA-F]{8}|[0-9a-fA-F]{6})(?![0-9a-zA-Z_])" +
            "|\\b(rgba?)\\(\\s*([^()]*?)\\s*\\)",
    )
}
