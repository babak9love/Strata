package dev.cluvex.strata.core.text

import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

data class TextPage(val start: Long, val end: Long, val firstLine: Long, val lines: Long) {
    val length: Long get() = end - start
}

data class PageText(val text: String, val lossless: Boolean)

object TextPager {
    const val TARGET = 256 * 1024
    const val HARD_MAX = 512 * 1024

    private enum class Mode { SINGLE_BYTE, UTF8, UTF16LE, UTF16BE, NEWLINE_ONLY }

    private fun mode(cs: Charset): Mode? = when (cs.name().uppercase()) {
        "UTF-8" -> Mode.UTF8
        "UTF-16LE" -> Mode.UTF16LE
        "UTF-16BE" -> Mode.UTF16BE
        else -> {
            val enc = runCatching { cs.newEncoder() }.getOrNull() ?: return null
            val lf = runCatching { "\n".toByteArray(cs) }.getOrNull()
            when {
                lf == null || !(lf.size == 1 && lf[0] == 0x0A.toByte()) -> null
                enc.maxBytesPerChar() <= 1f -> Mode.SINGLE_BYTE
                else -> Mode.NEWLINE_ONLY
            }
        }
    }

    fun pageable(charset: Charset): Boolean = mode(charset) != null

    fun index(
        channel: SeekableByteChannel,
        size: Long,
        bomLength: Int,
        charset: Charset,
        target: Int = TARGET,
        hardMax: Int = HARD_MAX,
    ): List<TextPage> {
        val mode = mode(charset) ?: throw IllegalArgumentException("${charset.name()} can't be paged")
        val unit = if (mode == Mode.UTF16LE || mode == Mode.UTF16BE) 2 else 1
        val pages = ArrayList<TextPage>()
        var pageStart = bomLength.toLong()
        var linesInPage = 0L
        var firstLine = 0L
        var pos = bomLength.toLong()
        val buf = ByteBuffer.allocate(64 * 1024)
        channel.position(bomLength.toLong())
        var carry = -1

        fun close(end: Long) {
            pages += TextPage(pageStart, end, firstLine, linesInPage)
            firstLine += linesInPage
            linesInPage = 0
            pageStart = end
        }

        while (pos < size) {
            buf.clear()
            val n = channel.read(buf)
            if (n <= 0) break
            buf.flip()
            while (buf.hasRemaining()) {
                if (unit == 1) {
                    val b = buf.get().toInt() and 0xFF
                    val here = pos
                    pos++
                    if (b == 0x0A) {
                        linesInPage++
                        if (pos - pageStart >= target) close(pos)
                    } else if (here - pageStart >= hardMax && (canCutBefore(mode, b) || here - pageStart >= hardMax.toLong() * 4)) {
                        close(here)
                    }
                } else {
                    val b = buf.get().toInt() and 0xFF
                    pos++
                    if (carry < 0) { carry = b; continue }
                    val unitValue = if (mode == Mode.UTF16LE) (b shl 8) or carry else (carry shl 8) or b
                    carry = -1
                    val unitStart = pos - 2
                    if (unitValue == 0x000A) {
                        linesInPage++
                        if (pos - pageStart >= target) close(pos)
                    } else if (unitStart - pageStart >= hardMax && unitValue !in 0xDC00..0xDFFF) {
                        close(unitStart)
                    }
                }
            }
        }
        if (pos > pageStart || pages.isEmpty()) close(maxOf(pos, pageStart))
        return pages
    }

    private fun canCutBefore(mode: Mode, b: Int): Boolean = when (mode) {
        Mode.SINGLE_BYTE -> true
        Mode.UTF8 -> (b and 0xC0) != 0x80
        else -> false
    }

    fun readPage(channel: SeekableByteChannel, page: TextPage): ByteArray {
        val out = ByteBuffer.allocate(page.length.toInt())
        channel.position(page.start)
        while (out.hasRemaining()) if (channel.read(out) < 0) break
        return out.array().copyOf(out.position())
    }

    fun decode(bytes: ByteArray, charset: Charset): PageText {
        val text = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
        val lossless = runCatching { text.toByteArray(charset).contentEquals(bytes) }.getOrDefault(false)
        return PageText(text, lossless)
    }

    fun assemble(
        source: SeekableByteChannel,
        pages: List<TextPage>,
        bom: ByteArray,
        out: OutputStream,
        replacement: (Int) -> ByteArray?,
    ) {
        out.write(bom)
        val buf = ByteBuffer.allocate(64 * 1024)
        pages.forEachIndexed { i, page ->
            val edited = replacement(i)
            if (edited != null) {
                out.write(edited)
            } else {
                source.position(page.start)
                var left = page.length
                while (left > 0) {
                    buf.clear()
                    if (left < buf.capacity()) buf.limit(left.toInt())
                    val n = source.read(buf)
                    if (n <= 0) break
                    out.write(buf.array(), 0, n)
                    left -= n
                }
            }
        }
        out.flush()
    }

    fun pageOfLine(pages: List<TextPage>, line: Long): Int {
        if (pages.isEmpty()) return 0
        val i = pages.indexOfLast { it.firstLine <= line }
        return if (i < 0) 0 else i
    }
}
