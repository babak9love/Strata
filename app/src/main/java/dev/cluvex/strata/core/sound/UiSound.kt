package dev.cluvex.strata.core.sound

enum class UiSound(val resource: String, val volume: Float) {
    SUCCESS("ui_success", 0.9f),
    ERROR("ui_error", 0.9f),
    TRASH("ui_trash", 0.85f),
    RESTORE("ui_restore", 0.85f),
    LAND("ui_land", 0.8f),
    SELECT("ui_select", 0.6f),
    POP("ui_pop", 0.7f),
    ATTENTION("ui_attention", 0.85f),
    UNLOCK("ui_unlock", 0.85f),
    LOCK("ui_lock", 0.85f),
    COMPLETE("ui_complete", 1f),
}

enum class RingerMode { BLOCKED, SILENT, VIBRATE, NORMAL }

interface SoundDevice {
    val ringer: RingerMode
    val foreground: Boolean
    fun play(sound: UiSound, loud: Boolean)
}

class SoundPolicy(private val minGapMillis: Long = 90L, private val sameGapMillis: Long = 400L) {

    private var lastAny = Long.MIN_VALUE / 2
    private val lastBySound = HashMap<UiSound, Long>()

    @Synchronized
    fun allow(sound: UiSound, enabled: Boolean, ringer: RingerMode, now: Long, inSilent: Boolean = false): Boolean {
        if (!enabled || ringer == RingerMode.BLOCKED) return false
        if (ringer != RingerMode.NORMAL && !inSilent) return false
        if (now - lastAny < minGapMillis) return false
        val previous = lastBySound[sound]
        if (previous != null && now - previous < sameGapMillis) return false
        lastAny = now
        lastBySound[sound] = now
        return true
    }

    companion object {
        fun forTask(title: String, failed: Boolean, okCount: Int): UiSound? {
            if (failed) return UiSound.ERROR
            if (okCount <= 0) return null
            val t = title.lowercase()
            return when {
                t.startsWith("delet") || t.startsWith("moving to") || t.contains("recycle") -> UiSound.TRASH
                t.startsWith("restor") -> UiSound.RESTORE
                t.startsWith("encrypt") -> UiSound.LOCK
                t.startsWith("decrypt") -> UiSound.UNLOCK
                t.startsWith("copy") || t.startsWith("mov") || t.startsWith("past") || t.startsWith("upload") || t.startsWith("download") || t.startsWith("extract") -> UiSound.LAND
                else -> UiSound.SUCCESS
            }
        }
    }
}

object UiSounds {

    @Volatile
    var enabled = false

    @Volatile
    var inSilent = true

    @Volatile
    var device: SoundDevice? = null

    @Volatile
    var clock: () -> Long = { System.nanoTime() / 1_000_000 }

    @Volatile
    private var policy = SoundPolicy()

    fun play(sound: UiSound?) {
        val d = device ?: return
        if (sound == null || !d.foreground) return
        val ringer = d.ringer
        if (policy.allow(sound, enabled, ringer, clock(), inSilent)) d.play(sound, loud = ringer != RingerMode.NORMAL)
    }

    fun forTask(title: String, failed: Boolean, okCount: Int) = play(SoundPolicy.forTask(title, failed, okCount))

    fun reset() {
        policy = SoundPolicy()
    }
}
