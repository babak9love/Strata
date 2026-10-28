package dev.cluvex.strata.feature.media

import android.content.Context
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.nio.ByteBuffer

/**
 * Renderers whose audio output can be moved against the picture: [delayUs] positive plays the sound later,
 * negative earlier. Only the sound moves; the clock, the position and the picture stay where they are.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AudioSyncRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    @Volatile var delayUs: Long = 0L

    private val delayProcessor = DelayAudioProcessor { delayUs }

    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink? =
        DefaultAudioSink.Builder(context)
            .setAudioProcessors(arrayOf<AudioProcessor>(delayProcessor))
            .build()
}

/**
 * Delays or advances the sound by changing the audio itself, not the clock:
 * - delayed: a stretch of silence is put in front of the audio, so the output's own clock runs on as before and
 *   the real audio is simply heard later;
 * - advanced: the first part of the audio is dropped.
 * It starts again after every seek (the audio is flushed then), reading the value that is set at that moment.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class DelayAudioProcessor(private val delayUs: () -> Long) : BaseAudioProcessor() {
    private var sampleRate = 0
    private var frameSize = 0
    private var started = false
    private var silenceLeft = 0L
    private var skipLeft = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        sampleRate = inputAudioFormat.sampleRate
        frameSize = inputAudioFormat.bytesPerFrame
        return inputAudioFormat
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        started = false
        silenceLeft = 0L
        skipLeft = 0L
    }

    override fun onReset() {
        started = false
        silenceLeft = 0L
        skipLeft = 0L
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!started) {
            started = true
            val delay = delayUs()
            val bytes = Math.abs(delay) * sampleRate / 1_000_000L * frameSize
            if (delay > 0L) silenceLeft = bytes else if (delay < 0L) skipLeft = bytes
        }
        if (skipLeft > 0L) {
            val n = minOf(skipLeft, inputBuffer.remaining().toLong()).toInt()
            inputBuffer.position(inputBuffer.position() + n)
            skipLeft -= n
        }
        val input = inputBuffer.remaining()
        if (input == 0) return
        val silence = silenceLeft.toInt()
        silenceLeft = 0L
        val out = replaceOutputBuffer(silence + input)
        if (silence > 0) out.put(ByteArray(silence))
        out.put(inputBuffer)
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
