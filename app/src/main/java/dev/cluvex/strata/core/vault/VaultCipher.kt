package dev.cluvex.strata.core.vault

import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr

import dev.cluvex.strata.core.nativebridge.NativeBridge
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom

object VaultCipher {

    const val HEADER_SIZE = 64
    const val TAG_SIZE = 16
    const val ID_SIZE = 16
    const val NONCE_SIZE = 24
    const val DEFAULT_CHUNK_LOG2 = 16

    private val MAGIC = "STRAVLT1".toByteArray(Charsets.US_ASCII)
    private const val VERSION: Byte = 1
    private const val PREFIX_SIZE = 19
    private const val ID_AT = 16
    private const val PREFIX_AT = 32
    private const val MAX_CHUNKS = 0xFFFF_FFFFL

    fun header(id: ByteArray, prefix: ByteArray, chunkLog2: Int): ByteArray {
        require(id.size == ID_SIZE && prefix.size == PREFIX_SIZE && chunkLog2 in 12..24)
        return ByteArray(HEADER_SIZE).also { h ->
            MAGIC.copyInto(h)
            h[8] = VERSION
            h[9] = chunkLog2.toByte()
            id.copyInto(h, ID_AT)
            prefix.copyInto(h, PREFIX_AT)
        }
    }

    fun encrypt(
        input: InputStream,
        output: OutputStream,
        key: ByteArray,
        id: ByteArray,
        random: SecureRandom,
        chunkLog2: Int = DEFAULT_CHUNK_LOG2,
        alive: () -> Boolean = { true },
        onBytes: (Long) -> Unit = {},
    ): Long {
        val prefix = ByteArray(PREFIX_SIZE).also(random::nextBytes)
        val header = header(id, prefix, chunkLog2)
        val chunk = 1 shl chunkLog2
        val source = BufferedInputStream(input, chunk + 1)
        output.write(header)
        val buf = ByteArray(chunk)
        var index = 0L
        var total = 0L
        try {
            while (true) {
                if (!alive()) throw VaultLockedException()
                val n = readUpTo(source, buf, chunk)
                val last = n < chunk || atEnd(source)
                val sealed = NativeBridge.seal(key, nonce(prefix, index, last), header, buf, n) ?: throw IOException(tr(R.string.files_cipher_failed))
                output.write(sealed)
                total += n
                onBytes(total)
                if (last) break
                index++
                if (index > MAX_CHUNKS) throw IOException(tr(R.string.files_cipher_too_large))
            }
        } finally {
            buf.fill(0)
        }
        return total
    }

    fun decrypt(
        input: InputStream,
        output: OutputStream,
        key: ByteArray,
        id: ByteArray,
        alive: () -> Boolean = { true },
        onBytes: (Long) -> Unit = {},
    ): Long {
        val stream = DecryptingStream(input, { key }, id, alive)
        var total = 0L
        stream.use {
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                output.write(buf, 0, n)
                total += n
                onBytes(total)
            }
            buf.fill(0)
        }
        return total
    }

    fun seal(key: ByteArray, plain: ByteArray, aad: ByteArray, random: SecureRandom): ByteArray {
        val nonce = ByteArray(NONCE_SIZE).also(random::nextBytes)
        val sealed = NativeBridge.seal(key, nonce, aad, plain) ?: throw IOException(tr(R.string.files_cipher_failed))
        return nonce + sealed
    }

    fun open(key: ByteArray, blob: ByteArray, aad: ByteArray): ByteArray? {
        if (blob.size < NONCE_SIZE + TAG_SIZE) return null
        val nonce = blob.copyOfRange(0, NONCE_SIZE)
        return NativeBridge.open(key, nonce, aad, blob.copyOfRange(NONCE_SIZE, blob.size))
    }

    internal fun nonce(prefix: ByteArray, index: Long, last: Boolean): ByteArray =
        ByteArray(NONCE_SIZE).also { n ->
            prefix.copyInto(n)
            n[19] = (index ushr 24).toByte()
            n[20] = (index ushr 16).toByte()
            n[21] = (index ushr 8).toByte()
            n[22] = index.toByte()
            n[23] = if (last) 1 else 0
        }

    private fun readUpTo(input: InputStream, buf: ByteArray, want: Int): Int {
        var n = 0
        while (n < want) {
            val r = input.read(buf, n, want - n)
            if (r < 0) break
            n += r
        }
        return n
    }

    private fun atEnd(input: BufferedInputStream): Boolean {
        input.mark(1)
        val b = input.read()
        input.reset()
        return b < 0
    }

    class DecryptingStream(
        input: InputStream,
        private val key: () -> ByteArray,
        private val id: ByteArray,
        private val alive: () -> Boolean = { true },
    ) : InputStream() {

        private val header = ByteArray(HEADER_SIZE)
        private val source: BufferedInputStream
        private val prefix: ByteArray
        private val sealedSize: Int
        private val sealed: ByteArray
        private var plain = ByteArray(0)
        private var at = 0
        private var index = 0L
        private var finished = false

        init {
            if (readUpTo(input, header, HEADER_SIZE) < HEADER_SIZE || !MAGIC.indices.all { header[it] == MAGIC[it] }) throw VaultDamagedException()
            if (header[8] != VERSION) throw VaultDamagedException()
            val chunkLog2 = header[9].toInt()
            if (chunkLog2 !in 12..24) throw VaultDamagedException()
            if (!id.indices.all { header[ID_AT + it] == id[it] }) throw VaultDamagedException()
            prefix = header.copyOfRange(PREFIX_AT, PREFIX_AT + PREFIX_SIZE)
            sealedSize = (1 shl chunkLog2) + TAG_SIZE
            sealed = ByteArray(sealedSize)
            source = BufferedInputStream(input, sealedSize + 1)
        }

        private fun fill(): Boolean {
            if (finished) return false
            if (!alive()) throw VaultLockedException()
            val n = readUpTo(source, sealed, sealedSize)
            if (n < TAG_SIZE) throw VaultDamagedException()
            val last = n < sealedSize || atEnd(source)
            plain.fill(0)
            plain = NativeBridge.open(key(), nonce(prefix, index, last), header, sealed, n) ?: throw VaultDamagedException()
            at = 0
            index++
            if (last) finished = true
            return true
        }

        override fun read(): Int {
            while (at >= plain.size) if (!fill()) return -1
            return plain[at++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (at >= plain.size) if (!fill()) return -1
            val n = minOf(len, plain.size - at)
            plain.copyInto(b, off, at, at + n)
            at += n
            return n
        }

        override fun available(): Int = plain.size - at

        override fun close() {
            plain.fill(0)
            sealed.fill(0)
            source.close()
        }
    }
}
