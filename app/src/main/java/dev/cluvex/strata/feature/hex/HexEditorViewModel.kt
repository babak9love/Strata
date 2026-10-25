package dev.cluvex.strata.feature.hex

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.cluvex.strata.R
import dev.cluvex.strata.core.hex.ByteSources
import dev.cluvex.strata.core.hex.CopyFormat
import dev.cluvex.strata.core.hex.HexCodec
import dev.cluvex.strata.core.hex.HexDataType
import dev.cluvex.strata.core.hex.HexDocument
import dev.cluvex.strata.core.hex.PasteFormat
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.i18n.trPlural
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class HexUiState(
    val path: String = "",
    val name: String = "",
    val size: Long = 0,
    val loading: Boolean = true,
    val error: String? = null,
    val readOnly: Boolean = false,
    val bytesPerRow: Int = 16,
    val windowStart: Long = 0,
    val window: ByteArray = ByteArray(0),
    val modified: Set<Long> = emptySet(),
    val cursor: Long = 0,
    val anchor: Long = -1,
    val selStart: Long = -1,
    val selEnd: Long = -1,
    val pendingNibble: Int = -1,
    val dirty: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val bigEndian: Boolean = false,
    val showInspector: Boolean = true,
    val inspector: List<Pair<String, String>> = emptyList(),
    val lastFound: Long = -1,
    val message: String? = null,
    val scrollSeq: Int = 0,
    val scrollTo: Long = 0,
) {
    val hasSelection: Boolean get() = selStart in 0..selEnd
    val selectionLength: Long get() = if (hasSelection) selEnd - selStart + 1 else 0
    val rowCount: Int
        get() = if (size <= 0L) 0 else ((size + bytesPerRow - 1) / bytesPerRow).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    fun byteAt(offset: Long): Int? {
        val i = offset - windowStart
        return if (i >= 0 && i < window.size) window[i.toInt()].toInt() and 0xFF else null
    }

    fun isSelected(offset: Long): Boolean = hasSelection && offset in selStart..selEnd
}

class HexEditorViewModel : ViewModel() {

    private val _state = MutableStateFlow(HexUiState())
    val state: StateFlow<HexUiState> = _state.asStateFlow()

    private var doc: HexDocument? = null
    private val mutex = Mutex()
    private var firstVisibleRow = 0
    private var visibleRowCount = 48
    private var loadSeq = 0

    private companion object {
        const val WINDOW_MARGIN_ROWS = 96
        const val MAX_COPY_BYTES = 4L * 1024 * 1024
    }

    fun open(path: String, rootEnabled: Boolean = false) {
        if (_state.value.path == path && doc != null) return
        closeDoc()
        _state.value = HexUiState(path = path, name = File(path).name, bytesPerRow = _state.value.bytesPerRow)
        viewModelScope.launch {
            val opened = withContext(Dispatchers.IO) { runCatching { HexDocument(ByteSources.openAny(path, rootEnabled)) } }
            opened.onSuccess { d ->
                doc = d
                _state.update { it.copy(loading = false, size = d.size, readOnly = !d.isWritable) }
                reloadWindow()
                refreshInspector()
            }.onFailure { e ->
                _state.update { it.copy(loading = false, error = e.message ?: tr(R.string.hex_cannot_open)) }
            }
        }
    }

    fun discard() {
        closeDoc()
        _state.value = HexUiState(bytesPerRow = _state.value.bytesPerRow)
    }

    private fun closeDoc() {
        runCatching { doc?.close() }
        doc = null
    }

    override fun onCleared() = closeDoc()

    fun setBytesPerRow(bpr: Int) {
        if (_state.value.bytesPerRow == bpr) return
        _state.update { it.copy(bytesPerRow = bpr) }
        reloadWindow()
    }

    fun onViewport(firstRow: Int, rowCount: Int) {
        firstVisibleRow = firstRow
        visibleRowCount = maxOf(rowCount, 1)
        val s = _state.value
        val bpr = s.bytesPerRow
        val needStart = firstRow.toLong() * bpr
        val needEnd = minOf((firstRow + visibleRowCount).toLong() * bpr, s.size)
        val covered = needStart >= s.windowStart && needEnd <= s.windowStart + s.window.size
        if (!covered) reloadWindow()
    }

    private fun reloadWindow() {
        val d = doc ?: return
        val bpr = _state.value.bytesPerRow
        val startRow = maxOf(0, firstVisibleRow - WINDOW_MARGIN_ROWS)
        val endRow = firstVisibleRow + visibleRowCount + WINDOW_MARGIN_ROWS
        val start = startRow.toLong() * bpr
        val length = minOf((endRow - startRow).toLong() * bpr, 1L shl 20).toInt()
        val seq = ++loadSeq
        viewModelScope.launch {
            val (bytes, modified) = mutex.withLock {
                withContext(Dispatchers.IO) {
                    val b = d.read(start, length)
                    b to d.modifiedOffsets(start, start + b.size)
                }
            }
            if (seq == loadSeq) _state.update { it.copy(windowStart = start, window = bytes, modified = modified) }
        }
    }

    fun tap(offset: Long) {
        val s = _state.value
        if (s.size <= 0 || offset !in 0 until s.size) return
        if (s.anchor >= 0) {
            _state.update {
                it.copy(cursor = offset, selStart = minOf(it.anchor, offset), selEnd = maxOf(it.anchor, offset), pendingNibble = -1)
            }
        } else {
            _state.update { it.copy(cursor = offset, selStart = -1, selEnd = -1, pendingNibble = -1) }
        }
        refreshInspector()
    }

    fun longPress(offset: Long) {
        val s = _state.value
        if (s.size <= 0 || offset !in 0 until s.size) return
        _state.update { it.copy(cursor = offset, anchor = offset, selStart = offset, selEnd = offset, pendingNibble = -1) }
        refreshInspector()
    }

    fun clearSelection() = _state.update { it.copy(anchor = -1, selStart = -1, selEnd = -1) }

    fun goto(offset: Long) {
        val s = _state.value
        if (s.size <= 0) return
        val o = offset.coerceIn(0, s.size - 1)
        _state.update { it.copy(cursor = o, anchor = -1, selStart = -1, selEnd = -1, pendingNibble = -1, scrollTo = o, scrollSeq = it.scrollSeq + 1) }
        refreshInspector()
    }

    fun selectRange(start: Long, length: Long) {
        val s = _state.value
        if (s.size <= 0 || length <= 0 || start !in 0 until s.size) {
            message(tr(R.string.hex_invalid_range))
            return
        }
        val end = minOf(start + length - 1, s.size - 1)
        _state.update { it.copy(cursor = start, anchor = start, selStart = start, selEnd = end, scrollTo = start, scrollSeq = it.scrollSeq + 1) }
        refreshInspector()
    }

    fun typeNibble(value: Int) {
        val s = _state.value
        if (s.readOnly) {
            message(tr(R.string.hex_file_read_only))
            return
        }
        if (s.size <= 0) return
        if (s.pendingNibble < 0) {
            _state.update { it.copy(pendingNibble = value and 0xF) }
            return
        }
        val byte = ((s.pendingNibble shl 4) or (value and 0xF)).toByte()
        val at = s.cursor
        _state.update { it.copy(pendingNibble = -1) }
        mutate { d ->
            d.write(at, byteArrayOf(byte))
            val next = if (at + 1 < d.size) at + 1 else at
            _state.update { it.copy(cursor = next) }
        }
    }

    fun cancelPendingNibble() = _state.update { it.copy(pendingNibble = -1) }

    fun undo() = mutate { d -> d.undo()?.let { o -> _state.update { it.copy(cursor = o, scrollTo = o, scrollSeq = it.scrollSeq + 1) } } }

    fun redo() = mutate { d -> d.redo()?.let { o -> _state.update { it.copy(cursor = o, scrollTo = o, scrollSeq = it.scrollSeq + 1) } } }

    fun save(onSaved: (() -> Unit)? = null) {
        val d = doc ?: return
        viewModelScope.launch {
            val result = mutex.withLock { withContext(Dispatchers.IO) { runCatching { d.save() } } }
            result.onSuccess { n ->
                message(if (n == 0) tr(R.string.hex_no_changes) else trPlural(R.plurals.hex_saved_bytes, n, n))
                afterEdit()
                onSaved?.invoke()
            }.onFailure { e -> message(tr(R.string.hex_save_failed, e.message)) }
        }
    }

    fun fillSelection(value: Int) {
        val s = _state.value
        if (s.readOnly) {
            message(tr(R.string.hex_file_read_only))
            return
        }
        val start = if (s.hasSelection) s.selStart else s.cursor
        val len = if (s.hasSelection) s.selectionLength else 1
        if (len > MAX_COPY_BYTES) {
            message(tr(R.string.hex_selection_too_large))
            return
        }
        mutate { d -> d.write(start, ByteArray(len.toInt()) { value.toByte() }) }
    }

    fun find(query: String, type: HexDataType, bigEndian: Boolean, forward: Boolean) {
        val pattern = encodeOrReport(query, type, bigEndian) ?: return
        val d = doc ?: return
        val s = _state.value
        val from = if (forward) {
            if (s.lastFound == s.cursor) s.cursor + 1 else s.cursor
        } else {
            s.cursor - 1
        }
        viewModelScope.launch {
            val result = mutex.withLock {
                withContext(Dispatchers.IO) {
                    runCatching {
                        var wrapped = false
                        var hit = if (from < 0) -1 else d.find(pattern, from, forward)
                        if (hit < 0) {
                            hit = if (forward) d.find(pattern, 0, true) else d.find(pattern, d.size - 1, false)
                            wrapped = hit >= 0
                        }
                        hit to wrapped
                    }
                }
            }
            result.onSuccess { (hit, wrapped) ->
                if (hit < 0) {
                    message(tr(R.string.hex_not_found))
                } else {
                    highlightMatch(hit, pattern.size)
                    if (wrapped) message(tr(if (forward) R.string.hex_wrapped_to_start else R.string.hex_wrapped_to_end))
                }
            }.onFailure { e -> message(tr(R.string.hex_search_failed, e.message)) }
        }
    }

    fun replace(query: String, replacement: String, type: HexDataType, bigEndian: Boolean, all: Boolean) {
        val s = _state.value
        if (s.readOnly) {
            message(tr(R.string.hex_file_read_only))
            return
        }
        val pattern = encodeOrReport(query, type, bigEndian) ?: return
        val rep = encodeOrReport(replacement, type, bigEndian) ?: return
        if (pattern.size != rep.size) {
            message(trPlural(R.plurals.hex_replacement_size, pattern.size, pattern.size, rep.size))
            return
        }
        if (all) {
            mutate { d ->
                val n = d.replaceAll(pattern, rep)
                message(if (n == 0) tr(R.string.hex_not_found) else trPlural(R.plurals.hex_replaced, n, n))
            }
        } else {
            mutate { d ->
                val current = d.read(s.cursor, pattern.size)
                val at = if (current.contentEquals(pattern)) s.cursor else d.find(pattern, s.cursor, true)
                if (at < 0) {
                    message(tr(R.string.hex_not_found))
                } else {
                    d.write(at, rep)
                    val next = d.find(pattern, at + rep.size, true)
                    if (next >= 0) highlightMatch(next, pattern.size) else highlightMatch(at, rep.size)
                }
            }
        }
    }

    private fun highlightMatch(at: Long, length: Int) {
        _state.update {
            it.copy(
                cursor = at,
                anchor = at,
                selStart = at,
                selEnd = at + length - 1,
                lastFound = at,
                pendingNibble = -1,
                scrollTo = at,
                scrollSeq = it.scrollSeq + 1,
            )
        }
        reloadWindowIfNeeded()
        refreshInspector()
    }

    private fun encodeOrReport(text: String, type: HexDataType, bigEndian: Boolean): ByteArray? {
        val bytes = try {
            HexCodec.encode(text, type, bigEndian)
        } catch (e: IllegalArgumentException) {
            message(e.message ?: tr(R.string.hex_invalid_input))
            return null
        }
        if (bytes.isEmpty()) {
            message(tr(R.string.hex_nothing_to_search))
            return null
        }
        return bytes
    }

    fun copyAs(format: CopyFormat, onResult: (String) -> Unit) {
        val d = doc ?: return
        val s = _state.value
        val start = if (s.hasSelection) s.selStart else s.cursor
        val len = if (s.hasSelection) s.selectionLength else 1
        if (len > MAX_COPY_BYTES) {
            message(tr(R.string.hex_range_too_large_to_copy))
            return
        }
        viewModelScope.launch {
            val text = mutex.withLock { withContext(Dispatchers.IO) { HexCodec.copyAs(d.read(start, len.toInt()), format) } }
            onResult(text)
            message(tr(R.string.hex_copied_as, tr(format.label)))
        }
    }

    fun pasteFrom(text: String, format: PasteFormat) {
        val s = _state.value
        if (s.readOnly) {
            message(tr(R.string.hex_file_read_only))
            return
        }
        val bytes = try {
            HexCodec.pasteFrom(text, format)
        } catch (e: IllegalArgumentException) {
            message(e.message ?: tr(R.string.hex_invalid_clipboard))
            return
        }
        if (bytes.isEmpty()) {
            message(tr(R.string.hex_clipboard_empty))
            return
        }
        val at = if (s.hasSelection) s.selStart else s.cursor
        mutate { d ->
            val fits = minOf(bytes.size.toLong(), d.size - at).toInt()
            d.write(at, bytes.copyOf(fits))
            message(if (fits < bytes.size) trPlural(R.plurals.hex_pasted_partial, bytes.size, fits, bytes.size) else trPlural(R.plurals.hex_pasted, fits, fits))
        }
    }

    fun toggleEndian() {
        _state.update { it.copy(bigEndian = !it.bigEndian) }
        refreshInspector()
    }

    fun toggleInspector() = _state.update { it.copy(showInspector = !it.showInspector) }

    private fun refreshInspector() {
        val d = doc ?: return
        val s = _state.value
        viewModelScope.launch {
            val bytes = mutex.withLock { withContext(Dispatchers.IO) { d.read(s.cursor, 8) } }
            _state.update { it.copy(inspector = HexCodec.inspect(bytes, it.bigEndian)) }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private fun message(text: String) = _state.update { it.copy(message = text) }

    private fun mutate(block: (HexDocument) -> Unit) {
        val d = doc ?: return
        viewModelScope.launch {
            mutex.withLock { withContext(Dispatchers.IO) { runCatching { block(d) }.onFailure { e -> message(e.message ?: tr(R.string.hex_edit_failed)) } } }
            afterEdit()
        }
    }

    private fun afterEdit() {
        val d = doc ?: return
        _state.update { it.copy(dirty = d.isDirty, canUndo = d.canUndo, canRedo = d.canRedo) }
        reloadWindow()
        refreshInspector()
    }

    private fun reloadWindowIfNeeded() {
        val s = _state.value
        val needEnd = minOf(s.scrollTo + s.bytesPerRow, s.size)
        if (s.scrollTo < s.windowStart || needEnd > s.windowStart + s.window.size) {
            firstVisibleRow = (s.scrollTo / s.bytesPerRow).toInt()
            reloadWindow()
        }
    }
}
