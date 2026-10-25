package dev.cluvex.strata.feature.editor

import android.graphics.Typeface
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.cluvex.strata.R
import dev.cluvex.strata.core.color.ColorCodec
import dev.cluvex.strata.core.text.LineEnding
import dev.cluvex.strata.core.data.EditorPrefs
import dev.cluvex.strata.core.i18n.tr
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.event.PublishSearchResultEvent
import io.github.rosemoe.sora.event.SelectionChangeEvent
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.text.LineSeparator
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import io.github.rosemoe.sora.widget.subscribeAlways

data class CursorColor(val line: Int, val literal: ColorCodec.Literal)

data class EditorSelection(val startLine: Int, val startColumn: Int, val endLine: Int, val endColumn: Int, val text: String)

data class SearchQuery(
    val text: String,
    val regex: Boolean = false,
    val matchCase: Boolean = false,
    val wholeWord: Boolean = false,
)

@Stable
class EditorController {
    var editor: CodeEditor? by mutableStateOf(null)
        private set

    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set
    var line by mutableIntStateOf(1)
        private set
    var column by mutableIntStateOf(1)
        private set
    var lineCount by mutableIntStateOf(1)
        private set
    var matchCount by mutableIntStateOf(0)
        private set
    var matchIndex by mutableIntStateOf(-1)
        private set

    var onEdited: () -> Unit = {}

    fun attach(view: CodeEditor) {
        editor = view
        view.subscribeAlways<ContentChangeEvent> { e ->
            refreshUndo()
            lineCount = view.text.lineCount
            if (e.action != ContentChangeEvent.ACTION_SET_NEW_TEXT) onEdited()
        }
        view.subscribeAlways<SelectionChangeEvent> { e ->
            line = e.left.line + 1
            column = e.left.column + 1
            refreshSearch(view.searcher)
        }
        view.subscribeAlways<PublishSearchResultEvent> { refreshSearch(view.searcher) }
    }

    fun detach() {
        editor = null
    }

    fun setText(text: String) {
        val e = editor ?: return
        e.setText(text)
        lineCount = e.text.lineCount
        refreshUndo()
    }

    fun text(): String = editor?.text?.toString().orEmpty()

    fun length(): Int = editor?.text?.length ?: 0

    fun undo() {
        editor?.undo()
        refreshUndo()
    }

    fun redo() {
        editor?.redo()
        refreshUndo()
    }

    fun insertSymbol(symbol: String) {
        val e = editor ?: return
        if (!e.isEditable) return
        if (symbol == "\t") e.indentOrCommitTab() else e.commitText(symbol)
    }

    fun colorUnderCursor(): CursorColor? {
        val e = editor ?: return null
        val cursor = e.cursor
        val line = cursor.leftLine
        val literal = ColorCodec.literalAt(e.text.getLineString(line), cursor.leftColumn) ?: return null
        return CursorColor(line, literal)
    }

    fun replaceColor(at: CursorColor, argb: Int) {
        val e = editor ?: return
        if (!e.isEditable) return
        val text = at.literal.render(argb)
        e.text.replace(at.line, at.literal.start, at.line, at.literal.end, text)
        e.setSelection(at.line, at.literal.start + text.length)
    }

    fun insertText(text: String) {
        val e = editor ?: return
        if (e.isEditable) e.commitText(text)
    }

    fun selection(): EditorSelection? {
        val e = editor ?: return null
        val c = e.cursor
        if (!c.isSelected) return null
        val text = e.text.subContent(c.leftLine, c.leftColumn, c.rightLine, c.rightColumn).toString()
        return EditorSelection(c.leftLine, c.leftColumn, c.rightLine, c.rightColumn, text)
    }

    fun replaceSelection(at: EditorSelection, replacement: String): Boolean {
        val e = editor ?: return false
        if (!e.isEditable) return false
        val content = e.text
        val now = runCatching { content.subContent(at.startLine, at.startColumn, at.endLine, at.endColumn).toString() }.getOrNull()
        if (now != at.text) return false
        val start = content.getCharIndex(at.startLine, at.startColumn)
        content.replace(at.startLine, at.startColumn, at.endLine, at.endColumn, replacement)
        val end = content.indexer.getCharPosition(start + replacement.length)
        e.setSelectionRegion(at.startLine, at.startColumn, end.line, end.column)
        return true
    }

    fun select(line: Int, start: Int, end: Int) {
        val e = editor ?: return
        val l = (line - 1).coerceIn(0, e.text.lineCount - 1)
        val count = e.text.getColumnCount(l)
        val a = start.coerceIn(0, count)
        val b = end.coerceIn(a, count)
        if (b > a) e.setSelectionRegion(l, a, l, b, true) else e.setSelection(l, a, true)
        e.ensurePositionVisible(l, a)
    }

    fun lineText(line: Int): String? {
        val e = editor ?: return null
        val l = line - 1
        return if (l in 0 until e.text.lineCount) e.text.getLineString(l) else null
    }

    fun gotoLine(target: Int) {
        val e = editor ?: return
        val l = (target - 1).coerceIn(0, e.text.lineCount - 1)
        e.setSelection(l, 0, true)
        e.requestFocus()
    }

    fun search(query: SearchQuery): String? {
        val e = editor ?: return null
        if (query.text.isEmpty()) {
            stopSearch()
            return null
        }
        val type = when {
            query.regex -> EditorSearcher.SearchOptions.TYPE_REGULAR_EXPRESSION
            query.wholeWord -> EditorSearcher.SearchOptions.TYPE_WHOLE_WORD
            else -> EditorSearcher.SearchOptions.TYPE_NORMAL
        }
        if (query.regex) {
            runCatching { Regex(query.text) }.exceptionOrNull()?.let { return it.message ?: tr(R.string.editor_invalid_regex) }
        }
        return try {
            e.searcher.search(query.text, EditorSearcher.SearchOptions(type, !query.matchCase))
            null
        } catch (ex: Exception) {
            ex.message ?: tr(R.string.editor_invalid_search)
        }
    }

    fun stopSearch() {
        editor?.searcher?.stopSearch()
        matchCount = 0
        matchIndex = -1
    }

    fun next() = editor?.searcher?.takeIf { it.hasQuery() }?.gotoNext()
    fun previous() = editor?.searcher?.takeIf { it.hasQuery() }?.gotoPrevious()

    fun replace(replacement: String) {
        val s = editor?.searcher?.takeIf { it.hasQuery() } ?: return
        if (s.isMatchedPositionSelected) s.replaceCurrentMatch(replacement) else s.gotoNext()
    }

    fun replaceAll(replacement: String, onDone: () -> Unit = {}) {
        val s = editor?.searcher?.takeIf { it.hasQuery() } ?: return
        s.replaceAll(replacement) { onDone() }
    }

    fun setLanguage(language: Language) {
        editor?.setEditorLanguage(language)
    }

    fun setColorScheme(scheme: EditorColorScheme) {
        editor?.colorScheme = scheme
    }

    fun setWordWrap(on: Boolean) = editor?.setWordwrap(on)
    fun setLineNumbers(on: Boolean) = editor?.setLineNumberEnabled(on)
    fun setEditable(on: Boolean) {
        editor?.isEditable = on
    }

    fun setTextSize(sp: Float) = editor?.setTextSize(sp)

    fun applyOptions(p: EditorPrefs) {
        val e = editor ?: return
        e.setTabWidth(p.tabWidth)
        e.props.autoIndent = p.autoIndent
        e.props.symbolPairAutoCompletion = p.autoClosePairs
        e.props.stickyScroll = p.stickyScroll
        e.props.highlightMatchingDelimiters = p.highlightBrackets
        e.setHighlightCurrentLine(p.highlightLine)
        e.setHighlightBracketPair(p.highlightBrackets)
        e.setScalable(p.pinchZoom)
        e.setBlockLineEnabled(p.indentGuides)
        e.setPinLineNumber(p.pinLineNumbers)
        e.setCursorAnimationEnabled(p.cursorAnimation)
        e.keepScreenOn = p.keepScreenOn
        e.invalidate()
    }

    fun setShowWhitespace(on: Boolean) {
        editor?.nonPrintablePaintingFlags = if (on) {
            CodeEditor.FLAG_DRAW_WHITESPACE_LEADING or CodeEditor.FLAG_DRAW_WHITESPACE_INNER or
                CodeEditor.FLAG_DRAW_WHITESPACE_TRAILING or CodeEditor.FLAG_DRAW_LINE_SEPARATOR
        } else {
            0
        }
    }

    fun setLineEnding(ending: LineEnding) {
        editor?.lineSeparator = when (ending) {
            LineEnding.LF -> LineSeparator.LF
            LineEnding.CRLF -> LineSeparator.CRLF
            LineEnding.CR -> LineSeparator.CR
        }
    }

    fun setTypeface(typeface: Typeface) {
        editor?.typefaceText = typeface
        editor?.typefaceLineNumber = typeface
    }

    private fun refreshUndo() {
        val e = editor ?: return
        canUndo = e.canUndo()
        canRedo = e.canRedo()
    }

    private fun refreshSearch(searcher: EditorSearcher) {
        if (!searcher.hasQuery()) {
            matchCount = 0
            matchIndex = -1
            return
        }
        matchCount = runCatching { searcher.matchedPositionCount }.getOrDefault(0)
        matchIndex = runCatching { searcher.currentMatchedPositionIndex }.getOrDefault(-1)
    }
}
