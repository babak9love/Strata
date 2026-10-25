package dev.cluvex.strata.core.util

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object DateTimeText {

    const val PATTERN = "yyyy-MM-dd HH:mm:ss"

    private fun formatter(tz: TimeZone) = SimpleDateFormat(PATTERN, Locale.US).apply {
        timeZone = tz
        isLenient = false
    }

    fun format(millis: Long, tz: TimeZone = TimeZone.getDefault()): String = formatter(tz).format(millis)

    fun parse(text: String, tz: TimeZone = TimeZone.getDefault()): Long? {
        val trimmed = text.trim()
        if (trimmed.length != PATTERN.length) return null
        val pos = ParsePosition(0)
        val date = formatter(tz).parse(trimmed, pos) ?: return null
        return if (pos.index == trimmed.length) date.time else null
    }
}
