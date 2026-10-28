package dev.cluvex.strata.feature.media

import android.content.Context
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.nio.ByteBuffer

/**
 * Renderers whose own sound can be silenced while still running: [mute] makes the audio output play silence in place
 * of the sound, so the audio clock (and with it the picture) carries on exactly as before. This is used while another
 * player supplies the sound, which is how the sync and an audio file from storage are done.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AudioSyncRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    @Volatile var mute: Boolean = false

    private val muteProcessor = MuteAudioProcessor { mute }

    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink? =
        DefaultAudioSink.Builder(context)
            .setAudioProcessors(arrayOf<AudioProcessor>(muteProcessor))
            .build()
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class MuteAudioProcessor(private val mute: () -> Boolean) : BaseAudioProcessor() {
    private var zeros = ByteArray(0)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat = inputAudioFormat

    override fun queueInput(inputBuffer: ByteBuffer) {
        val n = inputBuffer.remaining()
        if (n == 0) return
        val out = replaceOutputBuffer(n)
        if (mute()) {
            inputBuffer.position(inputBuffer.limit())
            if (zeros.size < n) zeros = ByteArray(n)
            out.put(zeros, 0, n)
        } else {
            out.put(inputBuffer)
        }
        out.flip()
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
