package dev.cluvex.strata.feature.explorer.components

import android.util.LruCache
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import dev.cluvex.strata.ui.isolated

/**
 * File name that never hides its format. When the name does not fit in [maxLines], the beginning of the name is kept on
 * the first line(s) followed by "…", and the LAST line shows the end of the name including the extension:
 *
 *     fact.dorax_3995269_long…
 *     …36262.mp4            (e.g. "36262.mp4")
 *
 * With a single line the ellipsis goes in the middle ("fact.dor…36262.mp4"). Names that fit are shown unchanged.
 */
@Composable
fun FileNameText(
    name: String,
    maxLines: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    textAlign: TextAlign? = null,
) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    BoxWithConstraints(modifier) {
        val width = constraints.maxWidth
        val fit = remember(name, maxLines, width, style, density.density, density.fontScale, direction) {
            if (width == Constraints.Infinity) null else fitName(name, maxLines, width, style, measurer, density, direction)
        }
        val text = when {
            fit == null -> isolated(name)
            fit.inline -> isolated(fit.head + "…" + fit.tail)
            else -> isolated(fit.head + "…") + "\n" + isolated(fit.tail)
        }
        Text(
            text = text,
            style = style,
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis,
            textAlign = textAlign,
        )
    }
}

private class NameFit(val head: String, val tail: String, val inline: Boolean)

private val NONE = NameFit("", "", true)
private val cache = LruCache<String, NameFit>(1024)

private fun fitName(
    name: String,
    maxLines: Int,
    width: Int,
    style: TextStyle,
    measurer: TextMeasurer,
    density: Density,
    direction: LayoutDirection,
): NameFit? {
    val key = "$name|$maxLines|$width|${style.hashCode()}|${density.density}|${density.fontScale}|$direction"
    cache.get(key)?.let { return if (it === NONE) null else it }
    val result = compute(name, maxLines, width, style, measurer, density, direction)
    cache.put(key, result ?: NONE)
    return result
}

private fun compute(
    name: String,
    maxLines: Int,
    width: Int,
    style: TextStyle,
    measurer: TextMeasurer,
    density: Density,
    direction: LayoutDirection,
): NameFit? {
    if (name.length < 4 || maxLines < 1) return null

    fun overflows(s: String, lines: Int): Boolean {
        val r = measurer.measure(
            text = s,
            style = style,
            maxLines = lines,
            constraints = Constraints(maxWidth = width),
            layoutDirection = direction,
            density = density,
        )
        return r.didOverflowHeight || r.didOverflowWidth
    }

    // Fits as it is (wrapped over the allowed lines): nothing to shorten.
    if (!overflows(name, maxLines)) return null

    val len = name.length

    fun tailOf(count: Int): String {
        var start = len - count
        if (start in 1 until len && Character.isLowSurrogate(name[start])) start--
        return name.substring(start.coerceAtLeast(0))
    }

    fun headOf(count: Int): String {
        var h = name.take(count)
        if (h.isNotEmpty() && Character.isHighSurrogate(h.last())) h = h.dropLast(1)
        return h.trimEnd()
    }

    if (maxLines == 1) {
        // Middle ellipsis: keep the extension plus a few characters before it.
        val dot = name.lastIndexOf('.')
        val ext = if (dot > 0 && len - dot <= 9) len - dot else 0
        val tailLen = minOf(len - 1, maxOf(ext + 4, 6))
        val tail = tailOf(tailLen)
        var lo = 0
        var hi = len - tail.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (overflows(headOf(mid) + "…" + tail, 1)) hi = mid - 1 else lo = mid
        }
        if (lo <= 0) return null
        return NameFit(headOf(lo), tail, inline = true)
    }

    // Several lines: the last line is reserved for the end of the name (with the extension), the rest shows the start.
    var lo = 1
    var hi = len - 1
    while (lo < hi) {
        val mid = (lo + hi + 1) / 2
        if (overflows(tailOf(mid), 1)) hi = mid - 1 else lo = mid
    }
    val tail = tailOf(lo)
    var hLo = 0
    var hHi = len - tail.length
    while (hLo < hHi) {
        val mid = (hLo + hHi + 1) / 2
        if (overflows(headOf(mid) + "…", maxLines - 1)) hHi = mid - 1 else hLo = mid
    }
    if (hLo >= len - tail.length) return null
    return NameFit(headOf(hLo), tail, inline = false)
}
