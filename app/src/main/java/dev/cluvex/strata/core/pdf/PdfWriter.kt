package dev.cluvex.strata.core.pdf

import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr
import java.io.OutputStream

class PdfWriter(output: OutputStream) {

    private val out = CountingStream(output.buffered(64 * 1024))
    private val offsets = HashMap<Int, Long>()

    private class CountingStream(private val target: OutputStream) : OutputStream() {
        var count = 0L
            private set

        override fun write(b: Int) {
            target.write(b)
            count++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            target.write(b, off, len)
            count += len
        }

        override fun flush() = target.flush()
        override fun close() = target.close()
    }

    val written: Long get() = out.count

    fun header(version: String) {
        text("%PDF-$version\n")
        out.write(byteArrayOf('%'.code.toByte(), 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), '\n'.code.toByte()))
    }

    fun write(number: Int, obj: PdfObject) {
        begin(number)
        value(obj)
        text("\nendobj\n")
    }

    fun writeStream(number: Int, dictionary: PdfDictionary, length: Long, body: (OutputStream) -> Unit) {
        begin(number)
        dictionary["Length"] = PdfNumber(length)
        value(dictionary)
        text("\nstream\n")
        val before = out.count
        body(out)
        val actual = out.count - before
        if (actual != length) throw PdfException(tr(R.string.pdf_error_stream_length, actual, length))
        text("\nendstream\nendobj\n")
    }

    fun finish(root: Int, size: Int, id: ByteArray) {
        val xref = out.count
        text("xref\n0 $size\n")
        text("0000000000 65535 f\r\n")
        for (n in 1 until size) {
            val offset = offsets[n]
            if (offset == null) text("0000000000 65535 f\r\n") else text(String.format(java.util.Locale.ROOT, "%010d 00000 n\r\n", offset))
        }
        text("trailer\n")
        val hex = PdfString(id, hex = true)
        value(
            PdfDictionary().apply {
                this["Size"] = PdfNumber(size.toLong())
                this["Root"] = PdfReference(root, 0)
                this["ID"] = PdfArray(mutableListOf(hex, hex))
            },
        )
        text("\nstartxref\n$xref\n%%EOF\n")
        out.flush()
    }

    private fun begin(number: Int) {
        if (offsets.containsKey(number)) throw PdfException("Object $number written twice")
        offsets[number] = out.count
        text("$number 0 obj\n")
    }

    private fun text(s: String) = out.write(s.toByteArray(Charsets.ISO_8859_1))

    private fun value(obj: PdfObject) {
        when (obj) {
            PdfNull -> text("null")
            is PdfBoolean -> text(if (obj.value) "true" else "false")
            is PdfNumber -> text(obj.text)
            is PdfName -> name(obj.name)
            is PdfReference -> text("${obj.number} ${obj.generation} R")
            is PdfString -> string(obj)
            is PdfArray -> {
                text("[")
                obj.items.forEachIndexed { i, item ->
                    if (i > 0) text(" ")
                    value(item)
                }
                text("]")
            }
            is PdfDictionary -> {
                text("<<")
                for ((k, v) in obj.entries) {
                    name(k)
                    text(" ")
                    value(v)
                }
                text(">>")
            }
            is PdfStream -> value(obj.dictionary)
        }
    }

    private fun name(name: String) {
        val sb = StringBuilder("/")
        for (ch in name) {
            val c = ch.code and 0xFF
            if (c < 0x21 || c > 0x7E || c == '#'.code || PdfLexer.isDelimiter(c)) sb.append('#').append(String.format(java.util.Locale.ROOT, "%02X", c))
            else sb.append(ch)
        }
        text(sb.toString())
    }

    private fun string(s: PdfString) {
        if (s.hex) {
            val sb = StringBuilder(s.bytes.size * 2 + 2).append('<')
            for (b in s.bytes) sb.append(HEX[(b.toInt() shr 4) and 15]).append(HEX[b.toInt() and 15])
            text(sb.append('>').toString())
            return
        }
        out.write('('.code)
        for (b in s.bytes) {
            when (val c = b.toInt() and 0xFF) {
                '\\'.code, '('.code, ')'.code -> {
                    out.write('\\'.code)
                    out.write(c)
                }
                13 -> text("\\r")
                10 -> text("\\n")
                else -> out.write(c)
            }
        }
        out.write(')'.code)
    }

    private companion object {
        val HEX = "0123456789ABCDEF".toCharArray()
    }
}
