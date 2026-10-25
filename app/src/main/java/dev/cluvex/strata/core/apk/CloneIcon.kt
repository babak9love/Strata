package dev.cluvex.strata.core.apk

import kotlin.math.roundToInt

enum class IconDensity(val qualifier: String, val scale: Float) {
    MDPI("mdpi", 1f),
    HDPI("hdpi", 1.5f),
    XHDPI("xhdpi", 2f),
    XXHDPI("xxhdpi", 3f),
    XXXHDPI("xxxhdpi", 4f);

    fun px(dp: Int): Int = (dp * scale).roundToInt()
}

class CloneIcon(
    val legacy: Map<IconDensity, ByteArray>,
    val foreground: Map<IconDensity, ByteArray>,
    val background: Map<IconDensity, ByteArray>,
) {
    init {
        require(legacy.isNotEmpty() && foreground.isNotEmpty()) { "An icon needs at least one density" }
        require(foreground.keys == background.keys) { "Adaptive layers need the same densities" }
    }

    companion object {
        const val LEGACY_DP = 48
        const val LAYER_DP = 108
        const val VIEWPORT_DP = 72

        fun edgeColor(argb: IntArray, size: Int): Int {
            require(size > 0 && argb.size >= size * size) { "Not a $size×$size picture" }
            var a = 0L; var r = 0L; var g = 0L; var b = 0L; var n = 0
            for (y in 0 until size) for (x in 0 until size) {
                if (y != 0 && y != size - 1 && x != 0 && x != size - 1) continue
                val p = argb[y * size + x]
                val alpha = p ushr 24
                a += alpha; r += ((p shr 16) and 0xFF) * alpha; g += ((p shr 8) and 0xFF) * alpha; b += (p and 0xFF) * alpha
                n++
            }
            if (a < 128L * n) return WHITE
            return (0xFF shl 24) or ((r / a).toInt() shl 16) or ((g / a).toInt() shl 8) or (b / a).toInt()
        }

        private const val WHITE = 0xFFFFFFFF.toInt()
    }
}
