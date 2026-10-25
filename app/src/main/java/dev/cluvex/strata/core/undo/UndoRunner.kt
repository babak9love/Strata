package dev.cluvex.strata.core.undo

import dev.cluvex.strata.core.fs.Conflict
import dev.cluvex.strata.core.fs.FsUtil
import dev.cluvex.strata.core.fs.OpProgress
import dev.cluvex.strata.core.model.FileItem
import dev.cluvex.strata.core.trash.RecycleBin
import kotlinx.coroutines.flow.lastOrNull
import java.io.File
import java.util.UUID

/** Why an entry cannot be undone right now. */
enum class UndoBlocker {
    /** Nothing it made or moved is there any more. */
    GONE,

    /** The place the items would go back to is taken by something else. */
    OCCUPIED,
}

class UndoOutcome(
    val restored: Int,
    val failed: List<String>,
    /** Replaced items that were no longer in the recycle bin. */
    val missingReplaced: Int,
    /** Old and new path of every item that went back, so things tied to a path (protected folders) can follow. */
    val moves: List<Pair<String, String>>,
    val touchedDirs: Set<String>,
    /** Copies that had been changed since they were made, so they went to the recycle bin instead of being deleted. */
    val keptInBin: Int = 0,
)

object UndoRunner {

    fun blocker(e: UndoEntry): UndoBlocker? = when (e.kind) {
        UndoKind.COPY -> if (e.created.none { FsUtil.exists(File(it)) }) UndoBlocker.GONE else null
        UndoKind.RENAME, UndoKind.MOVE -> {
            val present = e.pairs.filter { FsUtil.exists(File(it.second)) }
            val vacated = present.map { it.second }.toSet()
            when {
                present.isEmpty() -> UndoBlocker.GONE
                present.any { (old, cur) ->
                    old !in vacated && !old.equals(cur, ignoreCase = true) && FsUtil.exists(File(old))
                } -> UndoBlocker.OCCUPIED
                else -> null
            }
        }
    }

    /** Entry count, total size and newest modification time of a file or folder tree (links are not followed). */
    fun fingerprint(root: File): UndoFingerprint {
        var entries = 0
        var bytes = 0L
        var newest = 0L
        fun visit(f: File) {
            entries++
            newest = maxOf(newest, f.lastModified())
            if (f.isDirectory && !isLink(f)) {
                f.listFiles()?.forEach(::visit)
            } else {
                bytes += f.length()
            }
        }
        visit(root)
        return UndoFingerprint(entries, bytes, newest)
    }

    private fun isLink(f: File): Boolean {
        val parent = f.absoluteFile.parentFile ?: return false
        return runCatching { f.canonicalFile != File(parent.canonicalFile, f.name) }.getOrDefault(false)
    }

    suspend fun run(e: UndoEntry, bin: RecycleBin): UndoOutcome {
        val failed = mutableListOf<String>()
        val moves = mutableListOf<Pair<String, String>>()
        val dirs = linkedSetOf<String>()
        var restored = 0
        var keptInBin = 0

        when (e.kind) {
            UndoKind.COPY -> {
                // A copy nobody has touched is simply deleted; one that was changed since goes to the recycle bin.
                val toBin = mutableListOf<File>()
                for (f in e.created.map(::File).filter { FsUtil.exists(it) }) {
                    f.parent?.let { dirs.add(it) }
                    val untouched = e.fingerprints[f.path]?.let { it == fingerprint(f) } == true
                    if (untouched) {
                        if (FsUtil.deleteRecursively(f)) restored++ else failed += f.name
                    } else {
                        toBin += f
                    }
                }
                if (toBin.isNotEmpty()) {
                    val fin = bin.moveToTrash(toBin.map { FileItem.of(it) }).lastOrNull() as? OpProgress.Finished
                    val n = fin?.okCount ?: 0
                    restored += n
                    keptInBin += n
                    failed += fin?.failed.orEmpty()
                }
            }
            UndoKind.RENAME, UndoKind.MOVE -> {
                val present = e.pairs.filter { FsUtil.exists(File(it.second)) }
                val back = putBack(present, failed)
                restored = back.size
                moves += back.map { (old, cur) -> Pair(cur, old) }
                back.forEach { (old, cur) ->
                    File(old).parent?.let { dirs.add(it) }
                    File(cur).parent?.let { dirs.add(it) }
                }
            }
        }

        var missing = 0
        if (e.replaced.isNotEmpty()) {
            val inBin = bin.list().associateBy { it.info.id }
            for (r in e.replaced) {
                val entry = inBin[r.trashId]
                if (entry == null) {
                    missing++
                    continue
                }
                val fin = bin.restore(listOf(entry), Conflict.KEEP_BOTH).lastOrNull() as? OpProgress.Finished
                if (fin == null || fin.failed.isNotEmpty()) failed += File(r.path).name
                File(r.path).parent?.let { dirs.add(it) }
            }
        }
        return UndoOutcome(restored, failed, missing, moves, dirs, keptInBin)
    }

    /**
     * Puts each item back at its old path. When one item's old path is another's current one (a batch rename that swapped
     * names), everything goes through temporary names first so no item ever lands on top of another.
     */
    private fun putBack(pairs: List<Pair<String, String>>, failed: MutableList<String>): List<Pair<String, String>> {
        val done = mutableListOf<Pair<String, String>>()
        val currents = pairs.map { it.second }.toSet()
        val chained = pairs.any { it.first in currents }
        if (!chained) {
            for ((from, to) in pairs) {
                if (move(File(to), File(from))) done += from to to else failed += File(to).name
            }
            return done
        }
        val parked = mutableListOf<Triple<String, String, File>>()
        for ((from, to) in pairs) {
            val src = File(to)
            val temp = File(src.parentFile, ".strata-undo-" + UUID.randomUUID().toString().take(8))
            if (src.renameTo(temp)) {
                parked += Triple(from, to, temp)
            } else {
                failed += src.name
            }
        }
        for ((from, to, temp) in parked) {
            if (move(temp, File(from))) {
                done += from to to
            } else {
                temp.renameTo(File(to))
                failed += File(to).name
            }
        }
        return done
    }

    private fun move(src: File, dst: File): Boolean {
        dst.parentFile?.mkdirs()
        return src.renameTo(dst) || FsUtil.move(src, dst)
    }
}
