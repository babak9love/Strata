package dev.cluvex.strata.ui.expressive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import kotlin.math.max
import kotlin.math.min

class PolygonMorph(val from: RoundedPolygon, val to: RoundedPolygon) {

    private val morph = Morph(from, to)
    private val bounds: FloatArray = run {
        val a = from.calculateMaxBounds()
        val b = to.calculateMaxBounds()
        floatArrayOf(min(a[0], b[0]), min(a[1], b[1]), max(a[2], b[2]), max(a[3], b[3]))
    }

    fun fill(progress: Float, width: Float, height: Float, rotation: Float, scale: Float, path: android.graphics.Path, matrix: android.graphics.Matrix) {
        path.rewind()
        morph.toPath(progress.coerceIn(0f, 1f), path)
        val w = bounds[2] - bounds[0]
        val h = bounds[3] - bounds[1]
        val s = min(width / w, height / h) * scale
        matrix.setTranslate(-(bounds[0] + w / 2f), -(bounds[1] + h / 2f))
        matrix.postScale(s, s)
        matrix.postRotate(rotation)
        matrix.postTranslate(width / 2f, height / 2f)
        path.transform(matrix)
    }
}

@Composable
fun rememberPolygonMorph(from: RoundedPolygon, to: RoundedPolygon): PolygonMorph = remember(from, to) { PolygonMorph(from, to) }

class MorphShape(
    private val morph: PolygonMorph,
    private val progress: Float,
    private val rotation: Float = 0f,
    private val scale: Float = 1f,
) : Shape {

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val path = android.graphics.Path()
        morph.fill(progress, size.width, size.height, rotation, scale, path, android.graphics.Matrix())
        return Outline.Generic(path.asComposePath())
    }

    override fun equals(other: Any?): Boolean =
        other is MorphShape && other.morph === morph && other.progress == progress && other.rotation == rotation && other.scale == scale

    override fun hashCode(): Int = ((System.identityHashCode(morph) * 31 + progress.hashCode()) * 31 + rotation.hashCode()) * 31 + scale.hashCode()
}

class MorphPainter {
    private val path = android.graphics.Path()
    private val matrix = android.graphics.Matrix()

    fun DrawScope.draw(morph: PolygonMorph, progress: Float, color: Color, rotation: Float = 0f, scale: Float = 1f, alpha: Float = 1f) {
        morph.fill(progress, size.width, size.height, rotation, scale, path, matrix)
        drawPath(path.asComposePath(), color, alpha = alpha)
    }
}
