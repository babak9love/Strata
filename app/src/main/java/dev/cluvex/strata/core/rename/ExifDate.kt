package dev.cluvex.strata.core.rename

import java.text.ParsePosition
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

object ExifDate {
    fun parse(text: String, zone: TimeZone = TimeZone.getDefault()): Long? {
        val trimmed = text.trim()
        if (trimmed.length != 19) return null
        val format = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).apply {
            isLenient = false
            timeZone = zone
        }
        val pos = ParsePosition(0)
        val date = format.parse(trimmed, pos) ?: return null
        return if (pos.index == trimmed.length) date.time else null
    }
}
