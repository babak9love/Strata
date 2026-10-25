package dev.cluvex.strata.core.undo

enum class UndoKind { RENAME, MOVE, COPY }

/** An item an overwrite put into the recycle bin: where it was and the bin id that holds it. */
data class UndoReplaced(val path: String, val trashId: String)

/** What a copy looked like right after it was made, to tell later whether anything in it was touched. */
data class UndoFingerprint(val entries: Int, val bytes: Long, val newest: Long)

/**
 * One rename, move or copy that can be taken back.
 *
 * [pairs] holds old and new path for renames and moves; [created] holds the copies a copy made.
 * [replaced] is whatever an overwrite pushed into the recycle bin, to be brought back by the undo.
 */
data class UndoEntry(
    val id: Long,
    val kind: UndoKind,
    val createdAt: Long,
    val title: String,
    val pairs: List<Pair<String, String>> = emptyList(),
    val created: List<String> = emptyList(),
    val replaced: List<UndoReplaced> = emptyList(),
    /** For copies: how each created item looked when it was made. */
    val fingerprints: Map<String, UndoFingerprint> = emptyMap(),
)

/** The list of undoable operations: the newest first, at most [MAX_ENTRIES], each gone after [MAX_AGE_MS]. */
object UndoHistory {
    const val MAX_ENTRIES = 10
    const val MAX_AGE_MS = 3 * 60 * 1000L

    fun active(list: List<UndoEntry>, now: Long): List<UndoEntry> =
        list.filter { now - it.createdAt < MAX_AGE_MS }.take(MAX_ENTRIES)

    fun add(list: List<UndoEntry>, entry: UndoEntry, now: Long): List<UndoEntry> =
        active(listOf(entry) + list, now)

    fun remove(list: List<UndoEntry>, id: Long): List<UndoEntry> = list.filter { it.id != id }
}
