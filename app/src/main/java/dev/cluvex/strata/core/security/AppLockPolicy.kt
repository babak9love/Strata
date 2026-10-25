package dev.cluvex.strata.core.security

class AppLockPolicy(private val returnGraceMs: Long = 5 * 60_000L) {

    var enabled: Boolean = false
        private set

    var locked: Boolean = false
        private set

    var delayMs: Long = 0
        private set

    private var hiddenAt: Long? = null
    private var forResultAt: Long? = null
    private var authenticating = false

    fun configure(enabled: Boolean, delayMs: Long, coldStart: Boolean = false) {
        val wasEnabled = this.enabled
        this.enabled = enabled
        this.delayMs = delayMs.coerceAtLeast(0)
        if (!enabled) {
            locked = false
            hiddenAt = null
        } else if (coldStart && !wasEnabled) {
            locked = true
        }
    }

    fun turnedOn() {
        enabled = true
        locked = false
        hiddenAt = null
    }

    fun leavingForResult(now: Long) {
        forResultAt = now
    }

    fun hidden(now: Long) {
        if (!enabled || locked || authenticating || hiddenAt != null) return
        hiddenAt = now
    }

    fun shown(now: Long) {
        val at = hiddenAt
        hiddenAt = null
        val forResult = forResultAt
        forResultAt = null
        if (!enabled || locked || authenticating || at == null) return
        val away = now - at
        if (forResult != null && forResult <= at && away < returnGraceMs) return
        if (away >= delayMs) locked = true
    }

    fun lockNow() {
        if (enabled) locked = true
    }

    fun authStarted() {
        authenticating = true
    }

    fun authFinished(success: Boolean) {
        authenticating = false
        if (success) {
            locked = false
            hiddenAt = null
            forResultAt = null
        }
    }
}
