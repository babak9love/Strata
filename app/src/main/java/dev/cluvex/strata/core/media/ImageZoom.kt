package dev.cluvex.strata.core.media

import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object ImageZoom {
    const val MIN_MAX_SCALE = 8f

    const val FIT_HEADROOM = 4f

    const val DOUBLE_TAP_STEP = 2.5f

    const val CRISP_FROM = 3f

    const val DIRECT_MAX_PIXELS = 1L shl 20

    fun fitScale(width: Float, height: Float, viewWidth: Float, viewHeight: Float): Float =
        min(viewWidth / width, viewHeight / height)

    fun baseScale(width: Float, height: Float, viewWidth: Float, viewHeight: Float): Float =
        min(1f, fitScale(width, height, viewWidth, viewHeight))

    fun maxScale(width: Float, height: Float, viewWidth: Float, viewHeight: Float): Float =
        max(MIN_MAX_SCALE, fitScale(width, height, viewWidth, viewHeight) * FIT_HEADROOM)

    fun doubleTapTarget(current: Float, width: Float, height: Float, viewWidth: Float, viewHeight: Float): Float {
        val base = baseScale(width, height, viewWidth, viewHeight)
        if (abs(current - base) > base * 0.05f) return base
        val fit = fitScale(width, height, viewWidth, viewHeight)
        return min(max(fit, base * DOUBLE_TAP_STEP), maxScale(width, height, viewWidth, viewHeight))
    }

    fun percent(scale: Float): String = tr(R.string.media_zoom_percent, (scale * 100).roundToInt())

    fun oriented(width: Int, height: Int, shownWidth: Float, shownHeight: Float): Pair<Int, Int> {
        if (width == height || shownWidth <= 0f || shownHeight <= 0f) return width to height
        val shown = shownWidth / shownHeight
        val asIs = abs(width.toFloat() / height - shown)
        val turned = abs(height.toFloat() / width - shown)
        return if (turned < asIs) height to width else width to height
    }

    fun drawsDirectly(header: ByteArray, width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0 || width.toLong() * height > DIRECT_MAX_PIXELS) return false
        return when {
            header.startsWith(PNG) -> true
            header.startsWith(BMP) -> true
            header.startsWith(ICO) -> true
            isWebp(header) -> !isAnimatedWebp(header)
            else -> false
        }
    }

    fun isWebp(header: ByteArray): Boolean =
        header.size >= 12 && header.startsWith(RIFF) && header.copyOfRange(8, 12).contentEquals(WEBP)

    fun isAnimatedWebp(header: ByteArray): Boolean =
        isWebp(header) && header.size > 20 && header.copyOfRange(12, 16).contentEquals(VP8X) && (header[20].toInt() and 0x02) != 0

    fun header(file: File): ByteArray = file.inputStream().use { input ->
        val buf = ByteArray(HEADER)
        var n = 0
        while (n < buf.size) {
            val read = input.read(buf, n, buf.size - n)
            if (read < 0) break
            n += read
        }
        buf.copyOf(n)
    }

    const val HEADER = 32

    private val PNG = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
    private val BMP = "BM".toByteArray(Charsets.US_ASCII)
    private val ICO = byteArrayOf(0, 0, 1, 0)
    private val RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
    private val WEBP = "WEBP".toByteArray(Charsets.US_ASCII)
    private val VP8X = "VP8X".toByteArray(Charsets.US_ASCII)

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}
