package dev.cluvex.strata.feature.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale

/**
 * Finds subtitle files next to videos (`movie.srt`, `movie.fa.srt`, ...) and loads files the user picks.
 *
 * Players read subtitle files as UTF-8 only, but many `.srt` files (Persian and Arabic ones in particular) are
 * saved in an old encoding such as Windows-1256 and would show as garbage. Every file is therefore decoded here
 * and written to the app's cache as UTF-8; the player reads that copy and the original is never touched.
 */
object SubtitleFiles {

    private val EXTENSIONS = setOf("srt", "vtt", "ass", "ssa")
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val MAX_CACHED = 300
    private const val PRUNE = 100

    /**
     * A subtitle ready to hand to the player: a UTF-8 copy of the file plus how to show it in the list.
     * [key] identifies the original file (its offset is remembered under it), [base] is the unshifted UTF-8 copy
     * and [offsetMs] how far the copy at [uri] is shifted from it (positive = later).
     */
    class Prepared(
        val uri: Uri,
        val mime: String,
        val label: String,
        val language: String?,
        val key: String,
        val base: File,
        val offsetMs: Int = 0,
    ) {
        fun configuration(default: Boolean): MediaItem.SubtitleConfiguration =
            MediaItem.SubtitleConfiguration.Builder(uri)
                .setMimeType(mime)
                .setLabel(label)
                .setLanguage(language)
                .setSelectionFlags(if (default) C.SELECTION_FLAG_DEFAULT else 0)
                .build()
    }

    /** Subtitle files that belong to each of [videos] (all in one folder), keyed by the video's path. Runs on IO. */
    fun sidecarsFor(ctx: Context, videos: List<File>): Map<String, List<Prepared>> {
        val dir = videos.firstOrNull()?.parentFile ?: return emptyMap()
        val subs = dir.listFiles { f -> f.isFile && f.extension.lowercase() in EXTENSIONS }
            ?.sortedBy { it.name }
            .orEmpty()
        if (subs.isEmpty()) return emptyMap()
        val out = HashMap<String, List<Prepared>>()
        for (video in videos) {
            val base = video.nameWithoutExtension
            val mine = subs.filter { s ->
                val n = s.nameWithoutExtension
                n.equals(base, ignoreCase = true) || n.startsWith("$base.", ignoreCase = true)
            }
            if (mine.isEmpty()) continue
            val ready = mine.mapNotNull { prepareFile(ctx, it, base) }
            if (ready.isNotEmpty()) out[video.path] = ready
        }
        return out
    }

    /** A file chosen with the system file picker. Runs on IO. Null when it can't be read. */
    fun preparePicked(ctx: Context, uri: Uri): Prepared? = runCatching {
        val resolver = ctx.contentResolver
        val bytes = resolver.openInputStream(uri)?.use { readCapped(it) } ?: return@runCatching null
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
            ?: uri.lastPathSegment
            ?: "subtitle"
        val text = decode(ctx, bytes)
        if (text.isBlank()) return@runCatching null
        val ext = sniff(text)
        val out = File(cacheDir(ctx), "picked_${uri.toString().hashCode()}_${bytes.size}.$ext")
        if (!out.exists()) out.writeText(text)
        val prepared = Prepared(Uri.fromFile(out), mimeOf(ext), name, languageOf(name.substringBeforeLast('.'), null), "uri:$uri", out)
        withSavedOffset(ctx, prepared)
    }.getOrNull()

    private fun prepareFile(ctx: Context, f: File, videoBase: String): Prepared? = runCatching {
        if (f.length() > MAX_BYTES) return@runCatching null
        val ext = f.extension.lowercase()
        val out = File(cacheDir(ctx), "${f.absolutePath.hashCode()}_${f.lastModified()}_${f.length()}.$ext")
        if (!out.exists()) out.writeText(decode(ctx, f.readBytes()))
        val prepared = Prepared(Uri.fromFile(out), mimeOf(ext), f.name, languageOf(f.nameWithoutExtension, videoBase), f.absolutePath, out)
        withSavedOffset(ctx, prepared)
    }.getOrNull()

    /** The subtitle shifted by the offset the user last set for this file, if any. */
    private fun withSavedOffset(ctx: Context, p: Prepared): Prepared {
        val saved = SubtitleOffsets.get(ctx, p.key)
        return if (saved == 0) p else withOffset(ctx, p, saved) ?: p
    }

    /**
     * A copy of [p] with every time shifted by [offsetMs] (positive shows the subtitles later, negative earlier),
     * made from the unshifted UTF-8 copy so offsets never add up. Runs on IO. Null when it can't be written.
     */
    fun withOffset(ctx: Context, p: Prepared, offsetMs: Int): Prepared? = runCatching {
        if (offsetMs == 0) return@runCatching Prepared(Uri.fromFile(p.base), p.mime, p.label, p.language, p.key, p.base, 0)
        val ext = p.base.extension.lowercase()
        val out = File(p.base.parentFile, "${p.base.nameWithoutExtension}_s$offsetMs.$ext")
        if (!out.exists()) out.writeText(shift(p.base.readText(), ext, offsetMs))
        Prepared(Uri.fromFile(out), p.mime, p.label, p.language, p.key, p.base, offsetMs)
    }.getOrNull()

    private val SRT_TIME = Regex("""(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})""")
    private val VTT_TIME = Regex("""(?:(\d{1,2}):)?(\d{2}):(\d{2})\.(\d{3})""")
    private val ASS_TIME = Regex("""(\d+):(\d{2}):(\d{2})[.](\d{1,2})""")

    private fun shift(text: String, ext: String, offsetMs: Int): String = when (ext) {
        "ass", "ssa" -> shiftAss(text, offsetMs)
        "vtt" -> shiftCueLines(text, offsetMs, VTT_TIME, '.')
        else -> shiftCueLines(text, offsetMs, SRT_TIME, ',')
    }

    /** SRT and VTT: only the lines holding "start --> end" carry times. */
    private fun shiftCueLines(text: String, offsetMs: Int, time: Regex, separator: Char): String =
        text.split("\n").joinToString("\n") { line ->
            if (!line.contains("-->")) {
                line
            } else {
                time.replace(line) { m ->
                    val h = m.groupValues[1].toLongOrNull() ?: 0L
                    val min = m.groupValues[2].toLong()
                    val sec = m.groupValues[3].toLong()
                    val ms = m.groupValues[4].padEnd(3, '0').toLong()
                    val total = (((h * 60 + min) * 60 + sec) * 1000 + ms + offsetMs).coerceAtLeast(0L)
                    String.format(Locale.US, "%02d:%02d:%02d%c%03d", total / 3_600_000, total / 60_000 % 60, total / 1000 % 60, separator, total % 1000)
                }
            }
        }

    /** ASS and SSA: the Start and End fields of every "Dialogue:" line, found through the [Events] Format line. */
    private fun shiftAss(text: String, offsetMs: Int): String {
        val lines = text.split("\n")
        var startIdx = 1
        var endIdx = 2
        var fields = 10
        lines.firstNotNullOfOrNull { line ->
            if (!line.trimStart().startsWith("Format:", ignoreCase = true)) return@firstNotNullOfOrNull null
            line.substringAfter(':').split(',').map { it.trim().lowercase() }.takeIf { "start" in it && "end" in it }
        }?.let { names ->
            startIdx = names.indexOf("start")
            endIdx = names.indexOf("end")
            fields = names.size
        }
        return lines.joinToString("\n") { line ->
            if (!line.startsWith("Dialogue:")) return@joinToString line
            val parts = line.substring("Dialogue:".length).split(",", limit = fields)
            if (parts.size < fields) return@joinToString line
            val m = parts.toMutableList()
            m[startIdx] = shiftAssTime(m[startIdx], offsetMs)
            m[endIdx] = shiftAssTime(m[endIdx], offsetMs)
            "Dialogue:" + m.joinToString(",")
        }
    }

    private fun shiftAssTime(value: String, offsetMs: Int): String {
        val m = ASS_TIME.find(value.trim()) ?: return value
        val h = m.groupValues[1].toLong()
        val min = m.groupValues[2].toLong()
        val sec = m.groupValues[3].toLong()
        val cs = m.groupValues[4].padEnd(2, '0').toLong()
        val total = (((h * 60 + min) * 60 + sec) * 1000 + cs * 10 + offsetMs).coerceAtLeast(0L)
        return String.format(Locale.US, "%d:%02d:%02d.%02d", total / 3_600_000, total / 60_000 % 60, total / 1000 % 60, total / 10 % 100)
    }

    private fun cacheDir(ctx: Context): File {
        val dir = File(ctx.cacheDir, "subtitles").also { it.mkdirs() }
        val files = dir.listFiles()
        if (files != null && files.size > MAX_CACHED) {
            files.sortedBy { it.lastModified() }.take(PRUNE).forEach { it.delete() }
        }
        return dir
    }

    private fun mimeOf(ext: String): String = when (ext) {
        "vtt" -> MimeTypes.TEXT_VTT
        "ass", "ssa" -> MimeTypes.TEXT_SSA
        else -> MimeTypes.APPLICATION_SUBRIP
    }

    /** The real format is taken from the content, since a file's extension is often wrong. */
    private fun sniff(text: String): String {
        val head = text.take(2048)
        return when {
            head.trimStart().startsWith("WEBVTT") -> "vtt"
            head.contains("[Script Info]", ignoreCase = true) || head.contains("[Events]", ignoreCase = true) -> "ass"
            else -> "srt"
        }
    }

    /** `movie.fa.srt` (video `movie`) gives `fa`; null when the name carries no language. */
    private fun languageOf(subtitleBase: String, videoBase: String?): String? {
        val tail = (if (videoBase != null && subtitleBase.startsWith(videoBase, ignoreCase = true)) subtitleBase.substring(videoBase.length) else subtitleBase)
            .trimStart('.')
        val token = tail.substringAfterLast('.')
        return token.takeIf { it.length in 2..3 && it.all(Char::isLetter) }?.lowercase()
    }

    private fun readCapped(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_BYTES) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun decode(ctx: Context, b: ByteArray): String {
        fun startsWith(vararg x: Int) = b.size >= x.size && x.indices.all { (b[it].toInt() and 0xFF) == x[it] }
        val text = when {
            startsWith(0xEF, 0xBB, 0xBF) -> String(b, 3, b.size - 3, Charsets.UTF_8)
            startsWith(0xFF, 0xFE) -> String(b, 2, b.size - 2, Charsets.UTF_16LE)
            startsWith(0xFE, 0xFF) -> String(b, 2, b.size - 2, Charsets.UTF_16BE)
            else -> strictUtf8(b) ?: String(b, legacyCharset(ctx))
        }
        return text.removePrefix("\uFEFF")
    }

    private fun strictUtf8(b: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(b))
            .toString()
    } catch (e: CharacterCodingException) {
        null
    }

    /** The old encoding that is most likely for a file that is not UTF-8, going by the app's language. */
    private fun legacyCharset(ctx: Context): Charset {
        val name = when (ctx.resources.configuration.locales[0].language) {
            "fa", "ar", "ur", "ps", "ku" -> "windows-1256"
            "ru", "uk", "bg", "be", "kk", "sr", "mk" -> "windows-1251"
            "zh" -> "GBK"
            "ja" -> "Shift_JIS"
            "ko" -> "EUC-KR"
            "el" -> "windows-1253"
            "tr" -> "windows-1254"
            "he" -> "windows-1255"
            "pl", "cs", "sk", "hu", "ro", "hr", "sl" -> "windows-1250"
            else -> "windows-1252"
        }
        return runCatching { Charset.forName(name) }.getOrDefault(Charsets.ISO_8859_1)
    }
}

/** The sync offset (milliseconds, positive = later) the user set for each subtitle file, kept between runs. */
object SubtitleOffsets {
    private const val FILE = "subtitle_offsets"
    private const val MAX_ENTRIES = 500

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
