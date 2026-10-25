package dev.cluvex.strata.core.media

import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class FontInfo(
    val format: String,
    val family: String?,
    val subfamily: String?,
    val fullName: String?,
    val version: String?,
    val postScriptName: String?,
    val copyright: String?,
    val designer: String?,
    val glyphCount: Int,
    val fontsInCollection: Int,
)

object FontInfoReader {

    fun read(file: File): FontInfo = read(file.readBytes())

    fun read(bytes: ByteArray): FontInfo {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (bytes.size < 12) throw IOException(tr(R.string.media_font_not_font))
        val tag = buf.getInt(0)
        var offset = 0
        var count = 1
        val format = when (tag) {
            TTCF -> {
                count = buf.getInt(8).coerceAtLeast(1)
                offset = buf.getInt(12)
                tr(R.string.media_font_ttc)
            }
            OTTO -> "OpenType (CFF)"
            0x00010000, TRUE -> "TrueType"
            else -> throw IOException(tr(R.string.media_font_not_font))
        }
        if (offset < 0 || offset + 12 > bytes.size) throw IOException(tr(R.string.media_font_damaged))
        val subTag = buf.getInt(offset)
        val effectiveFormat = if (tag == TTCF && subTag == OTTO) tr(R.string.media_font_otc) else format
        val tables = tables(buf, offset)
        val names = tables["name"]?.let { (start, length) -> names(buf, start, length) }.orEmpty()
        val glyphs = tables["maxp"]?.let { (start, length) -> if (length >= 6) buf.getShort(start + 4).toInt() and 0xFFFF else 0 } ?: 0
        return FontInfo(
            format = effectiveFormat,
            family = names[16] ?: names[1],
            subfamily = names[17] ?: names[2],
            fullName = names[4],
            version = names[5],
            postScriptName = names[6],
            copyright = names[0],
            designer = names[9],
            glyphCount = glyphs,
            fontsInCollection = count,
        )
    }

    private fun tables(buf: ByteBuffer, offset: Int): Map<String, Pair<Int, Int>> {
        val numTables = buf.getShort(offset + 4).toInt() and 0xFFFF
        val out = HashMap<String, Pair<Int, Int>>()
        for (i in 0 until numTables) {
            val rec = offset + 12 + i * 16
            if (rec + 16 > buf.limit()) break
            val tag = String(ByteArray(4) { buf.get(rec + it) }, Charsets.ISO_8859_1)
            val start = buf.getInt(rec + 8)
            val length = buf.getInt(rec + 12)
            if (start >= 0 && length >= 0 && start.toLong() + length <= buf.limit()) out[tag] = start to length
        }
        return out
    }

    private fun names(buf: ByteBuffer, start: Int, length: Int): Map<Int, String> {
        if (length < 6) return emptyMap()
        val count = buf.getShort(start + 2).toInt() and 0xFFFF
        val storage = start + (buf.getShort(start + 4).toInt() and 0xFFFF)
        val best = HashMap<Int, Pair<Int, String>>()
        for (i in 0 until count) {
            val rec = start + 6 + i * 12
            if (rec + 12 > start + length) break
            val platform = buf.getShort(rec).toInt() and 0xFFFF
            val encoding = buf.getShort(rec + 2).toInt() and 0xFFFF
            val language = buf.getShort(rec + 4).toInt() and 0xFFFF
            val nameId = buf.getShort(rec + 6).toInt() and 0xFFFF
            val len = buf.getShort(rec + 8).toInt() and 0xFFFF
            val off = buf.getShort(rec + 10).toInt() and 0xFFFF
            val at = storage + off
            if (at < 0 || at + len > buf.limit()) continue
            val raw = ByteArray(len) { buf.get(at + it) }
            val (score, text) = when {
                platform == 3 && (encoding == 1 || encoding == 10) -> (if (language == 0x409) 3 else 2) to String(raw, Charsets.UTF_16BE)
                platform == 0 -> 2 to String(raw, Charsets.UTF_16BE)
                platform == 1 && encoding == 0 -> (if (language == 0) 1 else 0) to String(raw, Charsets.ISO_8859_1)
                else -> continue
            }
            val current = best[nameId]
            if (current == null || score > current.first) best[nameId] = score to text.trim()
        }
        return best.mapValues { it.value.second }.filterValues { it.isNotEmpty() }
    }

    private const val TTCF = 0x74746366
    private const val OTTO = 0x4F54544F
    private const val TRUE = 0x74727565
}
