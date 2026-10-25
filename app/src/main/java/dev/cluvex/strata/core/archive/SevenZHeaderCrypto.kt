package dev.cluvex.strata.core.archive

import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.SeekableByteChannel
import java.security.SecureRandom
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object SevenZHeaderCrypto {

    private val SIGNATURE = byteArrayOf('7'.code.toByte(), 'z'.code.toByte(), 0xBC.toByte(), 0xAF.toByte(), 0x27, 0x1C)
    private val AES_CODEC_ID = byteArrayOf(0x06, 0xF1.toByte(), 0x07, 0x01)
    private const val START_HEADER_SIZE = 32
    private const val K_HEADER = 0x01
    private const val K_ENCODED_HEADER = 0x17

    const val NUM_CYCLES_POWER = 19

    fun encryptHeader(channel: SeekableByteChannel, password: CharArray, random: SecureRandom = SecureRandom()) {
        require(password.isNotEmpty()) { "Empty password" }
        val start = ByteBuffer.allocate(START_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        channel.position(0)
        readFully(channel, start)
        start.flip()
        val sig = ByteArray(6).also { start.get(it) }
        if (!sig.contentEquals(SIGNATURE)) throw IOException(tr(R.string.archive_7z_not_7z))
        val nextHeaderOffset = start.getLong(12)
        val nextHeaderSize = start.getLong(20)
        if (nextHeaderSize <= 0 || nextHeaderSize > Int.MAX_VALUE) throw IOException(tr(R.string.archive_7z_header_size))

        val header = ByteBuffer.allocate(nextHeaderSize.toInt())
        channel.position(START_HEADER_SIZE + nextHeaderOffset)
        readFully(channel, header)
        val plain = header.array()
        if (plain[0].toInt() != K_HEADER) throw IOException(tr(R.string.archive_7z_header_encoded))

        val iv = ByteArray(16).also { random.nextBytes(it) }
        val key = deriveKey(password, ByteArray(0), NUM_CYCLES_POWER)
        val padded = plain.copyOf((plain.size + 15) / 16 * 16)
        val encrypted = Cipher.getInstance("AES/CBC/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            doFinal(padded)
        }
        key.fill(0)

        val props = byteArrayOf((NUM_CYCLES_POWER or 0x40).toByte(), 0x0F) + iv
        val crc = CRC32().apply { update(plain) }.value
        val encoded = ByteArrayOutputStream().apply {
            write(K_ENCODED_HEADER)
            write(0x06)
            writeNumber(nextHeaderOffset)
            writeNumber(1)
            write(0x09)
            writeNumber(encrypted.size.toLong())
            write(0x00)
            write(0x07)
            write(0x0B)
            writeNumber(1)
            write(0x00)
            writeNumber(1)
            write(0x20 or AES_CODEC_ID.size)
            write(AES_CODEC_ID)
            writeNumber(props.size.toLong())
            write(props)
            write(0x0C)
            writeNumber(plain.size.toLong())
            write(0x0A)
            write(0x01)
            writeUInt32(crc)
            write(0x00)
            write(0x00)
        }.toByteArray()

        channel.position(START_HEADER_SIZE + nextHeaderOffset)
        writeFully(channel, ByteBuffer.wrap(encrypted))
        writeFully(channel, ByteBuffer.wrap(encoded))
        val end = channel.position()
        channel.truncate(end)

        val tail = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(nextHeaderOffset + encrypted.size)
            .putLong(encoded.size.toLong())
            .putInt(CRC32().apply { update(encoded) }.value.toInt())
        val startCrc = CRC32().apply { update(tail.array()) }.value
        val newStart = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).putInt(startCrc.toInt()).put(tail.array())
        channel.position(8)
        writeFully(channel, ByteBuffer.wrap(newStart.array()))
        channel.position(end)
    }

    fun deriveKey(password: CharArray, salt: ByteArray, power: Int): ByteArray {
        val utf16 = SevenZKeys.utf16(password)
        try {
            return SevenZKeys.derive(utf16, salt, power)
        } finally {
            utf16.fill(0)
        }
    }

    private fun ByteArrayOutputStream.writeNumber(value: Long) {
        var v = value
        var first = 0
        var mask = 0x80
        var i = 0
        while (i < 8) {
            if (v < (1L shl (7 * (i + 1)))) {
                first = first or (v ushr (8 * i)).toInt()
                break
            }
            first = first or mask
            mask = mask ushr 1
            i++
        }
        write(first)
        while (i > 0) {
            write((v and 0xFF).toInt())
            v = v ushr 8
            i--
        }
    }

    private fun ByteArrayOutputStream.writeUInt32(value: Long) {
        for (k in 0 until 4) write(((value ushr (8 * k)) and 0xFF).toInt())
    }

    private fun readFully(channel: SeekableByteChannel, buf: ByteBuffer) {
        while (buf.hasRemaining()) if (channel.read(buf) < 0) throw IOException(tr(R.string.archive_7z_truncated))
    }

    private fun writeFully(channel: SeekableByteChannel, buf: ByteBuffer) {
        while (buf.hasRemaining()) channel.write(buf)
    }
}
