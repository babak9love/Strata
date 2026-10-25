package dev.cluvex.strata.ui.security

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import dev.cluvex.strata.core.model.SecurityPrefs
import dev.cluvex.strata.core.security.AppLockPolicy
import dev.cluvex.strata.core.vault.Vault
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors

object AppLock {

    private val policy = AppLockPolicy()
    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    @Volatile
    var bypass = false

    private var started = false
    private var vaultDelayMs = 60_000L
    private var vaultHiddenAt: Long? = null
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val vaultTimer = Runnable { lockVault() }

    private fun now(): Long = SystemClock.elapsedRealtime()

    fun apply(security: SecurityPrefs) {
        val enabled = security.appLock && !bypass
        val delay = security.lockDelay.seconds * 1000L
        if (!started) {
            started = true
            policy.configure(enabled, delay, coldStart = true)
        } else {
            policy.configure(enabled, delay)
        }
        vaultDelayMs = security.vaultLockDelay.seconds * 1000L
        publish()
    }

    fun turnedOn() {
        policy.turnedOn()
        publish()
    }

    fun lockNow() {
        policy.lockNow()
        publish()
    }

    fun authStarted() = policy.authStarted()

    fun authFinished(success: Boolean) {
        policy.authFinished(success)
        publish()
    }

    fun leavingForResult() = policy.leavingForResult(now())

    fun hidden() {
        val t = now()
        policy.hidden(t)
        if (vaultHiddenAt == null) vaultHiddenAt = t
        main.removeCallbacks(vaultTimer)
        main.postDelayed(vaultTimer, vaultDelayMs.coerceAtLeast(0))
        publish()
    }

    fun shown() {
        val t = now()
        policy.shown(t)
        main.removeCallbacks(vaultTimer)
        val at = vaultHiddenAt
        vaultHiddenAt = null
        if (at != null && t - at >= vaultDelayMs) lockVault()
        publish()
    }

    fun lockVault() {
        if (!Vault.ready) return
        val store = Vault.store
        if (store.isUnlocked) worker.execute { runCatching { store.lock() } }
    }

    fun track(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            private var startedCount = 0

            override fun onActivityStarted(activity: Activity) {
                if (startedCount++ == 0) shown()
            }

            override fun onActivityStopped(activity: Activity) {
                startedCount = (startedCount - 1).coerceAtLeast(0)
                if (startedCount == 0 && !activity.isChangingConfigurations) hidden()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun publish() {
        _locked.value = policy.locked
    }
}
