package dev.cluvex.strata.core.apk

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr
import java.io.ByteArrayOutputStream
import java.io.IOException

object CloneIconRenderer {
    private const val MAX_SIDE = 1024

    fun decode(bytes: ByteArray): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException(tr(R.string.apk_error_not_picture))
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: throw IOException(tr(R.string.apk_error_not_picture))
    }

    fun render(picture: Bitmap): CloneIcon {
        val square = centerSquare(picture)
        val edge = Bitmap.createScaledBitmap(square, EDGE_SAMPLE, EDGE_SAMPLE, true)
        val pixels = IntArray(EDGE_SAMPLE * EDGE_SAMPLE).also { edge.getPixels(it, 0, EDGE_SAMPLE, 0, 0, EDGE_SAMPLE, EDGE_SAMPLE) }
        val background = CloneIcon.edgeColor(pixels, EDGE_SAMPLE)
        return CloneIcon(
            legacy = IconDensity.entries.associateWith { png(Bitmap.createScaledBitmap(square, it.px(CloneIcon.LEGACY_DP), it.px(CloneIcon.LEGACY_DP), true)) },
            foreground = IconDensity.entries.associateWith { png(layer(square, it.px(CloneIcon.LAYER_DP), it.px(CloneIcon.VIEWPORT_DP))) },
            background = IconDensity.entries.associateWith { d ->
                png(Bitmap.createBitmap(d.px(CloneIcon.LAYER_DP), d.px(CloneIcon.LAYER_DP), Bitmap.Config.ARGB_8888).apply { eraseColor(background) })
            },
        )
    }

    private fun centerSquare(b: Bitmap): Bitmap {
        val side = minOf(b.width, b.height)
        return if (b.width == b.height) b else Bitmap.createBitmap(b, (b.width - side) / 2, (b.height - side) / 2, side, side)
    }

    private fun layer(square: Bitmap, size: Int, viewport: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val inset = (size - viewport) / 2
        Canvas(out).drawBitmap(square, null, Rect(inset, inset, inset + viewport, inset + viewport), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun png(b: Bitmap): ByteArray = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    private const val EDGE_SAMPLE = 64
}
