package dev.cluvex.strata.core.sound

import android.app.Activity
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Bundle
import dev.cluvex.strata.R
import java.util.EnumMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class SoundPoolDevice(context: Context) : SoundDevice {

    private val app = context.applicationContext
    private val audio = app.getSystemService(AudioManager::class.java)
    private val notifications = app.getSystemService(NotificationManager::class.java)
    private val started = AtomicInteger()
    private val ready: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    private val loudReady: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    private val samples = EnumMap<UiSound, Int>(UiSound::class.java)
    private val loudSamples = EnumMap<UiSound, Int>(UiSound::class.java)
    private val pool = poolFor(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
    private val loudPool = poolFor(AudioAttributes.USAGE_MEDIA)

    init {
        pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) ready += id }
        loudPool.setOnLoadCompleteListener { _, id, status -> if (status == 0) loudReady += id }
        for (sound in UiSound.entries) {
            samples[sound] = pool.load(app, rawOf(sound), 1)
            loudSamples[sound] = loudPool.load(app, rawOf(sound), 1)
        }
    }

    val loaded: Set<UiSound> get() = samples.filterValues { it in ready }.keys.intersect(loudSamples.filterValues { it in loudReady }.keys)

    override val ringer: RingerMode
        get() {
            val filter = notifications?.currentInterruptionFilter ?: NotificationManager.INTERRUPTION_FILTER_UNKNOWN
            val doNotDisturb = filter != NotificationManager.INTERRUPTION_FILTER_ALL && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
            val mode = audio?.mode
            val inCall = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
            return when {
                doNotDisturb || inCall -> RingerMode.BLOCKED
                audio?.ringerMode == AudioManager.RINGER_MODE_NORMAL -> RingerMode.NORMAL
                audio?.ringerMode == AudioManager.RINGER_MODE_VIBRATE -> RingerMode.VIBRATE
                else -> RingerMode.SILENT
            }
        }

    override val foreground: Boolean get() = started.get() > 0

    override fun play(sound: UiSound, loud: Boolean) {
        start(sound, loud = loud)
    }

    fun start(sound: UiSound, volume: Float = sound.volume, loud: Boolean = false): Int {
        val id = (if (loud) loudSamples else samples)[sound] ?: return 0
        if (id !in (if (loud) loudReady else ready)) return 0
        return (if (loud) loudPool else pool).play(id, volume, volume, 1, 0, 1f)
    }

    fun trackActivities(application: Application) {
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) {
                    started.incrementAndGet()
                }

                override fun onActivityStopped(activity: Activity) {
                    started.decrementAndGet()
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityResumed(activity: Activity) = Unit
                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) = Unit
            },
        )
    }

    fun release() {
        pool.release()
        loudPool.release()
    }

    private fun poolFor(usage: Int): SoundPool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(usage)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    companion object {
        fun rawOf(sound: UiSound): Int = when (sound) {
            UiSound.SUCCESS -> R.raw.ui_success
            UiSound.ERROR -> R.raw.ui_error
            UiSound.TRASH -> R.raw.ui_trash
            UiSound.RESTORE -> R.raw.ui_restore
            UiSound.LAND -> R.raw.ui_land
            UiSound.SELECT -> R.raw.ui_select
            UiSound.POP -> R.raw.ui_pop
            UiSound.ATTENTION -> R.raw.ui_attention
            UiSound.UNLOCK -> R.raw.ui_unlock
            UiSound.LOCK -> R.raw.ui_lock
            UiSound.COMPLETE -> R.raw.ui_complete
        }
    }
}
