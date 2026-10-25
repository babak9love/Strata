package dev.cluvex.strata.feature.explorer.components

import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics

/**
 * Smart fallback icon for file types that have no branded icon: a rounded tile with the exact geometry of the
 * language icons (128 viewport, 8..120 box, 26 radius, vertical gradient) and the file extension centered in white bold.
 * The colour is derived from the extension, so every file of one type always gets the same (pseudo-random) colour.
 */
@Composable
fun ExtensionIcon(extension: String, modifier: Modifier = Modifier, contentDescription: String? = null) {
    val label = remember(extension) { extension.uppercase().take(MAX_LABEL) }
    val (top, bottom) = remember(extension) { ExtensionColors.gradient(extension) }
    val paint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.DEFAULT_BOLD
            color = android.graphics.Color.WHITE
            textAlign = Paint.Align.CENTER
        }
    }
    val bounds = remember { Rect() }
    val semanticsModifier = if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier
    Canvas(modifier.then(semanticsModifier)) {
        val u = size.minDimension / 128f
        drawRoundRect(
            brush = Brush.verticalGradient(listOf(top, bottom), startY = 8f * u, endY = 120f * u),
            topLeft = Offset(8f * u, 8f * u),
            size = Size(112f * u, 112f * u),
            cornerRadius = CornerRadius(26f * u, 26f * u),
        )
        // Same type scale as the CSS / JS icons: ~40 units for three letters, larger for one or two, shrunk to fit the tile.
        var textSize = when (label.length) {
            1, 2 -> 48f
            3 -> 40f
            4 -> 34f
            else -> 30f
        } * u
        paint.textSize = textSize
        val maxWidth = 84f * u
        val width = paint.measureText(label)
        if (width > maxWidth) {
            textSize = (textSize * maxWidth / width).coerceAtLeast(12f * u)
            paint.textSize = textSize
        }
        paint.getTextBounds("H", 0, 1, bounds)
        val baseline = 64f * u + bounds.height() / 2f
        drawIntoCanvas { it.nativeCanvas.drawText(label, 64f * u, baseline, paint) }
    }
}

private const val MAX_LABEL = 8

private object ExtensionColors {
    /** Stable pseudo-random gradient (light top, darker bottom) for an extension. */
    fun gradient(extension: String): Pair<Color, Color> {
        var h = 2166136261L
        for (ch in extension.lowercase()) {
            h = (h xor ch.code.toLong()) * 16777619L and 0xFFFFFFFFL
        }
        val hue = (h % 360L).toFloat()
        var topV = 1.0f
        var bottomV = 0.80f
        val topColor = hsv(hue, 0.76f, topV)
        // Light hues (yellow / green) would make the white letters hard to read, so they are darkened.
        val luminance = 0.299f * topColor.red + 0.587f * topColor.green + 0.114f * topColor.blue
        if (luminance > 0.62f) {
            val k = 0.72f
            topV *= k
            bottomV *= k
        }
        return hsv(hue, 0.76f, topV) to hsv(hue, 0.86f, bottomV)
    }

    private fun hsv(h: Float, s: Float, v: Float): Color =
        Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)))
}
