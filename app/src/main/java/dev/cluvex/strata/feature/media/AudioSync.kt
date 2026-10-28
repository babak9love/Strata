package dev.cluvex.strata.feature.media

import android.content.Context
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * Renderers whose audio output is moved against the picture by [offsetUs] (positive = the sound is heard later
 * than the picture, negative = earlier). Only the sound moves: the picture and the player's position keep their
 * own timeline. Works for the video's own audio track and for an audio file added from storage alike.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AudioSyncRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    private val delay = AudioDelayProcessor()

    /** Takes effect the next time the audio is flushed (a seek does that). */
    var offsetUs: Long
        get() = delay.offsetUs
        set(value) { delay.offsetUs = value }

    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink? =
        DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf(delay))
            .build()
}

/**
 * Moves the sound by changing the audio itself, not the player's clock:
 * - offset > 0 (sound later): silence is put in front of the audio after every start or seek;
 * - offset < 0 (sound earlier): that much of the beginning of the audio is dropped after every start or seek.
 * The audio sink counts the silence (or the missing part) in the position it reports, so the picture follows
 * on its own and never jumps to the offset, and nothing here touches the video.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class AudioDelayProcessor : BaseAudioProcessor() {
    @Volatile var offsetUs: Long = 0L

    private var silenceBytes = 0L
    private var skipBytes = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat = inputAudioFormat

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        val offset = offsetUs
        val format = inputAudioFormat
        val bytes = abs(offset) * format.sampleRate / 1_000_000L * format.bytesPerFrame
        silenceBytes = if (offset > 0) bytes else 0L
        skipBytes = if (offset < 0) bytes else 0L
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        var remaining = inputBuffer.remaining()
        if (remaining == 0) return
        if (skipBytes > 0L) {
            val drop = minOf(skipBytes, remaining.toLong()).toInt()
            inputBuffer.position(inputBuffer.position() + drop)
            skipBytes -= drop
            remaining -= drop
            if (remaining == 0) return
        }
        val silence = silenceBytes.toInt()
        silenceBytes = 0L
        val out = replaceOutputBuffer(silence + remaining)
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
