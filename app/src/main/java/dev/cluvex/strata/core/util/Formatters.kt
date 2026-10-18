package dev.cluvex.strata.core.util

import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.model.DateStyle
import dev.cluvex.strata.core.model.SizeUnits
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.ln
import kotlin.math.pow

object Formatters {

    @Volatile
    var locale: Locale = Locale.US

    @Volatile
    var solarHijri: Boolean = false

    private val binaryUnits = intArrayOf(R.string.unit_b, R.string.unit_kb, R.string.unit_mb, R.string.unit_gb, R.string.unit_tb, R.string.unit_pb)
    private val decimalUnits = intArrayOf(R.string.unit_b, R.string.unit_kb_decimal, R.string.unit_mb, R.string.unit_gb, R.string.unit_tb, R.string.unit_pb)

    private val jalaliMonths = intArrayOf(
        R.string.jalali_month_1, R.string.jalali_month_2, R.string.jalali_month_3, R.string.jalali_month_4,
        R.string.jalali_month_5, R.string.jalali_month_6, R.string.jalali_month_7, R.string.jalali_month_8,
        R.string.jalali_month_9, R.string.jalali_month_10, R.string.jalali_month_11, R.string.jalali_month_12,
    )

    private val persianWeekdays = intArrayOf(
        R.string.persian_weekday_1, R.string.persian_weekday_2, R.string.persian_weekday_3, R.string.persian_weekday_4,
        R.string.persian_weekday_5, R.string.persian_weekday_6, R.string.persian_weekday_7,
    )

    private class Style(val locale: Locale, solarHijri: Boolean) {
        val jalali = solarHijri && locale.language == "fa"
    }

    fun number(value: Long, locale: Locale = Formatters.locale): String = String.format(locale, "%d", value)

    fun size(bytes: Long): String = size(bytes, SizeUnits.BINARY)

    fun size(bytes: Long, units: SizeUnits, locale: Locale = Formatters.locale): String {
        val base = if (units == SizeUnits.BINARY) 1024.0 else 1000.0
        val names = if (units == SizeUnits.BINARY) binaryUnits else decimalUnits
        if (bytes < base) return tr(names[0], number(bytes, locale))
        val exp = (ln(bytes.toDouble()) / ln(base)).toInt().coerceAtMost(names.size - 1)
        val value = bytes / base.pow(exp)
        return tr(names[exp], String.format(locale, "%.2f", value))
    }

    fun date(
        epochMillis: Long,
        zone: TimeZone = TimeZone.getDefault(),
        locale: Locale = Formatters.locale,
        solarHijri: Boolean = Formatters.solarHijri,
    ): String {
        if (epochMillis <= 0) return "—"
        val s = Style(locale, solarHijri)
        if (s.jalali) return tr(R.string.date_long_time, longDay(s, epochMillis, zone), time(s, epochMillis, true, zone))
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).apply { timeZone = zone }.format(Date(epochMillis))
    }

    fun shortDate(epochMillis: Long, zone: TimeZone = TimeZone.getDefault()): String =
        if (epochMillis <= 0) "—" else listDate(epochMillis, DateStyle.COMPACT, true, zone = zone)

    fun listDate(
        epochMillis: Long,
        style: DateStyle,
        use24Hour: Boolean,
        now: Long = System.currentTimeMillis(),
        zone: TimeZone = TimeZone.getDefault(),
        locale: Locale = Formatters.locale,
        solarHijri: Boolean = Formatters.solarHijri,
    ): String {
        if (epochMillis <= 0) return "—"
        val s = Style(locale, solarHijri)
        val time = time(s, epochMillis, use24Hour, zone)
        return when (style) {
            DateStyle.COMPACT -> numeric(s, epochMillis, zone, full = false) + " " + time
            DateStyle.ISO -> numeric(s, epochMillis, zone, full = true) + " " + time
            DateStyle.LONG -> tr(R.string.date_long_time, longDay(s, epochMillis, zone), time)
            DateStyle.RELATIVE -> {
                val ago = now - epochMillis
                val minute = 60_000L
                when {
                    ago < 0 -> numeric(s, epochMillis, zone, full = false) + " " + time
                    ago < minute -> tr(R.string.date_just_now)
                    ago < 60 * minute -> tr(R.string.date_minutes_ago, number(ago / minute, locale))
                    sameDay(epochMillis, now, zone) -> tr(R.string.date_hours_ago, number(ago / (60 * minute), locale))
                    sameDay(epochMillis, now - 86_400_000L, zone) -> tr(R.string.date_yesterday, time)
                    ago < 6 * 86_400_000L -> tr(R.string.date_weekday_time, weekday(s, epochMillis, zone), time)
                    else -> numeric(s, epochMillis, zone, full = false) + " " + time
                }
            }
        }
    }

    private fun calendar(epochMillis: Long, zone: TimeZone): Calendar = Calendar.getInstance(zone).apply { timeInMillis = epochMillis }

    private fun jalali(epochMillis: Long, zone: TimeZone): Jalali.Date {
        val c = calendar(epochMillis, zone)
        return Jalali.fromGregorian(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    private fun digits(s: Style, value: Int, width: Int = 1): String = String.format(s.locale, "%0${width}d", value)

    private fun numeric(s: Style, epochMillis: Long, zone: TimeZone, full: Boolean): String {
        if (s.jalali) {
            val j = jalali(epochMillis, zone)
            val year = if (full) digits(s, j.year, 4) else digits(s, j.year % 100, 2)
            return "$year/${digits(s, j.month, 2)}/${digits(s, j.day, 2)}"
        }
        return format(s, if (full) "yyyy-MM-dd" else "yy-MM-dd", epochMillis, zone)
    }

    private fun longDay(s: Style, epochMillis: Long, zone: TimeZone): String {
        if (s.jalali) {
            val j = jalali(epochMillis, zone)
            return "${digits(s, j.day)} ${tr(jalaliMonths[j.month - 1])} ${digits(s, j.year)}"
        }
        return DateFormat.getDateInstance(DateFormat.MEDIUM, s.locale).apply { timeZone = zone }.format(Date(epochMillis))
    }

    private fun weekday(s: Style, epochMillis: Long, zone: TimeZone): String {
        if (s.jalali) return tr(persianWeekdays[calendar(epochMillis, zone).get(Calendar.DAY_OF_WEEK) % 7])
        return format(s, "EEE", epochMillis, zone)
    }

    private fun time(s: Style, epochMillis: Long, use24Hour: Boolean, zone: TimeZone): String =
        format(s, if (use24Hour) "HH:mm" else "h:mm a", epochMillis, zone)

    private fun format(s: Style, pattern: String, epochMillis: Long, zone: TimeZone): String =
        SimpleDateFormat(pattern, s.locale).apply { timeZone = zone }.format(Date(epochMillis))

    private fun sameDay(a: Long, b: Long, zone: TimeZone): Boolean {
        val ca = calendar(a, zone)
        val cb = calendar(b, zone)
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
    }

    fun displayName(name: String, isDirectory: Boolean, showExtension: Boolean): String {
        if (showExtension || isDirectory) return name
        val dot = name.lastIndexOf('.')
        return if (dot <= 0) name else name.substring(0, dot)
    }
}
