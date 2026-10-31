package dev.cluvex.strata.core.media

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import androidx.annotation.StringRes
import dev.cluvex.strata.R
import io.github.anilbeesetti.nextlib.mediainfo.MediaInfo
import io.github.anilbeesetti.nextlib.mediainfo.MediaInfoBuilder
import io.github.anilbeesetti.nextlib.mediainfo.MediaThumbnailRetriever
import java.io.File
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** One labelled value in the media details list. [labelText] replaces [label] when the label is built at runtime (a chapter time, "Subtitle 2"). */
data class MediaRow(@StringRes val label: Int, val value: String, val labelText: String? = null)

/** Rows grouped under a section heading. [subtitle] is appended to the heading ("Track 2 · English"). */
data class MediaSection(@StringRes val title: Int, val rows: List<MediaRow>, val subtitle: String? = null)

/**
 * Reads technical details and tags of an audio/video file by parsing its header only
 * (nothing is played). FFmpeg (NextLib MediaInfo) lists every audio track, the subtitles and the chapters
 * and opens containers Android does not know; the framework MediaExtractor + MediaMetadataRetriever add the tags and
 * the bit depth and are the fallback when FFmpeg cannot read the file. Some fields (e.g. bit depth) need newer Android.
 */
object MediaInfoReader {

    /** "W × H" of an image (EXIF orientation applied), read from the header only; null if unknown. */
    fun imageResolution(path: String): String? = try {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(path, o)
        var w = o.outWidth
        var h = o.outHeight
        if (w > 0 && h > 0) {
            val rot = when (runCatching { android.media.ExifInterface(path).getAttributeInt("Orientation", 1) }.getOrDefault(1)) { 5, 6, 7, 8 -> true; else -> false }
            if (rot) { val t = w; w = h; h = t }
            "$w × $h"
        } else null
    } catch (_: Throwable) {
        null
    }

    private const val DISP_DEFAULT = 0x1
    private const val DISP_DUB = 0x2
    private const val DISP_ORIGINAL = 0x4
    private const val DISP_COMMENT = 0x8
    private const val DISP_LYRICS = 0x10
    private const val DISP_FORCED = 0x40
    private const val DISP_HEARING_IMPAIRED = 0x80
    private const val DISP_VISUAL_IMPAIRED = 0x100
    private const val DISP_ATTACHED_PIC = 0x400

    /** FFmpeg frame decoding allocates a full-size bitmap; skip it above this many pixels (about 4K). */
    private const val MAX_FFMPEG_FRAME_PIXELS = 10_000_000

    /** Recent results, so reopening the same file does not parse it again. */
    private val cache = object : LinkedHashMap<String, List<MediaSection>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<MediaSection>>?): Boolean = size > 24
    }

    /**
     * Details of [path]. With [res] the stream flags and the numbered labels are localised; without it they are left out.
     * Blocking: call it off the main thread.
     */
    fun read(path: String, res: Resources? = null): List<MediaSection> {
        val file = File(path)
        val key = "$path|${file.lastModified()}|${file.length()}|${Locale.getDefault()}|${res != null}"
        synchronized(cache) { cache[key] }?.let { return it }
        val result = readUncached(path, res)
        if (result.isNotEmpty()) synchronized(cache) { cache[key] = result }
        return result
    }

    private fun readUncached(path: String, res: Resources?): List<MediaSection> {
        var mime: String? = null
        var durationMs: Long? = null
        var overallBitrate: Long? = null
        var bitsPerSample: Int? = null
        var captureFps: String? = null
        val tags = mutableListOf<MediaRow>()

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            fun meta(key: Int): String? = runCatching { retriever.extractMetadata(key) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

            mime = meta(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            durationMs = meta(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0 }
            overallBitrate = meta(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?.takeIf { it > 0 }

            fun tag(label: Int, key: Int) { meta(key)?.let { tags += MediaRow(label, it) } }
            tag(R.string.media_title, MediaMetadataRetriever.METADATA_KEY_TITLE)
            tag(R.string.media_artist, MediaMetadataRetriever.METADATA_KEY_ARTIST)
            tag(R.string.media_album, MediaMetadataRetriever.METADATA_KEY_ALBUM)
            tag(R.string.media_album_artist, MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            tag(R.string.media_genre, MediaMetadataRetriever.METADATA_KEY_GENRE)
            tag(R.string.media_year, MediaMetadataRetriever.METADATA_KEY_YEAR)
            // The creation date, readable. Files without one carry a placeholder (1904 in MP4, 1970), which is dropped.
            dateText(meta(MediaMetadataRetriever.METADATA_KEY_DATE))?.let { tags += MediaRow(R.string.media_date, it) }
            tag(R.string.media_track, MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)
            tag(R.string.media_disc, MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)
            tag(R.string.media_composer, MediaMetadataRetriever.METADATA_KEY_COMPOSER)
            tag(R.string.media_writer, MediaMetadataRetriever.METADATA_KEY_WRITER)
            tag(R.string.media_location, MediaMetadataRetriever.METADATA_KEY_LOCATION)

            bitsPerSample = if (Build.VERSION.SDK_INT >= 31) meta(MediaMetadataRetriever.METADATA_KEY_BITS_PER_SAMPLE)?.toIntOrNull() else null
            captureFps = if (Build.VERSION.SDK_INT >= 23) meta(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE) else null
        } catch (_: Throwable) {
            // Unreadable / unsupported file: show whatever was collected.
        } finally {
            runCatching { retriever.release() }
        }

        // The framework's view of the tracks: the fallback, and the source of the bit depth.
        val fwAudio = mutableListOf<MediaRow>()
        val fwVideo = mutableListOf<MediaRow>()
        readTracks(path, fwAudio, fwVideo, bitsPerSample, captureFps)

        val ff = probe(path)
        val general = mutableListOf<MediaRow>()
        if (ff != null) {
            general += MediaRow(R.string.media_format, ff.format)
            mime?.let { general += MediaRow(R.string.media_mime_type, it) }
            (ff.duration.takeIf { it > 0 } ?: durationMs)?.let { general += MediaRow(R.string.media_duration, duration(it)) }
        } else {
            mime?.let { general += MediaRow(R.string.media_format, it) }
            durationMs?.let { general += MediaRow(R.string.media_duration, duration(it)) }
        }
        overallBitrate?.let { general += MediaRow(R.string.media_bitrate, kbps(it)) }

        val streams: List<MediaSection> = try {
            if (ff != null) ffmpegSections(ff, fwAudio, fwVideo, res) else frameworkSections(fwAudio, fwVideo)
        } catch (_: Throwable) {
            frameworkSections(fwAudio, fwVideo)
        } finally {
            runCatching { ff?.release() }
        }

        return buildList {
            if (general.isNotEmpty()) add(MediaSection(R.string.media_section_general, general))
            addAll(streams)
            if (tags.isNotEmpty()) add(MediaSection(R.string.media_section_tags, tags))
        }
    }

    private fun frameworkSections(audio: List<MediaRow>, video: List<MediaRow>): List<MediaSection> = buildList {
        if (audio.isNotEmpty()) add(MediaSection(R.string.media_section_audio, audio))
        if (video.isNotEmpty()) add(MediaSection(R.string.media_section_video, video))
    }

    /** Opens [path] with FFmpeg; null when FFmpeg cannot read it (or its libraries are missing). */
    private fun probe(path: String): MediaInfo? = try {
        MediaInfoBuilder().from(path).build()
    } catch (_: Throwable) {
        null
    }

    private fun ffmpegSections(ff: MediaInfo, fwAudio: List<MediaRow>, fwVideo: List<MediaRow>, res: Resources?): List<MediaSection> {
        val out = mutableListOf<MediaSection>()

        // Every audio track gets its own section.
        val audioStreams = ff.audioStreams
        if (audioStreams.isEmpty()) {
            if (fwAudio.isNotEmpty()) out += MediaSection(R.string.media_section_audio, fwAudio)
        }
        audioStreams.forEachIndexed { i, a ->
            val rows = mutableListOf<MediaRow>()
            a.title.clean()?.let { rows += MediaRow(R.string.media_title, it) }
            rows += MediaRow(R.string.media_codec, a.codecName)
            if (a.channels > 0) rows += MediaRow(R.string.media_channels, channelsText(a.channels, a.channelLayout))
            if (a.sampleRate > 0) rows += MediaRow(R.string.media_sample_rate, hz(a.sampleRate))
            sampleFormat(a.sampleFormat)?.let { rows += MediaRow(R.string.media_sample_format, it) }
            if (a.bitRate > 0) rows += MediaRow(R.string.media_bitrate, kbps(a.bitRate))
            // Bit depth is only known for the first track, from the framework reader.
            if (i == 0) fwAudio.firstOrNull { it.label == R.string.media_bit_depth }?.let { rows += it }
            val lang = languageName(a.language)
            lang?.let { rows += MediaRow(R.string.media_language, it) }
            flags(a.disposition, res)?.let { rows += MediaRow(R.string.media_flags, it) }
            val sub = if (audioStreams.size > 1) listOfNotNull(res?.getString(R.string.media_audio_track_n, i + 1), lang).joinToString(" · ") else null
            out += MediaSection(R.string.media_section_audio, rows, sub?.takeIf { it.isNotEmpty() })
        }

        // Video: FFmpeg only reports the first video stream. An attached picture is cover art, not a video.
        val v = ff.videoStream
        if (v != null && (v.disposition and DISP_ATTACHED_PIC) == 0) {
            val rows = mutableListOf<MediaRow>()
            v.title.clean()?.let { rows += MediaRow(R.string.media_title, it) }
            rows += MediaRow(R.string.media_codec, v.codecName)
            if (v.frameWidth > 0 && v.frameHeight > 0) {
                val swap = v.rotation == 90 || v.rotation == 270
                val w = if (swap) v.frameHeight else v.frameWidth
                val h = if (swap) v.frameWidth else v.frameHeight
                rows += MediaRow(R.string.media_resolution, "$w × $h")
                rows += MediaRow(R.string.media_aspect_ratio, aspectRatio(w, h))
            }
            if (v.frameRate > 0) rows += MediaRow(R.string.media_frame_rate, trim(v.frameRate) + " fps")
            if (v.bitRate > 0) rows += MediaRow(R.string.media_bitrate, kbps(v.bitRate))
            else fwVideo.firstOrNull { it.label == R.string.media_bitrate }?.let { rows += it }
            if (v.rotation != 0) rows += MediaRow(R.string.media_rotation, "${v.rotation}°")
            languageName(v.language)?.let { rows += MediaRow(R.string.media_language, it) }
            flags(v.disposition, res)?.let { rows += MediaRow(R.string.media_flags, it) }
            out += MediaSection(R.string.media_section_video, rows)
        } else if (v != null) {
            // The first "video" stream is the cover picture; the framework reader may still find a real video track.
            if (fwVideo.isNotEmpty()) out += MediaSection(R.string.media_section_video, fwVideo)
            val rows = mutableListOf<MediaRow>()
            rows += MediaRow(R.string.media_codec, v.codecName)
            if (v.frameWidth > 0 && v.frameHeight > 0) rows += MediaRow(R.string.media_resolution, "${v.frameWidth} × ${v.frameHeight}")
            out += MediaSection(R.string.media_section_cover, rows)
        } else if (fwVideo.isNotEmpty()) {
            out += MediaSection(R.string.media_section_video, fwVideo)
        }

        // Subtitle tracks.
        if (ff.subtitleStreams.isNotEmpty()) {
            val rows = ff.subtitleStreams.mapIndexed { i, st ->
                val value = listOfNotNull(languageName(st.language), st.codecName, st.title.clean(), flags(st.disposition, res)).joinToString(" · ")
                MediaRow(R.string.media_subtitles, value, labelText = res?.getString(R.string.media_subtitle_track_n, i + 1) ?: "#${i + 1}")
            }
            out += MediaSection(R.string.media_section_subtitles, rows)
        }

        // Chapters: start time and title.
        if (ff.chapters.isNotEmpty()) {
            val rows = ff.chapters.mapIndexed { i, c ->
                val name = c.title.clean() ?: res?.getString(R.string.media_chapter_n, i + 1) ?: "#${i + 1}"
                val length = (c.end - c.start).takeIf { it > 0 }?.let { " · ${duration(it)}" } ?: ""
                MediaRow(R.string.media_section_chapters, name + length, labelText = duration(c.start.coerceAtLeast(0)))
            }
            out += MediaSection(R.string.media_section_chapters, rows)
        }
        return out
    }

    /**
     * A picture for the details sheet: a frame from the video, or the cover art of an audio file. Null when there is none.
     * Blocking: call it off the main thread.
     */
    fun preview(path: String, video: Boolean, maxSide: Int = 1024): Bitmap? {
        val bitmap: Bitmap = (
            try {
                if (video) videoFrame(path) else coverArt(path)
            } catch (_: Throwable) {
                null
            }
            ) ?: return null
        return runCatching { scaleDown(bitmap, maxSide) }.getOrNull() ?: bitmap
    }

    private fun videoFrame(path: String): Bitmap? {
        // The framework first (hardware decoder, cheap), then FFmpeg for codecs the phone cannot decode.
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            retriever.getFrameAtTime(ms / 3 * 1000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { return it }
        } catch (_: Throwable) {
        } finally {
            runCatching { retriever.release() }
        }
        return ffmpegVideoFrame(path)
    }

    /**
     * A frame (at a third of the video) decoded by FFmpeg alone, scaled to at most [maxSide] pixels: for videos the
     * phone's own decoders cannot open, such as the thumbnails of the file list. Null when FFmpeg cannot decode it either.
     * Blocking: call it off the main thread.
     */
    fun ffmpegFrame(path: String, maxSide: Int = 512): Bitmap? {
        val bitmap = (
            try {
                ffmpegVideoFrame(path)
            } catch (_: Throwable) {
                null
            }
            ) ?: return null
        return runCatching { scaleDown(bitmap, maxSide) }.getOrNull() ?: bitmap
    }

    private fun ffmpegVideoFrame(path: String): Bitmap? {
        val info = probe(path) ?: return null
        return try {
            val v = info.videoStream
            if (v != null && info.supportsFrameLoading && v.frameWidth.toLong() * v.frameHeight <= MAX_FFMPEG_FRAME_PIXELS) info.getFrame() else null
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { info.release() }
        }
    }

    private fun coverArt(path: String): Bitmap? {
        var bytes: ByteArray? = null
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            bytes = retriever.embeddedPicture
        } catch (_: Throwable) {
        } finally {
            runCatching { retriever.release() }
        }
        if (bytes == null) {
            // FFmpeg also reads the pictures inside FLAC, Ogg and Opus files.
            val thumbs = MediaThumbnailRetriever()
            try {
                thumbs.setDataSource(path)
                bytes = thumbs.getEmbeddedPicture()
            } catch (_: Throwable) {
            } finally {
                runCatching { thumbs.release() }
            }
        }
        return bytes?.let { decodeSampled(it) }
    }

    private fun decodeSampled(bytes: ByteArray, maxSide: Int = 1024): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxSide * 2 || bounds.outHeight / sample > maxSide * 2) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun scaleDown(b: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(b.width, b.height)
        if (longest <= maxSide) return b
        val k = maxSide.toFloat() / longest
        val out = Bitmap.createScaledBitmap(b, (b.width * k).toInt().coerceAtLeast(1), (b.height * k).toInt().coerceAtLeast(1), true)
        if (out !== b) b.recycle()
        return out
    }

    /**
     * The date of [raw] in the phone's format ("Mar 14, 2025, 6:30 PM"), or null when it is empty or a placeholder.
     * Android reports it as yyyyMMdd'T'HHmmss.SSS'Z' (UTC); a date before 1971 is never real (MP4 stores 1904, others 1970 for "none").
     */
    private fun dateText(raw: String?): String? {
        val s = raw.clean() ?: return null
        if (s.length >= 15) {
            val parser = SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
                isLenient = false
            }
            val date = runCatching { parser.parse(s.substring(0, 15)) }.getOrNull() ?: return null
            val year = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { time = date }.get(Calendar.YEAR)
            return if (year < 1971) null else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(date)
        }
        // A short value is a plain year or date.
        val year = s.take(4).toIntOrNull() ?: return null
        return if (year >= 1971) s else null
    }

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    private val iso639B = mapOf(
        "alb" to "sqi", "arm" to "hye", "baq" to "eus", "bur" to "mya", "chi" to "zho", "cze" to "ces", "dut" to "nld",
        "fre" to "fra", "geo" to "kat", "ger" to "deu", "gre" to "ell", "ice" to "isl", "mac" to "mkd", "mao" to "mri",
        "may" to "msa", "per" to "fas", "rum" to "ron", "slo" to "slk", "tib" to "bod", "wel" to "cym",
    )

    /** "Persian (fas)" for an ISO 639 code, or the code itself when Android does not know the language; null for "und". */
    private fun languageName(code: String?): String? {
        val raw = code.clean()?.lowercase() ?: return null
        if (raw == "und" || raw == "zxx") return null
        val tag = iso639B[raw] ?: raw
        val name = Locale.forLanguageTag(tag).getDisplayLanguage(Locale.getDefault())
        return if (name.isNullOrBlank() || name.equals(tag, ignoreCase = true)) raw
        else name.replaceFirstChar { it.uppercase() } + " ($raw)"
    }

    private fun flags(disposition: Int, res: Resources?): String? {
        if (res == null) return null
        val names = buildList {
            if ((disposition and DISP_DEFAULT) != 0) add(res.getString(R.string.media_flag_default))
            if ((disposition and DISP_FORCED) != 0) add(res.getString(R.string.media_flag_forced))
            if ((disposition and DISP_DUB) != 0) add(res.getString(R.string.media_flag_dub))
            if ((disposition and DISP_ORIGINAL) != 0) add(res.getString(R.string.media_flag_original))
            if ((disposition and DISP_COMMENT) != 0) add(res.getString(R.string.media_flag_comment))
            if ((disposition and DISP_HEARING_IMPAIRED) != 0) add(res.getString(R.string.media_flag_hearing_impaired))
            if ((disposition and DISP_VISUAL_IMPAIRED) != 0) add(res.getString(R.string.media_flag_visual_impaired))
            if ((disposition and DISP_LYRICS) != 0) add(res.getString(R.string.media_flag_lyrics))
        }
        return names.takeIf { it.isNotEmpty() }?.joinToString(", ")
    }

    /** "6 (5.1(side))": the channel count, then FFmpeg's layout name when it has one. */
    private fun channelsText(count: Int, layout: String?): String {
        val l = layout.clean()
        return if (l == null || l.contains("channel", ignoreCase = true)) channels(count) else "$count ($l)"
    }

    /** FFmpeg sample format names ("fltp", "s16") with what they mean. */
    private fun sampleFormat(name: String?): String? {
        val s = name.clean() ?: return null
        val planar = s.endsWith("p")
        val base = if (planar) s.removeSuffix("p") else s
        val meaning = when (base) {
            "u8" -> "8-bit"
            "s16" -> "16-bit"
            "s32" -> "32-bit"
            "s64" -> "64-bit"
            "flt" -> "32-bit float"
            "dbl" -> "64-bit float"
            else -> return s
        }
        return if (planar) "$s ($meaning, planar)" else "$s ($meaning)"
    }

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    /** "16:9" for a simple ratio, otherwise "2.39:1". */
    private fun aspectRatio(w: Int, h: Int): String {
        val g = gcd(w, h).coerceAtLeast(1)
        val rw = w / g
        val rh = h / g
        return if (rw <= 32 && rh <= 32) "$rw:$rh" else String.format(Locale.US, "%.2f:1", w.toDouble() / h)
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
                    // Bit depth is only meaningful for lossless/PCM audio. For lossy codecs (MP3, AAC, Opus...)
                    // Android reports the decoder's 16-bit output, which is not a property of the file.
                    if (isLossless(mime)) {
                        val depth = bitsPerSample?.takeIf { it > 0 } ?: pcmDepth(f)
                        depth?.let { audio += MediaRow(R.string.media_bit_depth, "$it bit") }
                    }
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

    private fun isLossless(mime: String): Boolean {
        val m = mime.lowercase()
        return m == "audio/flac" || m == "audio/raw" || m == "audio/alac" || m == "audio/x-wav" || m == "audio/wav" ||
            m == "audio/x-ape" || m == "audio/x-wavpack" || m == "audio/wavpack" || m == "audio/aiff" || m == "audio/x-aiff" ||
            m.startsWith("audio/g711") || m == "audio/x-dsf" || m == "audio/dsd"
    }

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
