package dev.cluvex.strata.feature.media

import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Sleep timer for the video player. It lives outside the screen, so it keeps counting when the app is
 * hidden or when the audio has been handed over to [PlaybackService]; when it runs out, whatever is
 * playing (the video, or the audio in the background service) is paused.
 *
 * Input format is hours and minutes, like `3h 5m`, `45m` or `2h`.
 */
object SleepTimer {

    private const val MAX_MINUTES = 24 * 60L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    private val _endsAt = MutableStateFlow<Long?>(null)

    /** [SystemClock.elapsedRealtime] value at which the timer fires, or null when it is off. */
    val endsAt: StateFlow<Long?> = _endsAt.asStateFlow()

    /** Set by the video player while it is open: pauses the video itself. */
    @Volatile var videoPauser: (() -> Unit)? = null

    /** Must be called on the main thread. */
    fun start(durationMs: Long) {
        job?.cancel()
        val end = SystemClock.elapsedRealtime() + durationMs
        _endsAt.value = end
        job = scope.launch {
            while (true) {
                val left = end - SystemClock.elapsedRealtime()
                if (left <= 0L) break
                delay(minOf(left, 1_000L))
            }
            _endsAt.value = null
            job = null
            videoPauser?.invoke()
            PlaybackService.pausePlayback()
        }
    }

    /** Must be called on the main thread. */
    fun cancel() {
        job?.cancel()
        job = null
        _endsAt.value = null
    }

    /** `3h 5m` to milliseconds; null when the text is empty, malformed, zero or longer than 24 hours. */
    fun parse(text: String): Long? {
        val s = normalizeDigits(text).trim().lowercase()
        val m = Regex("""(?:(\d{1,3})\s*h)?\s*(?:(\d{1,4})\s*m)?""").matchEntire(s) ?: return null
        val h = m.groupValues[1].toLongOrNull()
        val min = m.groupValues[2].toLongOrNull()
        if (h == null && min == null) return null
        val total = (h ?: 0L) * 60L + (min ?: 0L)
        if (total <= 0L || total > MAX_MINUTES) return null
        return total * 60_000L
    }

    /** Only the digits the user typed (Persian and Arabic-Indic ones count), without leading zeros, at most four. */
    fun cleanDigits(text: String): String = normalizeDigits(text).filter { it in '0'..'9' }.trimStart('0').take(4)

    /** The fixed-format field: digits fill it from the right, so `130` is `1h 30m` and nothing typed is `0h 00m`. */
    fun maskText(digits: String): String {
        val n = digits.toIntOrNull() ?: 0
        return "${n / 100}h ${(n % 100).toString().padStart(2, '0')}m"
    }

    /** Typed [digits] (see [maskText]) to milliseconds; null when it is zero or longer than 24 hours. */
    fun fromDigits(digits: String): Long? {
        val n = digits.toIntOrNull() ?: 0
        val total = (n / 100) * 60L + (n % 100)
        if (total <= 0L || total > MAX_MINUTES) return null
        return total * 60_000L
    }

    /** Remaining time as `3h 5m`, `2h` or `45m`, rounded up to the next minute. */
    fun format(ms: Long): String {
        val totalMin = ((ms.coerceAtLeast(0L) + 59_999L) / 60_000L)
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h > 0 && m > 0 -> "${h}h ${m}m"
            h > 0 -> "${h}h"
            else -> "${m}m"
        }
    }

    // Persian (۰-۹) and Arabic-Indic (٠-٩) digits are accepted too.
    private fun normalizeDigits(s: String): String = buildString(s.length) {
        for (c in s) append(
            when (c) {
                in '\u06F0'..'\u06F9' -> '0' + (c - '\u06F0')
                in '\u0660'..'\u0669' -> '0' + (c - '\u0660')
                else -> c
            },
        )
    }
}
