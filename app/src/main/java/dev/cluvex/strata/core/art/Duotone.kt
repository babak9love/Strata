package dev.cluvex.strata.core.art

import kotlin.math.roundToInt

object Duotone {

    private const val RED = 0.2126f
    private const val GREEN = 0.7152f
    private const val BLUE = 0.0722f

    fun matrix(deep: Int, light: Int): FloatArray {
        val m = FloatArray(20)
        for (channel in 0..2) {
            val shift = 16 - channel * 8
            val from = (deep ushr shift and 0xFF).toFloat()
            val to = (light ushr shift and 0xFF).toFloat()
            val span = (to - from) / 255f
            m[channel * 5] = span * RED
            m[channel * 5 + 1] = span * GREEN
            m[channel * 5 + 2] = span * BLUE
            m[channel * 5 + 4] = from
        }
        m[18] = 1f
        return m
    }

    fun apply(matrix: FloatArray, argb: Int): Int {
        val r = (argb ushr 16 and 0xFF).toFloat()
        val g = (argb ushr 8 and 0xFF).toFloat()
        val b = (argb and 0xFF).toFloat()
        val a = (argb ushr 24 and 0xFF).toFloat()
        fun row(i: Int): Int {
            val v = matrix[i * 5] * r + matrix[i * 5 + 1] * g + matrix[i * 5 + 2] * b + matrix[i * 5 + 3] * a + matrix[i * 5 + 4]
            return v.roundToInt().coerceIn(0, 255)
        }
        return (row(3) shl 24) or (row(0) shl 16) or (row(1) shl 8) or row(2)
    }
}
