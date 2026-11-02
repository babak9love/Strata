package dev.cluvex.strata.core.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.request.Options
import dev.cluvex.strata.core.nativebridge.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** The cover picture embedded in an audio file, as a file-list thumbnail. [modified] keeps the cache honest after a tag edit. */
data class AudioCover(val path: String, val modified: Long)

object AudioCovers {

    const val SIZE = 384

    /** Files already known to have no cover, so scrolling a long folder does not parse them again and again. */
    private val withoutCover = object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean = size > 3000
    }

    /** True once a load found no cover in this file (cheap, safe on the main thread). Lets a grid tile use the normal icon layout. */
    fun knownWithoutCover(path: String, modified: Long): Boolean = synchronized(withoutCover) { withoutCover.containsKey("$path|$modified") }

    /** Null when the file has no (readable) cover. Blocking: call it off the main thread. */
    fun load(path: String, modified: Long, size: Int = SIZE): Bitmap? {
        val key = "$path|$modified"
        if (synchronized(withoutCover) { withoutCover.containsKey(key) }) return null
        val bitmap = embedded(path)?.let { decode(it, size) }
        if (bitmap == null) synchronized(withoutCover) { withoutCover[key] = true }
        return bitmap
    }

    /** The phone's own parser first (fast, covers MP3 / M4A / FLAC / OGG), then the native tag reader for what it cannot open. */
    private fun embedded(path: String): ByteArray? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            retriever.embeddedPicture?.let { return it }
        } catch (_: Throwable) {
        } finally {
            runCatching { retriever.release() }
        }
        return NativeBridge.readTagsCover(path)
    }

    private fun decode(bytes: ByteArray, size: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= size) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()
}

private val gate = Semaphore(3)

class AudioCoverFetcher(private val data: AudioCover) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val bitmap = gate.withPermit { withContext(Dispatchers.IO) { AudioCovers.load(data.path, data.modified) } } ?: return null
        return ImageFetchResult(image = bitmap.asImage(), isSampled = true, dataSource = DataSource.DISK)
    }

    class Factory : Fetcher.Factory<AudioCover> {
        override fun create(data: AudioCover, options: Options, imageLoader: ImageLoader): Fetcher = AudioCoverFetcher(data)
    }
}

class AudioCoverKeyer : Keyer<AudioCover> {
    override fun key(data: AudioCover, options: Options): String = "audio-cover:${data.path}:${data.modified}"
}
