package dev.cluvex.strata.feature.media

import android.content.Context
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer

/**
 * Renderers whose audio output can be moved against the picture: [delayUs] positive plays the sound later,
 * negative earlier. Only the sound moves; the clock, the position and the picture stay where they are.
 * [endUs] (the length of the video, 0 = unknown) keeps a delayed sound from running on after the video has ended.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AudioSyncRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    @Volatile var delayUs: Long = 0L
    @Volatile var endUs: Long = 0L

    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink? {
        val base = super.buildAudioSink(context, enableFloatOutput, enableAudioTrackPlaybackParams) ?: return null
        return DelayingAudioSink(base, { delayUs }, { endUs })
    }
}

/**
 * Delays or advances the sound by changing what is written to the audio output, not the clock:
 * - delayed: a stretch of silence is written first, and the real audio is labelled that much later, so the
 *   output's own clock runs on as before and the real audio is simply heard later;
 * - advanced: the first part of the audio is dropped and the rest is labelled earlier.
 * It starts again after every seek (the output is flushed then). Only raw PCM audio is moved; other audio
 * (passthrough) plays as it is.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class DelayingAudioSink(
    base: AudioSink,
    private val delayUs: () -> Long,
    private val endUs: () -> Long,
) : ForwardingAudioSink(base) {
    private var pcm = false
    private var frameSize = 0
    private var sampleRate = 0

    private var started = false
    private var activeDelayUs = 0L
    private var silenceLeft = 0L
    private var skipLeft = 0L
    private var firstPts = 0L
    private var silenceWritten = 0L
    private var pendingSilence: ByteBuffer? = null
    private var silencePts = 0L
    private var silenceBuffer: ByteBuffer? = null

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        pcm = inputFormat.sampleMimeType == MimeTypes.AUDIO_RAW
        frameSize = if (pcm) Util.getPcmFrameSize(inputFormat.pcmEncoding, inputFormat.channelCount) else 0
        sampleRate = inputFormat.sampleRate
        restart()
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun flush() {
        restart()
        super.flush()
    }

    override fun reset() {
        restart()
        super.reset()
    }

    private fun restart() {
        started = false
        silenceLeft = 0L
        skipLeft = 0L
        pendingSilence = null
    }

    private fun bytesToUs(bytes: Long): Long = bytes / frameSize * 1_000_000L / sampleRate

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        if (!started) {
            started = true
            activeDelayUs = delayUs()
            silenceWritten = 0L
            firstPts = presentationTimeUs
            if (activeDelayUs != 0L && pcm && frameSize > 0 && sampleRate > 0) {
                val bytes = Math.abs(activeDelayUs) * sampleRate / 1_000_000L * frameSize
                if (activeDelayUs > 0) silenceLeft = bytes else skipLeft = bytes
            } else {
                activeDelayUs = 0L
            }
        }
        if (activeDelayUs == 0L) return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)

        var ptsShift = 0L
        if (skipLeft > 0L) {
            val n = minOf(skipLeft, buffer.remaining().toLong()).toInt()
            buffer.position(buffer.position() + n)
            skipLeft -= n
            if (!buffer.hasRemaining()) return true
            ptsShift = bytesToUs(n.toLong())
        }
        while (silenceLeft > 0L || pendingSilence != null) {
            val z = pendingSilence ?: run {
                val chunk = (SILENCE_CHUNK / frameSize) * frameSize
                val n = minOf(silenceLeft, chunk.toLong()).toInt()
                val b = silenceBuffer ?: ByteBuffer.allocateDirect(chunk).also { silenceBuffer = it }
                b.clear()
                b.limit(n)
                silenceLeft -= n
                silencePts = firstPts + bytesToUs(silenceWritten)
                silenceWritten += n
                b
            }
            if (!super.handleBuffer(z, silencePts, 1)) {
                pendingSilence = z
                return false
            }
            pendingSilence = null
        }
        val out = presentationTimeUs + ptsShift + activeDelayUs
        val end = endUs()
        if (end > 0L && out >= end) {
            buffer.position(buffer.limit())
            return true
        }
        return super.handleBuffer(buffer, out, encodedAccessUnitCount)
    }

    private companion object {
        const val SILENCE_CHUNK = 16_384
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
