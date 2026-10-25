package dev.cluvex.strata.core.hex

import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.nativebridge.NativeBridge
import dev.cluvex.strata.core.root.RootManager
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

interface ByteSource : Closeable {
    val size: Long
    val isWritable: Boolean

    fun read(offset: Long, length: Int): ByteArray

    fun indexOf(pattern: ByteArray, from: Long): Long
    fun lastIndexOf(pattern: ByteArray, from: Long): Long

    @Throws(IOException::class)
    fun write(offset: Long, data: ByteArray)
}

class FileChannelByteSource(file: File) : ByteSource {

    override val isWritable: Boolean = file.canWrite()
    private val raf = RandomAccessFile(file, if (isWritable) "rw" else "r")

    override val size: Long get() = synchronized(raf) { raf.length() }

    override fun read(offset: Long, length: Int): ByteArray {
        synchronized(raf) {
            val total = raf.length()
            if (offset < 0 || offset >= total || length <= 0) return ByteArray(0)
            val n = minOf(length.toLong(), total - offset).toInt()
            val buf = ByteArray(n)
            raf.seek(offset)
            raf.readFully(buf)
            return buf
        }
    }

    override fun indexOf(pattern: ByteArray, from: Long): Long =
        ChunkedSearch.indexOf(size, pattern, from) { o, l -> read(o, l) }

    override fun lastIndexOf(pattern: ByteArray, from: Long): Long =
        ChunkedSearch.lastIndexOf(size, pattern, from) { o, l -> read(o, l) }

    override fun write(offset: Long, data: ByteArray) {
        synchronized(raf) {
            if (!isWritable) throw IOException(tr(R.string.hex_file_read_only))
            if (offset < 0 || offset + data.size > raf.length()) throw IOException(tr(R.string.hex_write_past_end))
            raf.seek(offset)
            raf.write(data)
            raf.fd.sync()
        }
    }

    override fun close() = raf.close()
}

class NativeByteSource(private val path: String) : ByteSource {

    override val isWritable: Boolean = File(path).canWrite()

    override val size: Long get() = NativeBridge.size(path) ?: 0L

    override fun read(offset: Long, length: Int): ByteArray =
        NativeBridge.read(path, offset, length) ?: ByteArray(0)

    override fun indexOf(pattern: ByteArray, from: Long): Long = NativeBridge.find(path, pattern, from)

    override fun lastIndexOf(pattern: ByteArray, from: Long): Long = NativeBridge.findLast(path, pattern, from)

    override fun write(offset: Long, data: ByteArray) {
        if (!isWritable) throw IOException(tr(R.string.hex_file_read_only))
        if (!NativeBridge.write(path, offset, data)) throw IOException(tr(R.string.hex_native_write_failed))
    }

    override fun close() = Unit
}

object ByteSources {
    fun open(path: String, preferNative: Boolean = true): ByteSource =
        if (preferNative && NativeBridge.available) NativeByteSource(path) else FileChannelByteSource(File(path))

    suspend fun openAny(path: String, rootEnabled: Boolean): ByteSource {
        val local = File(path)
        if (local.canRead() && (local.canWrite() || !rootEnabled)) return open(path)
        val root = if (rootEnabled) RootManager.access() else null
        if (root == null) {
            if (local.canRead()) return open(path)
            throw IOException(tr(R.string.hex_permission_denied))
        }
        return runCatching { ChannelByteSource(root.openChannel(path, writable = true), isWritable = true) }
            .getOrElse { ChannelByteSource(root.openChannel(path, writable = false), isWritable = false) }
    }
}
