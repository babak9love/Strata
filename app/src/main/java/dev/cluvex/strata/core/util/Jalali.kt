package dev.cluvex.strata.core.util

object Jalali {

    data class Date(val year: Int, val month: Int, val day: Int)

    private val BREAKS = intArrayOf(
        -61, 9, 38, 199, 426, 686, 756, 818, 1111, 1181, 1210, 1635, 2060, 2097, 2192, 2262, 2324, 2394, 2456, 3178,
    )

    fun fromGregorian(year: Int, month: Int, day: Int): Date = fromDayNumber(dayNumber(year, month, day))

    fun toGregorian(year: Int, month: Int, day: Int): Triple<Int, Int, Int> {
        val r = calendar(year)
        val start = dayNumber(r.gregorianYear, 3, r.march)
        val jdn = start + (month - 1) * 31 - (month / 7) * (month - 7) + day - 1
        return gregorian(jdn)
    }

    fun isLeap(year: Int): Boolean = calendar(year).leap == 0

    fun monthLength(year: Int, month: Int): Int = when {
        month <= 6 -> 31
        month <= 11 -> 30
        isLeap(year) -> 30
        else -> 29
    }

    private class Cal(val leap: Int, val gregorianYear: Int, val march: Int)

    private fun calendar(jy: Int): Cal {
        require(jy >= BREAKS.first() && jy < BREAKS.last()) { "Year out of range: $jy" }
        val gy = jy + 621
        var leapJ = -14
        var jp = BREAKS[0]
        var jump = 0
        for (i in 1 until BREAKS.size) {
            val jm = BREAKS[i]
            jump = jm - jp
            if (jy < jm) break
            leapJ += jump / 33 * 8 + jump % 33 / 4
            jp = jm
        }
        var n = jy - jp
        leapJ += n / 33 * 8 + (n % 33 + 3) / 4
        if (jump % 33 == 4 && jump - n == 4) leapJ += 1
        val leapG = gy / 4 - (gy / 100 + 1) * 3 / 4 - 150
        val march = 20 + leapJ - leapG
        if (jump - n < 6) n = n - jump + (jump + 4) / 33 * 33
        var leap = ((n + 1) % 33 - 1) % 4
        if (leap == -1) leap = 4
        return Cal(leap, gy, march)
    }

    private fun fromDayNumber(jdn: Int): Date {
        val gy = gregorian(jdn).first
        var jy = gy - 621
        val r = calendar(jy)
        val start = dayNumber(gy, 3, r.march)
        var k = jdn - start
        if (k >= 0) {
            if (k <= 185) return Date(jy, 1 + k / 31, k % 31 + 1)
            k -= 186
        } else {
            jy -= 1
            k += 179
            if (r.leap == 1) k += 1
        }
        return Date(jy, 7 + k / 30, k % 30 + 1)
    }

    private fun dayNumber(gy: Int, gm: Int, gd: Int): Int {
        var d = (gy + (gm - 8) / 6 + 100100) * 1461 / 4 + (153 * ((gm + 9) % 12) + 2) / 5 + gd - 34840408
        d = d - (gy + 100100 + (gm - 8) / 6) / 100 * 3 / 4 + 752
        return d
    }

    private fun gregorian(jdn: Int): Triple<Int, Int, Int> {
        var j = 4 * jdn + 139361631
        j += (4 * jdn + 183187720) / 146097 * 3 / 4 * 4 - 3908
        val i = j % 1461 / 4 * 5 + 308
        val gd = i % 153 / 5 + 1
        val gm = i / 153 % 12 + 1
        val gy = j / 1461 - 100100 + (8 - gm) / 6
        return Triple(gy, gm, gd)
    }
}
