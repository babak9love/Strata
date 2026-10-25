package dev.cluvex.strata.core.hex

object ChunkedSearch {

    const val DEFAULT_CHUNK = 64 * 1024

    fun indexOf(
        size: Long,
        pattern: ByteArray,
        from: Long,
        chunk: Int = DEFAULT_CHUNK,
        read: (offset: Long, length: Int) -> ByteArray,
    ): Long {
        if (pattern.isEmpty()) return -1
        val plen = pattern.size
        var pos = maxOf(from, 0L)
        if (pos >= size || size - pos < plen) return -1
        val overlap = plen - 1
        val window = maxOf(chunk, 1) + overlap
        while (true) {
            val want = minOf(window.toLong(), size - pos).toInt()
            val buf = read(pos, want)
            val n = buf.size
            if (n < plen) return -1
            val i = indexIn(buf, n, pattern)
            if (i >= 0) return pos + i
            if (pos + n >= size) return -1
            pos += (n - overlap)
        }
    }

    fun lastIndexOf(
        size: Long,
        pattern: ByteArray,
        from: Long,
        chunk: Int = DEFAULT_CHUNK,
        read: (offset: Long, length: Int) -> ByteArray,
    ): Long {
        if (pattern.isEmpty() || from < 0) return -1
        val plen = pattern.size.toLong()
        if (size < plen) return -1
        val maxStart = minOf(from, size - plen)
        val overlap = plen - 1
        val step = maxOf(chunk, 1).toLong()
        var end = maxStart + plen
        while (true) {
            val start = maxOf(0L, end - (step + overlap))
            val buf = read(start, (end - start).toInt())
            val i = lastIndexIn(buf, buf.size, pattern)
            if (i >= 0) return start + i
            if (start == 0L) return -1
            end = start + overlap
        }
    }

    fun indexIn(haystack: ByteArray, length: Int, pattern: ByteArray): Int {
        val last = length - pattern.size
        if (last < 0) return -1
        val first = pattern[0]
        var i = 0
        outer@ while (i <= last) {
            if (haystack[i] != first) {
                i++
                continue
            }
            for (k in 1 until pattern.size) {
                if (haystack[i + k] != pattern[k]) {
                    i++
                    continue@outer
                }
            }
            return i
        }
        return -1
    }

    fun lastIndexIn(haystack: ByteArray, length: Int, pattern: ByteArray): Int {
        var i = length - pattern.size
        if (i < 0) return -1
        val first = pattern[0]
        outer@ while (i >= 0) {
            if (haystack[i] != first) {
                i--
                continue
            }
            for (k in 1 until pattern.size) {
                if (haystack[i + k] != pattern[k]) {
                    i--
                    continue@outer
                }
            }
            return i
        }
        return -1
    }
}
