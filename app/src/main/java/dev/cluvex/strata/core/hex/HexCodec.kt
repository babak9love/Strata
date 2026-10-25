package dev.cluvex.strata.core.hex

import androidx.annotation.StringRes
import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr

import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.CRC32
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

enum class HexDataType(@StringRes val label: Int) {
    HEX(R.string.model_hex_hex),
    ASCII(R.string.model_hex_ascii),
    UTF8(R.string.model_hex_utf8),
    UTF16(R.string.model_hex_utf16),
    GBK(R.string.model_hex_gbk),
    INT8(R.string.model_hex_int8),
    UINT8(R.string.model_hex_uint8),
    INT16(R.string.model_hex_int16),
    UINT16(R.string.model_hex_uint16),
    INT32(R.string.model_hex_int32),
    UINT32(R.string.model_hex_uint32),
    INT64(R.string.model_hex_int64),
    UINT64(R.string.model_hex_uint64),
    FLOAT(R.string.model_hex_float),
    DOUBLE(R.string.model_hex_double),
    ;

    val endianSensitive: Boolean
        get() = this == UTF16 || ordinal >= INT16.ordinal
}

enum class CopyFormat(@StringRes val label: Int) {
    HEX(R.string.model_format_hex), DECIMAL(R.string.model_format_decimal), BINARY(R.string.model_format_binary), ASCII(R.string.model_hex_ascii),
    BASE64(R.string.model_format_base64), SHA1(R.string.model_format_sha1), MD5(R.string.model_format_md5), CRC32(R.string.model_format_crc32),
}

enum class PasteFormat(@StringRes val label: Int) {
    HEX(R.string.model_format_hex), DECIMAL(R.string.model_format_decimal), BINARY(R.string.model_format_binary), ASCII(R.string.model_hex_ascii), BASE64(R.string.model_format_base64),
}

@OptIn(ExperimentalEncodingApi::class)
object HexCodec {

    private val HEX_DIGITS = "0123456789ABCDEF".toCharArray()

    fun toHex(bytes: ByteArray, separator: String = " "): String {
        val sb = StringBuilder(bytes.size * (2 + separator.length))
        bytes.forEachIndexed { i, b ->
            if (i > 0) sb.append(separator)
            val v = b.toInt() and 0xFF
            sb.append(HEX_DIGITS[v ushr 4]).append(HEX_DIGITS[v and 0xF])
        }
        return sb.toString()
    }

    fun byteToHex(b: Int): String {
        val v = b and 0xFF
        return "${HEX_DIGITS[v ushr 4]}${HEX_DIGITS[v and 0xF]}"
    }

    fun parseHex(text: String): ByteArray {
        val digits = StringBuilder()
        for (token in text.split(Regex("[\\s,;:-]+"))) {
            if (token.isEmpty()) continue
            val t = if (token.startsWith("0x") || token.startsWith("0X")) token.substring(2) else token
            for (c in t) {
                require(c.digitToIntOrNull(16) != null) { tr(R.string.hex_invalid_digit, c.toString()) }
                digits.append(c)
            }
        }
        require(digits.length % 2 == 0) { tr(R.string.hex_odd_digits) }
        return ByteArray(digits.length / 2) { i ->
            ((digits[2 * i].digitToInt(16) shl 4) or digits[2 * i + 1].digitToInt(16)).toByte()
        }
    }

    fun asciiChar(b: Int): Char {
        val v = b and 0xFF
        return if (v in 0x20..0x7E) v.toChar() else '.'
    }

    fun asciiPreview(bytes: ByteArray): String = buildString(bytes.size) { bytes.forEach { append(asciiChar(it.toInt())) } }

    fun formatOffset(offset: Long, fileSize: Long): String {
        val digits = maxOf(8, java.lang.Long.toHexString(maxOf(fileSize - 1, 0)).length)
        return java.lang.Long.toHexString(offset).uppercase(Locale.US).padStart(digits, '0')
    }

    fun parseOffset(text: String, hex: Boolean): Long? {
        val t = text.trim().replace("_", "").replace(" ", "")
        if (t.isEmpty()) return null
        return if (hex) {
            val body = if (t.startsWith("0x", true)) t.substring(2) else t
            body.toLongOrNull(16)
        } else {
            t.toLongOrNull()
        }?.takeIf { it >= 0 }
    }

    fun encode(text: String, type: HexDataType, bigEndian: Boolean): ByteArray {
        val order = if (bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        fun buf(n: Int) = ByteBuffer.allocate(n).order(order)
        return when (type) {
            HexDataType.HEX -> parseHex(text)
            HexDataType.ASCII -> {
                require(text.all { it.code < 128 }) { tr(R.string.hex_non_ascii) }
                text.toByteArray(Charsets.US_ASCII)
            }
            HexDataType.UTF8 -> text.toByteArray(Charsets.UTF_8)
            HexDataType.UTF16 -> text.toByteArray(if (bigEndian) Charsets.UTF_16BE else Charsets.UTF_16LE)
            HexDataType.GBK -> text.toByteArray(Charset.forName("GBK"))
            HexDataType.INT8 -> byteArrayOf(integer(text, -128, 127).toByte())
            HexDataType.UINT8 -> byteArrayOf(integer(text, 0, 255).toByte())
            HexDataType.INT16 -> buf(2).putShort(integer(text, -32768, 32767).toShort()).array()
            HexDataType.UINT16 -> buf(2).putShort(integer(text, 0, 65535).toShort()).array()
            HexDataType.INT32 -> buf(4).putInt(integer(text, Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()).array()
            HexDataType.UINT32 -> buf(4).putInt(integer(text, 0, 0xFFFF_FFFFL).toInt()).array()
            HexDataType.INT64 -> buf(8).putLong(bigInteger(text, BigInteger.valueOf(Long.MIN_VALUE), BigInteger.valueOf(Long.MAX_VALUE)).toLong()).array()
            HexDataType.UINT64 -> buf(8).putLong(bigInteger(text, BigInteger.ZERO, UINT64_MAX).toLong()).array()
            HexDataType.FLOAT -> buf(4).putFloat(decimal(text).toFloat()).array()
            HexDataType.DOUBLE -> buf(8).putDouble(decimal(text)).array()
        }
    }

    private val UINT64_MAX = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)

    private fun parseBigInteger(text: String): BigInteger {
        val t = text.trim().replace("_", "")
        require(t.isNotEmpty()) { tr(R.string.hex_value_empty) }
        val negative = t.startsWith("-")
        val body = t.removePrefix("-").removePrefix("+")
        val value = try {
            if (body.startsWith("0x", ignoreCase = true)) BigInteger(body.substring(2), 16) else BigInteger(body, 10)
        } catch (_: NumberFormatException) {
            throw IllegalArgumentException(tr(R.string.hex_not_integer, text))
        }
        return if (negative) value.negate() else value
    }

    private fun bigInteger(text: String, min: BigInteger, max: BigInteger): BigInteger {
        val v = parseBigInteger(text)
        require(v >= min && v <= max) { tr(R.string.hex_out_of_range, min.toString(), max.toString()) }
        return v
    }

    private fun integer(text: String, min: Long, max: Long): Long =
        bigInteger(text, BigInteger.valueOf(min), BigInteger.valueOf(max)).toLong()

    private fun decimal(text: String): Double =
        text.trim().toDoubleOrNull() ?: throw IllegalArgumentException(tr(R.string.hex_not_number, text))

    fun inspect(bytes: ByteArray, bigEndian: Boolean): List<Pair<String, String>> {
        val order = if (bigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        fun bb(n: Int) = ByteBuffer.wrap(bytes, 0, n).order(order)
        val dash = "—"
        val out = ArrayList<Pair<String, String>>()
        out += tr(R.string.hex_inspector_binary) to (if (bytes.isNotEmpty()) Integer.toBinaryString(bytes[0].toInt() and 0xFF).padStart(8, '0') else dash)
        out += "Int8" to (if (bytes.isNotEmpty()) bytes[0].toString() else dash)
        out += "UInt8" to (if (bytes.isNotEmpty()) (bytes[0].toInt() and 0xFF).toString() else dash)
        out += "Int16" to (if (bytes.size >= 2) bb(2).short.toString() else dash)
        out += "UInt16" to (if (bytes.size >= 2) (bb(2).short.toInt() and 0xFFFF).toString() else dash)
        out += "Int32" to (if (bytes.size >= 4) bb(4).int.toString() else dash)
        out += "UInt32" to (if (bytes.size >= 4) (bb(4).int.toLong() and 0xFFFF_FFFFL).toString() else dash)
        out += "Int64" to (if (bytes.size >= 8) bb(8).long.toString() else dash)
        out += "UInt64" to (if (bytes.size >= 8) bb(8).long.toULong().toString() else dash)
        out += "Float" to (if (bytes.size >= 4) bb(4).float.toString() else dash)
        out += "Double" to (if (bytes.size >= 8) bb(8).double.toString() else dash)
        return out
    }

    fun copyAs(bytes: ByteArray, format: CopyFormat): String = when (format) {
        CopyFormat.HEX -> toHex(bytes)
        CopyFormat.DECIMAL -> bytes.joinToString(" ") { (it.toInt() and 0xFF).toString() }
        CopyFormat.BINARY -> bytes.joinToString(" ") { Integer.toBinaryString(it.toInt() and 0xFF).padStart(8, '0') }
        CopyFormat.ASCII -> String(bytes, Charsets.ISO_8859_1)
        CopyFormat.BASE64 -> Base64.Default.encode(bytes)
        CopyFormat.SHA1 -> digest("SHA-1", bytes)
        CopyFormat.MD5 -> digest("MD5", bytes)
        CopyFormat.CRC32 -> String.format(Locale.US, "%08x", CRC32().apply { update(bytes) }.value)
    }

    private fun digest(algorithm: String, bytes: ByteArray): String =
        toHex(MessageDigest.getInstance(algorithm).digest(bytes), "").lowercase(Locale.US)

    fun pasteFrom(text: String, format: PasteFormat): ByteArray = when (format) {
        PasteFormat.HEX -> parseHex(text)
        PasteFormat.DECIMAL -> text.split(Regex("[\\s,;]+")).filter { it.isNotEmpty() }.map { token ->
            val v = token.toIntOrNull() ?: throw IllegalArgumentException(tr(R.string.hex_not_a_number, token))
            require(v in -128..255) { tr(R.string.hex_byte_out_of_range, v.toString()) }
            v.toByte()
        }.toByteArray()
        PasteFormat.BINARY -> {
            val bits = text.filterNot { it.isWhitespace() }
            require(bits.all { it == '0' || it == '1' }) { tr(R.string.hex_binary_digits_only, "0", "1") }
            require(bits.length % 8 == 0) { tr(R.string.hex_bit_count, 8) }
            ByteArray(bits.length / 8) { i -> bits.substring(i * 8, i * 8 + 8).toInt(2).toByte() }
        }
        PasteFormat.ASCII -> {
            require(text.all { it.code <= 0xFF }) { tr(R.string.hex_outside_latin1, "0x00–0xFF") }
            text.toByteArray(Charsets.ISO_8859_1)
        }
        PasteFormat.BASE64 -> try {
            Base64.Default.decode(text.trim())
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException(tr(R.string.hex_invalid_base64, e.message))
        }
    }
}
