package dev.cluvex.strata.core.nativebridge

import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr
import java.io.IOException

object NativeBridge {

    @Volatile
    private var loaded = false

    @Synchronized
    private fun ensureLoaded(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("strata_native")
            loaded = true
            true
        } catch (_: Throwable) {
            false
        }
    }

    val available: Boolean get() = ensureLoaded()

    private external fun version(): String
    private external fun hashFile(path: String, algo: Int): String
    private external fun fileSize(path: String): Long
    private external fun readRange(path: String, offset: Long, length: Int): ByteArray?
    private external fun indexOf(path: String, pattern: ByteArray, from: Long): Long
    private external fun lastIndexOf(path: String, pattern: ByteArray, from: Long): Long
    private external fun writeBytes(path: String, offset: Long, data: ByteArray): Boolean
    private external fun rarList(path: String, password: String?, status: IntArray): Array<String>?
    private external fun rarExtract(path: String, password: String?, sink: RarSink): Int
    private external fun encDeriveKey(password: ByteArray, salt: ByteArray, mCostKib: Int, tCost: Int, pCost: Int): ByteArray?
    private external fun encSeal(key: ByteArray, nonce: ByteArray, aad: ByteArray, data: ByteArray, length: Int): ByteArray?
    private external fun encOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, data: ByteArray, length: Int): ByteArray?

    fun nativeVersion(): String? = if (ensureLoaded()) runCatching { version() }.getOrNull() else null

    enum class HashAlgo(val code: Int, val label: String) {
        CRC32(0, "CRC32"),
        MD5(1, "MD5"),
        SHA1(2, "SHA-1"),
        SHA256(3, "SHA-256"),
    }

    fun hash(path: String, algo: HashAlgo): String? {
        if (!ensureLoaded()) return null
        return runCatching { hashFile(path, algo.code) }.getOrNull()?.ifEmpty { null }
    }

    fun size(path: String): Long? {
        if (!ensureLoaded()) return null
        return fileSize(path).takeIf { it >= 0 }
    }

    fun read(path: String, offset: Long, length: Int): ByteArray? {
        if (!ensureLoaded()) return null
        return readRange(path, offset, length)
    }

    fun find(path: String, pattern: ByteArray, from: Long): Long {
        check(ensureLoaded()) { tr(R.string.files_native_unavailable) }
        val r = indexOf(path, pattern, from)
        if (r < -1) throw IOException(tr(R.string.files_native_search_failed))
        return r
    }

    fun findLast(path: String, pattern: ByteArray, from: Long): Long {
        check(ensureLoaded()) { tr(R.string.files_native_unavailable) }
        val r = lastIndexOf(path, pattern, from)
        if (r < -1) throw IOException(tr(R.string.files_native_search_failed))
        return r
    }

    fun write(path: String, offset: Long, data: ByteArray): Boolean {
        if (!ensureLoaded()) return false
        return writeBytes(path, offset, data)
    }

    fun deriveKey(password: ByteArray, salt: ByteArray, mCostKib: Int, tCost: Int, pCost: Int): ByteArray? {
        if (!ensureLoaded()) return null
        return encDeriveKey(password, salt, mCostKib, tCost, pCost)
    }

    fun seal(key: ByteArray, nonce: ByteArray, aad: ByteArray, data: ByteArray, length: Int = data.size): ByteArray? {
        check(ensureLoaded()) { tr(R.string.files_native_unavailable) }
        return encSeal(key, nonce, aad, data, length)
    }

    fun open(key: ByteArray, nonce: ByteArray, aad: ByteArray, data: ByteArray, length: Int = data.size): ByteArray? {
        check(ensureLoaded()) { tr(R.string.files_native_unavailable) }
        return encOpen(key, nonce, aad, data, length)
    }

    fun rarHeaders(path: String, password: String?, status: IntArray): Array<String>? =
        if (!ensureLoaded()) null.also { status[0] = -1 } else rarList(path, password, status)

    fun rarStream(path: String, password: String?, sink: RarSink): Int =
        if (!ensureLoaded()) -1 else rarExtract(path, password, sink)
}
