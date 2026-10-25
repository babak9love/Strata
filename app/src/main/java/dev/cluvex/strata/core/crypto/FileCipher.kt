package dev.cluvex.strata.core.crypto

import dev.cluvex.strata.R
import dev.cluvex.strata.core.fs.FileAccess
import dev.cluvex.strata.core.fs.FsUtil
import dev.cluvex.strata.core.fs.LocalFileAccess
import dev.cluvex.strata.core.fs.OpProgress
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.nativebridge.NativeBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.CharBuffer
import java.security.SecureRandom
import java.text.Normalizer

class IncorrectPasswordException : IOException(tr(R.string.files_cipher_wrong_password))

class DamagedFileException : IOException(tr(R.string.files_cipher_damaged))

object FileCipher {

    const val EXTENSION = ".senc"
    const val HEADER_SIZE = 64
    const val TAG_SIZE = 16

    private val MAGIC = "STRATENC".toByteArray(Charsets.US_ASCII)
    private const val VERSION: Byte = 1
    private const val KDF_ARGON2ID: Byte = 1
    private const val CIPHER_XCHACHA20_POLY1305: Byte = 1
    private const val SALT_SIZE = 16
    private const val PREFIX_SIZE = 19

    const val MAX_MEMORY_KIB = 256 * 1024
    const val MAX_PASSES = 16
    const val MAX_LANES = 4

    data class KdfParams(val memoryKib: Int = 64 * 1024, val passes: Int = 3, val lanes: Int = 1)

    fun encryptedName(name: String): String = name + EXTENSION

    fun decryptedName(name: String): String =
        if (name.endsWith(EXTENSION, ignoreCase = true) && name.length > EXTENSION.length) name.dropLast(EXTENSION.length) else "$name.decrypted"

    fun looksEncrypted(header: ByteArray): Boolean =
        header.size >= MAGIC.size && MAGIC.indices.all { header[it] == MAGIC[it] }

    fun encrypt(
        path: String,
        outputPath: String,
        password: CharArray,
        from: FileAccess = LocalFileAccess,
        to: FileAccess = from,
        params: KdfParams = KdfParams(),
        chunkLog2: Int = 16,
        random: SecureRandom = SecureRandom(),
    ): Flow<OpProgress> = flow {
        require(password.isNotEmpty()) { "The password cannot be empty" }
        require(chunkLog2 in 12..24) { "Chunk size out of range" }
        val src = from.file(path)
        if (!src.isFile) throw IOException(tr(R.string.files_not_a_file, src.name))
        val out = to.file(outputPath)
        if (FsUtil.exists(out, to)) throw IOException(tr(R.string.common_already_exists, out.name))

        val salt = ByteArray(SALT_SIZE).also(random::nextBytes)
        val prefix = ByteArray(PREFIX_SIZE).also(random::nextBytes)
        val header = header(params, chunkLog2, salt, prefix)
        val key = deriveKey(password, salt, params)
        val chunk = 1 shl chunkLog2
        val total = src.length()

        writeCleaningUp(out, to) {
            try {
                BufferedInputStream(from.openInput(src), chunk + 1).use { input ->
                    to.openOutput(out).buffered(chunk + TAG_SIZE).use { output ->
                        output.write(header)
                        val buf = ByteArray(chunk)
                        val progress = Progress(src.name, total)
                        var index = 0L
                        var done = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = readUpTo(input, buf, chunk)
                            val last = n < chunk || atEnd(input)
                            val sealed = NativeBridge.seal(key, nonce(prefix, index, last), header, buf, n)
                                ?: throw IOException(tr(R.string.files_cipher_failed))
                            output.write(sealed)
                            done += n
                            progress.report(this@flow, done)
                            if (last) break
                            index++
                            if (index > MAX_CHUNKS) throw IOException(tr(R.string.files_cipher_too_large))
                        }
                        buf.fill(0)
                    }
                }
            } finally {
                key.fill(0)
            }
        }
        emit(OpProgress.Finished(1, emptyList(), created = listOf(out.absolutePath)))
    }.flowOn(Dispatchers.IO)

    fun decrypt(
        path: String,
        outputPath: String,
        password: CharArray,
        from: FileAccess = LocalFileAccess,
        to: FileAccess = from,
    ): Flow<OpProgress> = flow {
        val src = from.file(path)
        if (!src.isFile) throw IOException(tr(R.string.files_not_a_file, src.name))
        val out = to.file(outputPath)
        if (FsUtil.exists(out, to)) throw IOException(tr(R.string.common_already_exists, out.name))
        val total = src.length()

        from.openInput(src).use { raw ->
            val header = ByteArray(HEADER_SIZE)
            if (readUpTo(raw, header, HEADER_SIZE) < HEADER_SIZE || !looksEncrypted(header)) {
                throw IOException(tr(R.string.files_cipher_not_ours, src.name))
            }
            val (params, chunkLog2, salt, prefix) = parseHeader(header)
            val key = deriveKey(password, salt, params)
            val chunk = 1 shl chunkLog2
            val sealedSize = chunk + TAG_SIZE
            try {
                writeCleaningUp(out, to) {
                    val body = BufferedInputStream(raw, sealedSize + 1)
                    to.openOutput(out).buffered(chunk).use { output ->
                        val buf = ByteArray(sealedSize)
                        val progress = Progress(src.name, total)
                        var index = 0L
                        var done = HEADER_SIZE.toLong()
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = readUpTo(body, buf, sealedSize)
                            if (n < TAG_SIZE) throw if (index == 0L) IncorrectPasswordException() else DamagedFileException()
                            val last = n < sealedSize || atEnd(body)
                            val plain = NativeBridge.open(key, nonce(prefix, index, last), header, buf, n)
                                ?: throw if (index == 0L) IncorrectPasswordException() else DamagedFileException()
                            output.write(plain)
                            plain.fill(0)
                            done += n
                            progress.report(this@flow, done)
                            if (last) break
                            index++
                        }
                    }
                }
            } finally {
                key.fill(0)
            }
        }
        emit(OpProgress.Finished(1, emptyList(), created = listOf(out.absolutePath)))
    }.flowOn(Dispatchers.IO)

    private const val MAX_CHUNKS = 0xFFFF_FFFFL

    internal fun header(params: KdfParams, chunkLog2: Int, salt: ByteArray, prefix: ByteArray): ByteArray =
        ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(MAGIC)
            put(VERSION)
            put(KDF_ARGON2ID)
            put(CIPHER_XCHACHA20_POLY1305)
            put(chunkLog2.toByte())
            putInt(params.memoryKib)
            putInt(params.passes)
            putInt(params.lanes)
            put(salt)
            put(prefix)
        }.array()

    private data class Parsed(val params: KdfParams, val chunkLog2: Int, val salt: ByteArray, val prefix: ByteArray)

    private fun parseHeader(h: ByteArray): Parsed {
        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        if (h[8] != VERSION) throw IOException(tr(R.string.files_cipher_newer, h[8].toInt()))
        if (h[9] != KDF_ARGON2ID || h[10] != CIPHER_XCHACHA20_POLY1305) throw IOException(tr(R.string.files_cipher_unsupported))
        val chunkLog2 = h[11].toInt()
        val memory = b.getInt(12)
        val passes = b.getInt(16)
        val lanes = b.getInt(20)
        val sane = chunkLog2 in 12..24 && memory in 8 * lanes..MAX_MEMORY_KIB && passes in 1..MAX_PASSES &&
            lanes in 1..MAX_LANES && (59 until HEADER_SIZE).all { h[it] == 0.toByte() }
        if (!sane) throw DamagedFileException()
        return Parsed(KdfParams(memory, passes, lanes), chunkLog2, h.copyOfRange(24, 40), h.copyOfRange(40, 59))
    }

    internal fun nonce(prefix: ByteArray, index: Long, last: Boolean): ByteArray =
        ByteArray(24).also { n ->
            prefix.copyInto(n)
            n[19] = (index ushr 24).toByte()
            n[20] = (index ushr 16).toByte()
            n[21] = (index ushr 8).toByte()
            n[22] = index.toByte()
            n[23] = if (last) 1 else 0
        }

    internal fun passwordBytes(password: CharArray): ByteArray {
        val nfc = Normalizer.normalize(CharBuffer.wrap(password), Normalizer.Form.NFC)
        val encoded = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(nfc))
        return ByteArray(encoded.remaining()).also { encoded.get(it) }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, p: KdfParams): ByteArray {
        val pw = passwordBytes(password)
        try {
            return NativeBridge.deriveKey(pw, salt, p.memoryKib, p.passes, p.lanes)
                ?: throw IOException(tr(if (NativeBridge.available) R.string.files_cipher_bad_params else R.string.files_cipher_unavailable))
        } finally {
            pw.fill(0)
        }
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

    private class Progress(private val name: String, private val total: Long) {
        private var last = -1L

        suspend fun report(collector: FlowCollector<OpProgress>, done: Long) {
            if (last < 0 || done - last >= PROGRESS_STEP || done >= total) {
                last = done
                collector.emit(OpProgress.Running(name, done, total, 0, 1))
            }
        }
    }

    private const val PROGRESS_STEP = 4L * 1024 * 1024

    private inline fun writeCleaningUp(out: File, access: FileAccess, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            runCatching { FsUtil.deleteRecursively(out, access) }
            throw e
        }
    }
}
