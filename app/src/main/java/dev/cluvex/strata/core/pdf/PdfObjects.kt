package dev.cluvex.strata.core.pdf

import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

open class PdfException(message: String) : IOException(message)

class PdfPasswordException(message: String) : PdfException(message)

sealed interface PdfObject

data object PdfNull : PdfObject

data class PdfBoolean(val value: Boolean) : PdfObject

class PdfNumber(val text: String) : PdfObject {
    constructor(value: Long) : this(value.toString())

    val long: Long?
        get() = text.toLongOrNull() ?: text.toDoubleOrNull()?.takeIf { it.isFinite() && it in -9.0e18..9.0e18 }?.toLong()

    val int: Int?
        get() = long?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

    override fun equals(other: Any?): Boolean = other is PdfNumber && other.text == text
    override fun hashCode(): Int = text.hashCode()
    override fun toString(): String = text
}

class PdfString(val bytes: ByteArray, val hex: Boolean = false) : PdfObject {
    override fun equals(other: Any?): Boolean = other is PdfString && other.bytes.contentEquals(bytes)
    override fun hashCode(): Int = bytes.contentHashCode()
}

data class PdfName(val name: String) : PdfObject

class PdfArray(val items: MutableList<PdfObject> = ArrayList()) : PdfObject

class PdfDictionary(val entries: LinkedHashMap<String, PdfObject> = LinkedHashMap()) : PdfObject {
    operator fun get(key: String): PdfObject? = entries[key]
    operator fun set(key: String, value: PdfObject) {
        entries[key] = value
    }

    fun remove(key: String): PdfObject? = entries.remove(key)
    fun name(key: String): String? = (entries[key] as? PdfName)?.name
    fun copy(): PdfDictionary = PdfDictionary(LinkedHashMap(entries))
}

data class PdfReference(val number: Int, val generation: Int) : PdfObject

class PdfStream(val dictionary: PdfDictionary, val data: StreamData) : PdfObject

sealed interface StreamData {
    class Range(val offset: Long, val length: Long, val owner: PdfReference?) : StreamData
    class Bytes(val bytes: ByteArray) : StreamData
}

interface PdfSource : Closeable {
    val size: Long
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int

    fun readFully(position: Long, length: Int): ByteArray {
        val out = ByteArray(length)
        var done = 0
        while (done < length) {
            val n = read(position + done, out, done, length - done)
            if (n <= 0) break
            done += n
        }
        return if (done == length) out else out.copyOf(done)
    }
}

class BytesSource(private val bytes: ByteArray) : PdfSource {
    override val size: Long get() = bytes.size.toLong()

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (position < 0 || position >= bytes.size) return -1
        val n = minOf(length.toLong(), bytes.size - position).toInt()
        System.arraycopy(bytes, position.toInt(), buffer, offset, n)
        return n
    }

    override fun close() = Unit
}

class ChannelSource(private val channel: SeekableByteChannel) : PdfSource {
    override val size: Long = channel.size()
    private val blocks = BlockCache()

    private class BlockCache : LinkedHashMap<Long, ByteArray>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean = this.size > MAX_BLOCKS
    }

    private fun block(index: Long): ByteArray = blocks.getOrPut(index) {
        val start = index * BLOCK
        val length = minOf(BLOCK.toLong(), size - start).toInt().coerceAtLeast(0)
        val buffer = ByteBuffer.allocate(length)
        channel.position(start)
        while (buffer.hasRemaining()) {
            if (channel.read(buffer) < 0) break
        }
        buffer.array().copyOf(buffer.position())
    }

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (position < 0 || position >= size) return -1
        val index = position / BLOCK
        val block = block(index)
        val inBlock = (position - index * BLOCK).toInt()
        if (inBlock >= block.size) return -1
        val n = minOf(length, block.size - inBlock)
        System.arraycopy(block, inBlock, buffer, offset, n)
        return n
    }

    override fun close() = channel.close()

    private companion object {
        const val BLOCK = 64 * 1024
        const val MAX_BLOCKS = 64
    }
}
