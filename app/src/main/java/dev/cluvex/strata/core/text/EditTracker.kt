package dev.cluvex.strata.core.text

import java.nio.charset.Charset

data class TextFormat(val charset: Charset, val bom: Boolean, val lineEnding: LineEnding)

class EditTracker {

    var disk: TextFormat = TextFormat(Charsets.UTF_8, false, LineEnding.LF)
        private set
    var page: Int = 0
        private set

    private var clean: String = ""
    private var differs = false
    private val edits = HashMap<Int, String>()

    val editedPages: Set<Int> get() = if (differs) edits.keys + page else edits.keys.toSet()

    fun reset(text: String, format: TextFormat, page: Int = 0) {
        clean = text
        disk = format
        this.page = page
        differs = false
        edits.clear()
    }

    fun changed(length: Int, current: () -> CharSequence) {
        differs = length != clean.length || !clean.contentEquals(current())
    }

    fun leave(current: String) {
        if (differs) edits[page] = current else edits.remove(page)
        differs = false
    }

    fun enter(target: Int, diskText: String): String? {
        page = target
        clean = diskText
        val edited = edits.remove(target)
        differs = edited != null
        return edited
    }

    fun edits(current: String): Map<Int, String> = if (differs) edits + (page to current) else HashMap(edits)

    fun dirty(format: TextFormat): Boolean = differs || edits.isNotEmpty() || format != disk
}
