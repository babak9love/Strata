package dev.cluvex.strata.feature.media

import android.content.Context
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink

/**
 * Renderers whose audio output reports its position shifted by [offsetUs] (positive = the sound is heard later
 * than the picture, negative = earlier). The picture follows the audio clock, so shifting that clock moves the
 * sound against the picture without touching the media itself. Works for the video's own audio track and for an
 * audio file added from storage alike.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AudioSyncRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    @Volatile var offsetUs: Long = 0L

    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink? {
        val base = super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams) ?: return null
        return object : ForwardingAudioSink(base) {
            override fun getCurrentPositionUs(sourceEnded: Boolean): Long {
                val position = super.getCurrentPositionUs(sourceEnded)
                return if (position == AudioSink.CURRENT_POSITION_NOT_SET) position else position + offsetUs
            }
        }
    }
}

/** The audio sync (milliseconds, positive = sound later) the user set for each video, kept between runs. */
object AudioOffsets {
    private const val FILE = "audio_offsets"
    private const val MAX_ENTRIES = 500
    const val MAX_MS = 10_000

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun get(ctx: Context, key: String): Int = prefs(ctx).getInt(key, 0)

    fun save(ctx: Context, key: String, offsetMs: Int) {
        val p = prefs(ctx)
        val edit = p.edit()
        if (p.all.size >= MAX_ENTRIES && !p.contains(key)) edit.clear()
        if (offsetMs == 0) edit.remove(key) else edit.putInt(key, offsetMs)
        edit.apply()
    }
}
