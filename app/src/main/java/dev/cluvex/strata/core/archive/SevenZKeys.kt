package dev.cluvex.strata.core.archive

import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr
import java.security.MessageDigest

object SevenZKeys {
    const val MAX_POWER = 24

    const val NO_HASHING = 0x3f

    private const val CACHE_SIZE = 8
    private val cache = object : LinkedHashMap<String, ByteArray>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > CACHE_SIZE
    }

    @Volatile
    var derivations: Int = 0
        private set

    @JvmStatic
    @Synchronized
    fun key(passwordUtf16: ByteArray, salt: ByteArray, power: Int): ByteArray {
        val id = cacheId(passwordUtf16, salt, power)
        cache[id]?.let { return it.copyOf() }
        val key = derive(passwordUtf16, salt, power)
        derivations++
        cache[id] = key.copyOf()
        return key
    }

    @JvmStatic
    @Synchronized
    fun clear() {
        cache.values.forEach { it.fill(0) }
        cache.clear()
    }

    @JvmStatic
    fun derive(passwordUtf16: ByteArray, salt: ByteArray, power: Int): ByteArray {
        if (power == NO_HASHING) {
            val key = ByteArray(32)
            (salt + passwordUtf16).copyInto(key, endIndex = minOf(32, salt.size + passwordUtf16.size))
            return key
        }
        require(power in 0..MAX_POWER) { tr(R.string.archive_7z_cycles, power) }
        val block = salt + passwordUtf16 + ByteArray(8)
        val counterAt = block.size - 8
        val sha = MessageDigest.getInstance("SHA-256")
        val rounds = 1L shl power
        var round = 0L
        while (round < rounds) {
            sha.update(block)
            var i = counterAt
            while (i < block.size) {
                block[i] = (block[i] + 1).toByte()
                if (block[i].toInt() != 0) break
                i++
            }
            round++
        }
        block.fill(0)
        return sha.digest()
    }

    @JvmStatic
    fun utf16(password: CharArray): ByteArray {
        val bytes = ByteArray(password.size * 2)
        password.forEachIndexed { i, c ->
            bytes[2 * i] = c.code.toByte()
            bytes[2 * i + 1] = (c.code shr 8).toByte()
        }
        return bytes
    }

    private fun cacheId(password: ByteArray, salt: ByteArray, power: Int): String {
        val sha = MessageDigest.getInstance("SHA-256")
        sha.update(password)
        sha.update(0)
        sha.update(salt)
        sha.update(power.toByte())
        return sha.digest().joinToString("") { "%02x".format(it) }
    }
}
