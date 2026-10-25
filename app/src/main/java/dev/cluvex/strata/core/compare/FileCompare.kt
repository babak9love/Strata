package dev.cluvex.strata.core.compare

import dev.cluvex.strata.core.archive.ArchiveReader
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile

enum class DiffOp { SAME, ADDED, REMOVED }
data class DiffLine(val op: DiffOp, val text: String, val leftNo: Int?, val rightNo: Int?)

data class DiffStats(val added: Int, val removed: Int, val same: Int) {
    val identical: Boolean get() = added == 0 && removed == 0
}

enum class EntryDiff { ADDED, REMOVED, CHANGED, SAME }
data class ArchiveEntryDiff(val path: String, val diff: EntryDiff, val leftSize: Long, val rightSize: Long)

object FileCompare {
    fun sameSize(a: File, b: File): Boolean = a.length() == b.length()

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun bytesEqual(a: File, b: File): Boolean {
        if (a.length() != b.length()) return false
        a.inputStream().buffered().use { ia ->
            b.inputStream().buffered().use { ib ->
                val ba = ByteArray(1 shl 16)
                val bb = ByteArray(1 shl 16)
                while (true) {
                    val na = fill(ia, ba)
                    val nb = fill(ib, bb)
                    if (na != nb) return false
                    if (na == 0) return true
                    for (i in 0 until na) if (ba[i] != bb[i]) return false
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        return true
    }

    private fun fill(input: java.io.InputStream, buf: ByteArray): Int {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) break
            off += n
        }
        return off
    }

    fun lines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val unix = text.replace("\r\n", "\n")
        return (if (unix.endsWith("\n")) unix.dropLast(1) else unix).split("\n")
    }

    fun textDiff(left: String, right: String): List<DiffLine> {
        val a = lines(left)
        val b = lines(right)
        val lcs = lcsTable(a, b)
        val out = ArrayList<DiffLine>()
        var i = 0
        var j = 0
        while (i < a.size && j < b.size) {
            if (a[i] == b[j]) {
                out += DiffLine(DiffOp.SAME, a[i], i + 1, j + 1)
                i++; j++
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                out += DiffLine(DiffOp.REMOVED, a[i], i + 1, null)
                i++
            } else {
                out += DiffLine(DiffOp.ADDED, b[j], null, j + 1)
                j++
            }
        }
        while (i < a.size) out += DiffLine(DiffOp.REMOVED, a[i], (i++) + 1, null)
        while (j < b.size) out += DiffLine(DiffOp.ADDED, b[j], null, (j++) + 1)
        return out
    }

    fun stats(diff: List<DiffLine>) = DiffStats(
        added = diff.count { it.op == DiffOp.ADDED },
        removed = diff.count { it.op == DiffOp.REMOVED },
        same = diff.count { it.op == DiffOp.SAME },
    )

    private fun lcsTable(a: List<String>, b: List<String>): Array<IntArray> {
        val dp = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) {
            for (j in b.indices.reversed()) {
                dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
        return dp
    }

    fun archiveDiff(left: File, right: File): List<ArchiveEntryDiff> {
        val a = entryMap(left)
        val b = entryMap(right)
        val names = (a.keys + b.keys).toSortedSet()
        return names.map { name ->
            val l = a[name]
            val r = b[name]
            val diff = when {
                l == null -> EntryDiff.ADDED
                r == null -> EntryDiff.REMOVED
                l.size == r.size && l.crc == r.crc -> EntryDiff.SAME
                else -> EntryDiff.CHANGED
            }
            ArchiveEntryDiff(name, diff, l?.size ?: -1, r?.size ?: -1)
        }
    }

    fun isArchivePair(a: String, b: String): Boolean = ArchiveReader.isZip(a) && ArchiveReader.isZip(b)

    private data class Meta(val size: Long, val crc: Long)

    private fun entryMap(file: File): Map<String, Meta> = ZipFile(file).use { zip ->
        zip.entries().asSequence().filterNot { it.isDirectory }.associate { it.name to Meta(it.size, it.crc) }
    }
}
