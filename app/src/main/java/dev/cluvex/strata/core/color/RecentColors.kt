package dev.cluvex.strata.core.color

object RecentColors {
    const val MAX = 16

    fun push(recent: List<Int>, argb: Int): List<Int> = (listOf(argb) + recent.filterNot { it == argb }).take(MAX)

    fun encode(colors: List<Int>): String = colors.joinToString(",") { "%08X".format(java.util.Locale.ROOT, it) }

    fun decode(text: String?): List<Int> =
        text.orEmpty().split(',').mapNotNull { s -> s.trim().takeIf { it.length == 8 }?.toLongOrNull(16)?.toInt() }.distinct().take(MAX)
}
