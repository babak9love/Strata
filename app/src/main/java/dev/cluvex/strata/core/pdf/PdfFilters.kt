package dev.cluvex.strata.core.pdf

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

object PdfFilters {

    fun names(dict: PdfDictionary, resolve: (PdfObject?) -> PdfObject?): List<String> = when (val f = resolve(dict["Filter"])) {
        is PdfName -> listOf(f.name)
        is PdfArray -> f.items.mapNotNull { (resolve(it) as? PdfName)?.name }
        else -> emptyList()
    }

    private fun parameters(dict: PdfDictionary, resolve: (PdfObject?) -> PdfObject?): List<PdfDictionary?> =
        when (val p = resolve(dict["DecodeParms"] ?: dict["DP"])) {
            is PdfDictionary -> listOf(p)
            is PdfArray -> p.items.map { resolve(it) as? PdfDictionary }
            else -> emptyList()
        }

    fun decode(data: ByteArray, dict: PdfDictionary, resolve: (PdfObject?) -> PdfObject?): ByteArray {
        val params = parameters(dict, resolve)
        var out = data
        names(dict, resolve).forEachIndexed { i, name ->
            out = when (name) {
                "FlateDecode", "Fl" -> predict(inflate(out), params.getOrNull(i), resolve)
                "ASCIIHexDecode", "AHx" -> asciiHex(out)
                "ASCII85Decode", "A85" -> ascii85(out)
                "Crypt" -> out
                else -> throw PdfException("Can't read a $name stream")
            }
        }
        return out
    }

    fun inflate(data: ByteArray): ByteArray {
        val first = runCatching { inflate(data, nowrap = false) }.getOrNull()
        if (first != null && first.isNotEmpty()) return first
        return runCatching { inflate(data, nowrap = true) }.getOrNull() ?: first ?: ByteArray(0)
    }

    private fun inflate(data: ByteArray, nowrap: Boolean): ByteArray {
        val inflater = Inflater(nowrap)
        val out = ByteArrayOutputStream(maxOf(64, data.size * 3))
        try {
            inflater.setInput(data)
            val buf = ByteArray(16 * 1024)
            while (!inflater.finished()) {
                val n = try {
                    inflater.inflate(buf)
                } catch (e: DataFormatException) {
                    if (out.size() == 0) throw e
                    break
                }
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) break
                } else {
                    out.write(buf, 0, n)
                }
            }
        } finally {
            inflater.end()
        }
        return out.toByteArray()
    }

    private fun predict(data: ByteArray, params: PdfDictionary?, resolve: (PdfObject?) -> PdfObject?): ByteArray {
        if (params == null) return data
        fun int(key: String, default: Int) = (resolve(params[key]) as? PdfNumber)?.int ?: default
        val predictor = int("Predictor", 1)
        if (predictor < 2) return data
        val colors = int("Colors", 1).coerceIn(1, 32)
        val bits = int("BitsPerComponent", 8).coerceIn(1, 16)
        val columns = int("Columns", 1).coerceAtLeast(1)
        val bpp = maxOf(1, colors * bits / 8)
        val rowLength = (colors * bits * columns + 7) / 8
        if (predictor == 2) {
            if (bits != 8) return data
            val out = data.copyOf()
            var row = 0
            while (row < out.size) {
                for (i in bpp until minOf(rowLength, out.size - row)) {
                    out[row + i] = (out[row + i] + out[row + i - bpp]).toByte()
                }
                row += rowLength
            }
            return out
        }
        val out = ByteArrayOutputStream(data.size)
        var prev = ByteArray(rowLength)
        var p = 0
        while (p < data.size) {
            val type = data[p].toInt() and 0xFF
            p++
            val n = minOf(rowLength, data.size - p)
            val cur = ByteArray(rowLength)
            System.arraycopy(data, p, cur, 0, n)
            p += n
            for (i in 0 until n) {
                val left = if (i >= bpp) cur[i - bpp].toInt() and 0xFF else 0
                val up = prev[i].toInt() and 0xFF
                val upLeft = if (i >= bpp) prev[i - bpp].toInt() and 0xFF else 0
                val x = cur[i].toInt() and 0xFF
                cur[i] = when (type) {
                    1 -> x + left
                    2 -> x + up
                    3 -> x + (left + up) / 2
                    4 -> x + paeth(left, up, upLeft)
                    else -> x
                }.toByte()
            }
            out.write(cur, 0, n)
            prev = cur
        }
        return out.toByteArray()
    }

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = kotlin.math.abs(p - a)
        val pb = kotlin.math.abs(p - b)
        val pc = kotlin.math.abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    private fun asciiHex(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size / 2)
        var high = -1
        for (b in data) {
            val c = b.toInt() and 0xFF
            if (c == '>'.code) break
            val v = PdfLexer.hexValue(c)
            if (v < 0) continue
            if (high < 0) {
                high = v
            } else {
                out.write(high * 16 + v)
                high = -1
            }
        }
        if (high >= 0) out.write(high * 16)
        return out.toByteArray()
    }

    private fun ascii85(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size)
        val group = IntArray(5)
        var n = 0
        var i = 0
        if (data.size >= 2 && data[0] == '<'.code.toByte() && data[1] == '~'.code.toByte()) i = 2
        while (i < data.size) {
            val c = data[i].toInt() and 0xFF
            i++
            if (c == '~'.code) break
            if (PdfLexer.isSpace(c)) continue
            if (c == 'z'.code && n == 0) {
                repeat(4) { out.write(0) }
                continue
            }
            if (c < '!'.code || c > 'u'.code) continue
            group[n++] = c - '!'.code
            if (n == 5) {
                var v = 0L
                for (d in group) v = v * 85 + d
                for (k in 3 downTo 0) out.write(((v shr (k * 8)) and 0xFF).toInt())
                n = 0
            }
        }
        if (n > 1) {
            for (k in n until 5) group[k] = 84
            var v = 0L
            for (d in group) v = v * 85 + d
            for (k in 3 downTo 5 - n) out.write(((v shr (k * 8)) and 0xFF).toInt())
        }
        return out.toByteArray()
    }
}
