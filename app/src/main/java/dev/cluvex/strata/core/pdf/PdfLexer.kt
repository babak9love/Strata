package dev.cluvex.strata.core.pdf

import java.io.ByteArrayOutputStream

sealed interface Token {
    data class Num(val text: String) : Token
    data class Name(val name: String) : Token
    class Str(val bytes: ByteArray, val hex: Boolean) : Token
    data class Word(val text: String) : Token
    data object DictOpen : Token
    data object DictClose : Token
    data object ArrayOpen : Token
    data object ArrayClose : Token
    data object End : Token
}

class PdfLexer(private val source: PdfSource, start: Long = 0) {

    var position: Long = start

    private val buffer = ByteArray(8192)
    private var bufferStart = -1L
    private var bufferLength = 0

    fun peek(): Int {
        if (bufferStart < 0 || position < bufferStart || position >= bufferStart + bufferLength) {
            bufferStart = position
            bufferLength = source.read(position, buffer, 0, buffer.size).coerceAtLeast(0)
        }
        if (position >= bufferStart + bufferLength) return -1
        return buffer[(position - bufferStart).toInt()].toInt() and 0xFF
    }

    fun next(): Int {
        val c = peek()
        if (c >= 0) position++
        return c
    }

    fun skipSpace() {
        while (true) {
            val c = peek()
            when {
                c < 0 -> return
                isSpace(c) -> position++
                c == '%'.code -> {
                    while (true) {
                        val d = peek()
                        if (d < 0 || d == 10 || d == 13) break
                        position++
                    }
                }
                else -> return
            }
        }
    }

    fun nextToken(): Token {
        while (true) {
            skipSpace()
            val c = peek()
            if (c < 0) return Token.End
            when (c) {
                '/'.code -> {
                    position++
                    return Token.Name(readName())
                }
                '('.code -> {
                    position++
                    return Token.Str(readLiteral(), hex = false)
                }
                '<'.code -> {
                    position++
                    if (peek() == '<'.code) {
                        position++
                        return Token.DictOpen
                    }
                    return Token.Str(readHex(), hex = true)
                }
                '>'.code -> {
                    position++
                    if (peek() == '>'.code) position++
                    return Token.DictClose
                }
                '['.code -> {
                    position++
                    return Token.ArrayOpen
                }
                ']'.code -> {
                    position++
                    return Token.ArrayClose
                }
                '{'.code, '}'.code -> {
                    position++
                    return Token.Word(c.toChar().toString())
                }
                ')'.code -> position++
                else -> {
                    val sb = StringBuilder()
                    while (true) {
                        val d = peek()
                        if (d < 0 || isSpace(d) || isDelimiter(d)) break
                        sb.append(d.toChar())
                        position++
                    }
                    if (sb.isEmpty()) {
                        position++
                        continue
                    }
                    val text = sb.toString()
                    return if (NUMBER.matches(text)) Token.Num(text) else Token.Word(text)
                }
            }
        }
    }

    fun readObject(depth: Int = 0): PdfObject {
        if (depth > MAX_DEPTH) throw PdfException("Objects are nested too deeply")
        return when (val t = nextToken()) {
            is Token.Num -> {
                val first = t.text.toIntOrNull()
                if (first != null && first >= 0) {
                    val save = position
                    val second = nextToken()
                    val generation = (second as? Token.Num)?.text?.toIntOrNull()
                    if (generation != null && generation >= 0) {
                        val third = nextToken()
                        if (third is Token.Word && third.text == "R") return PdfReference(first, generation)
                    }
                    position = save
                }
                PdfNumber(t.text)
            }
            is Token.Name -> PdfName(t.name)
            is Token.Str -> PdfString(t.bytes, t.hex)
            Token.ArrayOpen -> {
                val items = ArrayList<PdfObject>()
                while (true) {
                    val save = position
                    val n = nextToken()
                    if (n == Token.ArrayClose || n == Token.End || n == Token.DictClose) break
                    if (n is Token.Word && n.text in STOP_WORDS) {
                        position = save
                        break
                    }
                    position = save
                    items += readObject(depth + 1)
                }
                PdfArray(items)
            }
            Token.DictOpen -> {
                val dict = PdfDictionary()
                while (true) {
                    val save = position
                    when (val k = nextToken()) {
                        Token.DictClose, Token.End -> break
                        is Token.Name -> {
                            val valueStart = position
                            val v = nextToken()
                            if (v == Token.DictClose) {
                                dict[k.name] = PdfNull
                                break
                            }
                            if (v is Token.Word && v.text in STOP_WORDS) {
                                position = valueStart
                                break
                            }
                            position = valueStart
                            dict[k.name] = readObject(depth + 1)
                        }
                        is Token.Word -> if (k.text in STOP_WORDS) {
                            position = save
                            break
                        }
                        else -> Unit
                    }
                }
                dict
            }
            is Token.Word -> when (t.text) {
                "true" -> PdfBoolean(true)
                "false" -> PdfBoolean(false)
                else -> PdfNull
            }
            else -> PdfNull
        }
    }

    private fun readName(): String {
        val sb = StringBuilder()
        while (true) {
            val c = peek()
            if (c < 0 || isSpace(c) || isDelimiter(c)) break
            position++
            if (c == '#'.code) {
                val save = position
                val h = hexValue(peek())
                if (h >= 0) {
                    position++
                    val l = hexValue(peek())
                    if (l >= 0) {
                        position++
                        sb.append((h * 16 + l).toChar())
                        continue
                    }
                }
                position = save
            }
            sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun readLiteral(): ByteArray {
        val out = ByteArrayOutputStream()
        var depth = 1
        loop@ while (true) {
            val c = next()
            when (c) {
                -1 -> break@loop
                '('.code -> {
                    depth++
                    out.write(c)
                }
                ')'.code -> {
                    depth--
                    if (depth == 0) break@loop
                    out.write(c)
                }
                '\\'.code -> {
                    when (val e = next()) {
                        -1 -> break@loop
                        'n'.code -> out.write(10)
                        'r'.code -> out.write(13)
                        't'.code -> out.write(9)
                        'b'.code -> out.write(8)
                        'f'.code -> out.write(12)
                        13 -> if (peek() == 10) position++
                        10 -> Unit
                        in '0'.code..'7'.code -> {
                            var v = e - '0'.code
                            repeat(2) {
                                val d = peek()
                                if (d in '0'.code..'7'.code) {
                                    v = v * 8 + (d - '0'.code)
                                    position++
                                }
                            }
                            out.write(v and 0xFF)
                        }
                        else -> out.write(e)
                    }
                }
                13 -> {
                    if (peek() == 10) position++
                    out.write(10)
                }
                else -> out.write(c)
            }
        }
        return out.toByteArray()
    }

    private fun readHex(): ByteArray {
        val out = ByteArrayOutputStream()
        var high = -1
        while (true) {
            val c = next()
            if (c < 0 || c == '>'.code) break
            val v = hexValue(c)
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

    companion object {
        private const val MAX_DEPTH = 200
        private val NUMBER = Regex("[+-]?(\\d+\\.?\\d*|\\.\\d+)")
        private val STOP_WORDS = setOf("endobj", "stream", "endstream", "obj", "xref", "trailer", "startxref")

        fun isSpace(c: Int): Boolean = c == 0 || c == 9 || c == 10 || c == 12 || c == 13 || c == 32

        fun isDelimiter(c: Int): Boolean = c == '('.code || c == ')'.code || c == '<'.code || c == '>'.code ||
            c == '['.code || c == ']'.code || c == '{'.code || c == '}'.code || c == '/'.code || c == '%'.code

        fun hexValue(c: Int): Int = when (c) {
            in '0'.code..'9'.code -> c - '0'.code
            in 'a'.code..'f'.code -> c - 'a'.code + 10
            in 'A'.code..'F'.code -> c - 'A'.code + 10
            else -> -1
        }
    }
}
