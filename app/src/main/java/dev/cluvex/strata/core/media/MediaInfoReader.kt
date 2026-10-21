package dev.cluvex.strata.core.media

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.annotation.StringRes
import dev.cluvex.strata.R
import java.util.Locale

/** One labelled value in the media details list. */
data class MediaRow(@StringRes val label: Int, val value: String)

/** Rows grouped under a section heading. */
data class MediaSection(@StringRes val title: Int, val rows: List<MediaRow>)

/**
 * Reads technical details and tags of an audio/video file by parsing its header only
 * (nothing is played). Uses the framework MediaExtractor + MediaMetadataRetriever, so it
 * works offline on every supported API level; some fields (e.g. bit depth) need newer Android.
 */
object MediaInfoReader {

    fun read(path: String): List<MediaSection> {
        val general = mutableListOf<MediaRow>()
        val audio = mutableListOf<MediaRow>()
        val video = mutableListOf<MediaRow>()
        val tags = mutableListOf<MediaRow>()

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            fun meta(key: Int): String? = runCatching { retriever.extractMetadata(key) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

            meta(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)?.let { general += MediaRow(R.string.media_format, it) }
            meta(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 }?.let {
                general += MediaRow(R.string.media_duration, duration(it))
            }
            meta(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?.takeIf { it > 0 }?.let {
                general += MediaRow(R.string.media_bitrate, kbps(it))
            }

            fun tag(label: Int, key: Int) { meta(key)?.let { tags += MediaRow(label, it) } }
            tag(R.string.media_title, MediaMetadataRetriever.METADATA_KEY_TITLE)
            tag(R.string.media_artist, MediaMetadataRetriever.METADATA_KEY_ARTIST)
            tag(R.string.media_album, MediaMetadataRetriever.METADATA_KEY_ALBUM)
            tag(R.string.media_album_artist, MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            tag(R.string.media_genre, MediaMetadataRetriever.METADATA_KEY_GENRE)
            tag(R.string.media_year, MediaMetadataRetriever.METADATA_KEY_YEAR)
            if (meta(MediaMetadataRetriever.METADATA_KEY_YEAR) == null) tag(R.string.media_year, MediaMetadataRetriever.METADATA_KEY_DATE)
            tag(R.string.media_track, MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
            tag(R.string.media_disc, MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)
            tag(R.string.media_composer, MediaMetadataRetriever.METADATA_KEY_COMPOSER)
            tag(R.string.media_writer, MediaMetadataRetriever.METADATA_KEY_WRITER)
            tag(R.string.media_location, MediaMetadataRetriever.METADATA_KEY_LOCATION)

            val bitsPerSample = if (Build.VERSION.SDK_INT >= 31) meta(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull() else null
            val captureFps = if (Build.VERSION.SDK_INT >= 23) meta(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE) else null

            readTracks(path, audio, video, bitsPerSample, captureFps)
        } catch (_: Throwable) {
            // Unreadable / unsupported file: show whatever was collected.
        } finally {
            runCatching { retriever.release() }
        }

        return buildList {
            if (general.isNotEmpty()) add(MediaSection(R.string.media_section_general, general))
            if (audio.isNotEmpty()) add(MediaSection(R.string.media_section_audio, audio))
            if (video.isNotEmpty()) add(MediaSection(R.string.media_section_video, video))
            if (tags.isNotEmpty()) add(MediaSection(R.string.media_section_tags, tags))
        }
    }

    private fun readTracks(path: String, audio: MutableList<MediaRow>, video: MutableList<MediaRow>, bitsPerSample: Int?, captureFps: String?) {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(path)
            var audioDone = false
            var videoDone = false
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.str(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/") && !audioDone) {
                    audioDone = true
                    audio += MediaRow(R.string.media_codec, codecName(mime))
                    f.int(MediaFormat.KEY_CHANNEL_COUNT)?.let { audio += MediaRow(R.string.media_channels, channels(it)) }
                    f.int(MediaFormat.KEY_SAMPLE_RATE)?.let { audio += MediaRow(R.string.media_sample_rate, hz(it)) }
                    f.int(MediaFormat.KEY_BIT_RATE)?.takeIf { it > 0 }?.let { audio += MediaRow(R.string.media_bitrate, kbps(it.toLong())) }
                    val depth = bitsPerSample?.takeIf { it > 0 } ?: pcmDepth(f)
                    depth?.let { audio += MediaRow(R.string.media_bit_depth, "$it bit") }
                } else if (mime.startsWith("video/") && !videoDone) {
                    videoDone = true
                    video += MediaRow(R.string.media_codec, codecName(mime))
                    val w = f.int(MediaFormat.KEY_WIDTH)
                    val h = f.int(MediaFormat.KEY_HEIGHT)
                    if (w != null && h != null) {
                        val rot = if (android.os.Build.VERSION.SDK_INT >= 23) f.int(MediaFormat.KEY_ROTATION) ?: 0 else 0
                        val (rw, rh) = if (rot == 90 || rot == 270) h to w else w to h
                        video += MediaRow(R.string.media_resolution, "$rw × $rh")
                    }
                    val fps = f.int(MediaFormat.KEY_FRAME_RATE)?.toDouble()
                        ?: f.float(MediaFormat.KEY_FRAME_RATE)?.toDouble()
                        ?: captureFps?.toDoubleOrNull()
                    fps?.takeIf { it > 0 }?.let { video += MediaRow(R.string.media_frame_rate, trim(it) + " fps") }
                    f.int(MediaFormat.KEY_BIT_RATE)?.takeIf { it > 0 }?.let { video += MediaRow(R.string.media_bitrate, kbps(it.toLong())) }
                }
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { ex.release() }
        }
    }

    private fun MediaFormat.str(k: String): String? = if (containsKey(k)) runCatching { getString(k) }.getOrNull() else null
    private fun MediaFormat.int(k: String): Int? = if (containsKey(k)) runCatching { getInteger(k) }.getOrNull() else null
    private fun MediaFormat.float(k: String): Float? = if (containsKey(k)) runCatching { getFloat(k) }.getOrNull() else null

    private fun pcmDepth(f: MediaFormat): Int? {
        if (Build.VERSION.SDK_INT < 24) return null
        return when (f.int(MediaFormat.KEY_PCM_ENCODING)) {
            3 -> 8      // ENCODING_PCM_8BIT
            2 -> 16     // ENCODING_PCM_16BIT
            21 -> 24    // ENCODING_PCM_24BIT_PACKED
            22, 4 -> 32 // ENCODING_PCM_32BIT / FLOAT
            else -> null
        }
    }

    private fun codecName(mime: String): String = mime.substringAfter('/').let {
        when (it.lowercase()) {
            "mp4a-latm" -> "AAC"
            "mpeg" -> "MP3"
            "avc" -> "H.264 (AVC)"
            "hevc" -> "H.265 (HEVC)"
            "av01" -> "AV1"
            "x-vnd.on2.vp9" -> "VP9"
            "x-vnd.on2.vp8" -> "VP8"
            "mp4v-es" -> "MPEG-4 Visual"
            "3gpp" -> if (mime.startsWith("video")) "H.263" else "AMR-NB"
            "amr-wb" -> "AMR-WB"
            "opus" -> "Opus"
            "vorbis" -> "Vorbis"
            "flac" -> "FLAC"
            "raw" -> "PCM"
            "ac3" -> "AC-3"
            "eac3" -> "E-AC-3"
            else -> it.uppercase()
        }
    }

    private fun channels(n: Int) = when (n) { 1 -> "1 (Mono)"; 2 -> "2 (Stereo)"; 6 -> "6 (5.1)"; 8 -> "8 (7.1)"; else -> n.toString() }
    private fun kbps(bps: Long) = "${trim(bps / 1000.0)} kbps"
    private fun hz(v: Int) = if (v % 1000 == 0) "${v / 1000} kHz" else "${trim(v / 1000.0)} kHz ($v Hz)"
    private fun trim(d: Double): String = if (d == Math.floor(d)) d.toLong().toString() else String.format(Locale.US, "%.2f", d).trimEnd('0').trimEnd('.')

    private fun duration(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%d:%02d", m, sec)
    }
}
