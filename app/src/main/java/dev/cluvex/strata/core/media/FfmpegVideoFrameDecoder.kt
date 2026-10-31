package dev.cluvex.strata.core.media

import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.video.VideoFrameDecoder
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okio.FileSystem

/**
 * Video thumbnails: the phone's own decoder first (Coil's [VideoFrameDecoder], hardware, fast) and, when it cannot
 * open the video (a codec the phone lacks), a frame decoded by FFmpeg, the same decoder that plays it.
 */
class FfmpegVideoFrameDecoder private constructor(
    private val inner: Decoder,
    private val result: SourceFetchResult,
) : Decoder {

    override suspend fun decode(): DecodeResult? {
        val failure: Exception = try {
            return inner.decode()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e
        }
        val source = result.source
        // Only a plain file on the device can be read by FFmpeg; anything else keeps the original failure.
        if (source.fileSystem !== FileSystem.SYSTEM) throw failure
        val path = source.file().toString()
        // Software decoding is heavy: a list full of such videos must not decode them all at once.
        val bitmap = gate.withPermit { withContext(Dispatchers.IO) { MediaInfoReader.ffmpegFrame(path, MAX_SIDE) } }
            ?: throw failure
        return DecodeResult(image = bitmap.asImage(), isSampled = true)
    }

    class Factory(private val inner: Decoder.Factory = VideoFrameDecoder.Factory()) : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
            val decoder = inner.create(result, options, imageLoader) ?: return null
            return FfmpegVideoFrameDecoder(decoder, result)
        }
    }

    private companion object {
        const val MAX_SIDE = 512
        val gate = Semaphore(2)
    }
}
