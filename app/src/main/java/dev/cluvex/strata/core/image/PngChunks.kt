package dev.cluvex.strata.core.image

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import java.util.zip.CRC32

object PngChunks {

    val SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)

    class Chunk(val type: String, val data: ByteArray)

    fun read(png: ByteArray): List<Chunk> {
        require(png.size >= 8 && png.copyOf(8).contentEquals(SIGNATURE)) { "Not a PNG" }
        val out = ArrayList<Chunk>()
        var i = 8
        while (i + 8 <= png.size) {
            val len = int(png, i)
            require(len >= 0 && i + 12 + len <= png.size) { "Truncated PNG chunk" }
            val type = String(png, i + 4, 4, Charsets.ISO_8859_1)
            out += Chunk(type, png.copyOfRange(i + 8, i + 8 + len))
            i += 12 + len
            if (type == "IEND") break
        }
        return out
    }

    fun encode(argb: IntArray, width: Int, height: Int, extra: List<Chunk> = emptyList()): ByteArray {
        require(width > 0 && height > 0 && argb.size >= width * height) { "Bad picture size" }
        val ihdr = java.io.ByteArrayOutputStream().apply {
            write(be(width)); write(be(height))
            write(8)
            write(6)
            write(0); write(0); write(0)
        }.toByteArray()
        val raw = ByteArray(height * (1 + width * 4))
        var i = 0
        for (y in 0 until height) {
            raw[i++] = 0
            for (x in 0 until width) {
                val p = argb[y * width + x]
                raw[i++] = (p shr 16).toByte(); raw[i++] = (p shr 8).toByte(); raw[i++] = p.toByte(); raw[i++] = (p ushr 24).toByte()
            }
        }
        val deflater = java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION)
        val idat = try {
            deflater.setInput(raw)
            deflater.finish()
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
            out.toByteArray()
        } finally {
            deflater.end()
        }
        return write(listOf(Chunk("IHDR", ihdr)) + extra + listOf(Chunk("IDAT", idat), Chunk("IEND", ByteArray(0))))
    }

    fun write(chunks: List<Chunk>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(SIGNATURE)
        for (c in chunks) {
            val type = c.type.toByteArray(Charsets.ISO_8859_1)
            require(type.size == 4) { "Bad chunk type ${c.type}" }
            out.write(be(c.data.size))
            out.write(type)
            out.write(c.data)
            out.write(be(CRC32().apply { update(type); update(c.data) }.value.toInt()))
        }
        return out.toByteArray()
    }

    fun hasNpTc(input: InputStream): Boolean {
        val d = DataInputStream(input)
        val sig = ByteArray(8)
        try {
            d.readFully(sig)
            if (!sig.contentEquals(SIGNATURE)) return false
            while (true) {
                val len = d.readInt()
                val type = ByteArray(4).also { d.readFully(it) }.toString(Charsets.ISO_8859_1)
                when (type) {
                    "npTc" -> return true
                    "IDAT", "IEND" -> return false
                }
                if (len < 0) return false
                skipFully(d, len.toLong() + 4)
            }
        } catch (_: EOFException) {
            return false
        }
    }

    private fun skipFully(input: InputStream, n: Long) {
        var left = n
        while (left > 0) {
            val k = input.skip(left)
            if (k > 0) left -= k else if (input.read() < 0) throw EOFException() else left--
        }
    }

    fun head(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val k = input.read(buf, read, n - read)
            if (k < 0) break
            read += k
        }
        return if (read == n) buf else buf.copyOf(read)
    }

    internal fun int(b: ByteArray, at: Int): Int =
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

    internal fun be(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
}
