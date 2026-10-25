package dev.cluvex.strata.core.hex

import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

class ChannelByteSource(
    private val channel: FileChannel,
    override val isWritable: Boolean,
) : ByteSource {

    override val size: Long get() = synchronized(channel) { channel.size() }

    override fun read(offset: Long, length: Int): ByteArray {
        synchronized(channel) {
            val total = channel.size()
            if (offset < 0 || offset >= total || length <= 0) return ByteArray(0)
            val n = minOf(length.toLong(), total - offset).toInt()
            val buf = ByteBuffer.allocate(n)
            var pos = offset
            while (buf.hasRemaining()) {
                val r = channel.read(buf, pos)
                if (r <= 0) break
                pos += r
            }
            return if (buf.position() == n) buf.array() else buf.array().copyOf(buf.position())
        }
    }

    override fun indexOf(pattern: ByteArray, from: Long): Long =
        ChunkedSearch.indexOf(size, pattern, from) { o, l -> read(o, l) }

    override fun lastIndexOf(pattern: ByteArray, from: Long): Long =
        ChunkedSearch.lastIndexOf(size, pattern, from) { o, l -> read(o, l) }

    override fun write(offset: Long, data: ByteArray) {
        synchronized(channel) {
            if (!isWritable) throw IOException(tr(R.string.hex_file_read_only))
            if (offset < 0 || offset + data.size > channel.size()) throw IOException(tr(R.string.hex_write_past_end))
            val buf = ByteBuffer.wrap(data)
            var pos = offset
            while (buf.hasRemaining()) pos += channel.write(buf, pos)
            channel.force(false)
        }
    }

    override fun close() = channel.close()
}
