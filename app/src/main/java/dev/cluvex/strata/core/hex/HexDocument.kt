package dev.cluvex.strata.core.hex

import java.io.Closeable
import java.util.TreeMap

class HexDocument(
    private val source: ByteSource,
    private val pageSize: Int = 4096,
    maxCachedPages: Int = 512,
) : Closeable {

    private class Edit(val offset: Long, val before: ByteArray, val after: ByteArray)

    private val lock = Any()
    private val overlay = TreeMap<Long, Byte>()
    private val undoStack = ArrayDeque<Edit>()
    private val redoStack = ArrayDeque<Edit>()
    private val pages = object : LinkedHashMap<Long, ByteArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?): Boolean =
            size > maxCachedPages
    }

    val size: Long get() = source.size
    val isWritable: Boolean get() = source.isWritable
    val isDirty: Boolean get() = synchronized(lock) { overlay.isNotEmpty() }
    val canUndo: Boolean get() = synchronized(lock) { undoStack.isNotEmpty() }
    val canRedo: Boolean get() = synchronized(lock) { redoStack.isNotEmpty() }
    val modifiedCount: Int get() = synchronized(lock) { overlay.size }

    fun isModified(offset: Long): Boolean = synchronized(lock) { overlay.containsKey(offset) }

    fun modifiedOffsets(from: Long, toExclusive: Long): Set<Long> = synchronized(lock) {
        if (toExclusive <= from) emptySet() else overlay.subMap(from, true, toExclusive, false).keys.toSet()
    }

    fun read(offset: Long, length: Int): ByteArray {
        synchronized(lock) {
            val total = source.size
            if (offset < 0 || offset >= total || length <= 0) return ByteArray(0)
            val len = minOf(length.toLong(), total - offset).toInt()
            val out = ByteArray(len)
            var filled = 0
            while (filled < len) {
                val abs = offset + filled
                val page = page(abs / pageSize)
                val inPage = (abs % pageSize).toInt()
                if (inPage >= page.size) break
                val n = minOf(len - filled, page.size - inPage)
                System.arraycopy(page, inPage, out, filled, n)
                filled += n
            }
            for ((k, v) in overlay.subMap(offset, true, offset + len - 1, true)) {
                out[(k - offset).toInt()] = v
            }
            return if (filled < len) out.copyOf(filled) else out
        }
    }

    fun write(offset: Long, bytes: ByteArray): Boolean {
        synchronized(lock) {
            if (bytes.isEmpty() || offset < 0 || offset + bytes.size > source.size) return false
            val before = read(offset, bytes.size)
            if (before.contentEquals(bytes)) return true
            applyRaw(offset, bytes)
            undoStack.addLast(Edit(offset, before, bytes.copyOf()))
            redoStack.clear()
            return true
        }
    }

    fun undo(): Long? {
        synchronized(lock) {
            val e = undoStack.removeLastOrNull() ?: return null
            applyRaw(e.offset, e.before)
            redoStack.addLast(e)
            return e.offset
        }
    }

    fun redo(): Long? {
        synchronized(lock) {
            val e = redoStack.removeLastOrNull() ?: return null
            applyRaw(e.offset, e.after)
            undoStack.addLast(e)
            return e.offset
        }
    }

    fun save(): Int {
        synchronized(lock) {
            if (overlay.isEmpty()) return 0
            val entries = overlay.entries.toList()
            var written = 0
            var i = 0
            while (i < entries.size) {
                var j = i
                while (j + 1 < entries.size && entries[j + 1].key == entries[j].key + 1) j++
                val run = ByteArray(j - i + 1) { entries[i + it].value }
                source.write(entries[i].key, run)
                written += run.size
                i = j + 1
            }
            overlay.clear()
            pages.clear()
            return written
        }
    }

    fun find(pattern: ByteArray, from: Long, forward: Boolean): Long {
        synchronized(lock) {
            if (pattern.isEmpty()) return -1
            if (overlay.isEmpty()) {
                return if (forward) source.indexOf(pattern, from) else source.lastIndexOf(pattern, from)
            }
            val total = source.size
            return if (forward) {
                ChunkedSearch.indexOf(total, pattern, from) { o, l -> read(o, l) }
            } else {
                ChunkedSearch.lastIndexOf(total, pattern, from) { o, l -> read(o, l) }
            }
        }
    }

    fun replaceAll(pattern: ByteArray, replacement: ByteArray, limit: Int = Int.MAX_VALUE): Int {
        require(pattern.size == replacement.size) { "Replacement must be the same length in overwrite mode" }
        synchronized(lock) {
            var count = 0
            var pos = 0L
            while (count < limit) {
                val hit = find(pattern, pos, forward = true)
                if (hit < 0) break
                write(hit, replacement)
                count++
                pos = hit + pattern.size
            }
            return count
        }
    }

    override fun close() = source.close()

    private fun page(index: Long): ByteArray =
        pages.getOrPut(index) { source.read(index * pageSize, pageSize) }

    private fun sourceByte(offset: Long): Byte? {
        val page = page(offset / pageSize)
        val i = (offset % pageSize).toInt()
        return if (i < page.size) page[i] else null
    }

    private fun applyRaw(offset: Long, bytes: ByteArray) {
        for (i in bytes.indices) {
            val abs = offset + i
            if (sourceByte(abs) == bytes[i]) overlay.remove(abs) else overlay[abs] = bytes[i]
        }
    }
}
