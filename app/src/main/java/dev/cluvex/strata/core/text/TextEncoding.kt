package dev.cluvex.strata.core.text

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

enum class LineEnding(val chars: String, val label: String) {
    LF("\n", "LF"),
    CRLF("\r\n", "CRLF"),
    CR("\r", "CR"),
}

data class DecodedText(
    val text: String,
    val charset: Charset,
    val bom: Boolean,
    val lineEnding: LineEnding,
)

object TextEncoding {

    private val UTF_16LE: Charset = Charsets.UTF_16LE
    private val UTF_16BE: Charset = Charsets.UTF_16BE

    val choices: List<Charset> = listOf(
        "UTF-8", "UTF-16LE", "UTF-16BE", "GBK", "GB18030", "Big5", "Shift_JIS", "EUC-JP", "EUC-KR",
        "windows-1256", "windows-1251", "windows-1252", "ISO-8859-1",
    ).mapNotNull { runCatching { Charset.forName(it) }.getOrNull() }.distinct()

    fun decode(bytes: ByteArray, charset: Charset? = null): DecodedText {
        val (detected, bomLength) = if (charset != null) charset to bomLength(bytes, charset) else detect(bytes)
        val body = ByteBuffer.wrap(bytes, bomLength, bytes.size - bomLength)
        val text = detected.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(body)
            .toString()
        return DecodedText(text, detected, bomLength > 0, lineEnding(text))
    }

    fun detect(bytes: ByteArray): Pair<Charset, Int> {
        if (startsWith(bytes, 0xEF, 0xBB, 0xBF)) return Charsets.UTF_8 to 3
        if (startsWith(bytes, 0xFF, 0xFE)) return UTF_16LE to 2
        if (startsWith(bytes, 0xFE, 0xFF)) return UTF_16BE to 2
        return if (isValidUtf8(bytes)) Charsets.UTF_8 to 0 else Charsets.ISO_8859_1 to 0
    }

    fun detectSample(bytes: ByteArray, complete: Boolean): Pair<Charset, Int> {
        if (startsWith(bytes, 0xEF, 0xBB, 0xBF)) return Charsets.UTF_8 to 3
        if (startsWith(bytes, 0xFF, 0xFE)) return UTF_16LE to 2
        if (startsWith(bytes, 0xFE, 0xFF)) return UTF_16BE to 2
        val utf8 = if (complete) isValidUtf8(bytes) else isValidUtf8Prefix(bytes)
        return if (utf8) Charsets.UTF_8 to 0 else Charsets.ISO_8859_1 to 0
    }

    fun isValidUtf8Prefix(bytes: ByteArray): Boolean {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val out = java.nio.CharBuffer.allocate(8192)
        val input = ByteBuffer.wrap(bytes)
        while (true) {
            val r = decoder.decode(input, out, false)
            if (r.isError) return false
            if (r.isOverflow) { out.clear(); continue }
            return true
        }
    }

    fun isValidUtf8(bytes: ByteArray): Boolean = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
        true
    } catch (e: CharacterCodingException) {
        false
    }

    fun encode(text: String, charset: Charset, bom: Boolean): ByteArray {
        val body = text.toByteArray(charset)
        if (!bom) return body
        val mark = when (charset) {
            Charsets.UTF_8 -> byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
            UTF_16LE -> byteArrayOf(0xFF.toByte(), 0xFE.toByte())
            UTF_16BE -> byteArrayOf(0xFE.toByte(), 0xFF.toByte())
            else -> return body
        }
        return mark + body
    }

    fun canEncode(text: String, charset: Charset): Boolean = charset.newEncoder().canEncode(text)

    fun lineEnding(text: String): LineEnding {
        var crlf = 0
        var lf = 0
        var cr = 0
        var i = 0
        while (i < text.length) {
            when (text[i]) {
                '\r' -> if (i + 1 < text.length && text[i + 1] == '\n') { crlf++; i++ } else cr++
                '\n' -> lf++
            }
            i++
        }
        return when {
            crlf >= lf && crlf >= cr && crlf > 0 -> LineEnding.CRLF
            cr > lf -> LineEnding.CR
            else -> LineEnding.LF
        }
    }

    fun convertLineEndings(text: String, ending: LineEnding): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when (c) {
                '\r' -> {
                    out.append(ending.chars)
                    if (i + 1 < text.length && text[i + 1] == '\n') i++
                }
                '\n' -> out.append(ending.chars)
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    fun looksBinary(bytes: ByteArray): Boolean {
        if (startsWith(bytes, 0xFF, 0xFE) || startsWith(bytes, 0xFE, 0xFF)) return false
        val n = minOf(bytes.size, 8192)
        for (i in 0 until n) if (bytes[i] == 0.toByte()) return true
        return false
    }

    fun bomLength(bytes: ByteArray, charset: Charset): Int = when (charset) {
        Charsets.UTF_8 -> if (startsWith(bytes, 0xEF, 0xBB, 0xBF)) 3 else 0
        UTF_16LE -> if (startsWith(bytes, 0xFF, 0xFE)) 2 else 0
        UTF_16BE -> if (startsWith(bytes, 0xFE, 0xFF)) 2 else 0
        else -> 0
    }

    private fun startsWith(bytes: ByteArray, vararg prefix: Int): Boolean =
        bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it].toByte() }
}
