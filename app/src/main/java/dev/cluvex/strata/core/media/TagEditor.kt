package dev.cluvex.strata.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import dev.cluvex.strata.core.nativebridge.NativeBridge
import java.io.ByteArrayOutputStream

/** The tags the editor shows. [track] and [disc] are "n" or "n/total". */
data class AudioTags(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val albumArtist: String = "",
    val year: String = "",
    val track: String = "",
    val disc: String = "",
    val genre: String = "",
    val comment: String = "",
    val format: String = "",
)

sealed interface CoverChange {
    data object Keep : CoverChange
    data object Remove : CoverChange
    class Replace(val jpeg: ByteArray) : CoverChange
}

/** Reads and writes the tags of an audio file (MP3, FLAC, OGG, Opus, M4A, WAV, ...) through the native `lofty` module. */
object TagEditor {

    /** Blocking: call it off the main thread. Null if the file cannot be read. */
    fun read(path: String): AudioTags? {
        val f = NativeBridge.readTags(path) ?: return null
        if (f.size < 10) return null
        return AudioTags(f[0], f[1], f[2], f[3], f[4], f[5], f[6], f[7], f[8], f[9])
    }

    fun readCover(path: String): ByteArray? = NativeBridge.readTagsCover(path)

    /** Blocking. Returns null on success, otherwise the error text. */
    fun write(path: String, tags: AudioTags, cover: CoverChange): String? {
        val fields = arrayOf(
            tags.title, tags.artist, tags.album, tags.albumArtist, tags.year,
            tags.track, tags.disc, tags.genre, tags.comment, "",
        )
        return when (cover) {
            CoverChange.Keep -> NativeBridge.writeTags(path, fields, 0, null)
            CoverChange.Remove -> NativeBridge.writeTags(path, fields, 1, null)
            is CoverChange.Replace -> NativeBridge.writeTags(path, fields, 2, cover.jpeg)
        }
    }

    /** Reads a picked image and shrinks it (longest side at most [MAX_SIDE]) to a JPEG so the audio file does not balloon. */
    fun prepareCover(context: Context, uri: Uri): ByteArray? = runCatching {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val scaled = if (maxOf(bmp.width, bmp.height) > MAX_SIDE) {
            val k = MAX_SIDE.toFloat() / maxOf(bmp.width, bmp.height)
            Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt().coerceAtLeast(1), (bmp.height * k).toInt().coerceAtLeast(1), true)
        } else bmp
        ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.toByteArray()
        }
    }.getOrNull()

    private const val MAX_SIDE = 1200
}
