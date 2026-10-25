package dev.cluvex.strata.feature.ninepatch

import android.app.Application
import android.graphics.BitmapFactory
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.cluvex.strata.R
import dev.cluvex.strata.core.fs.FileAccess
import dev.cluvex.strata.core.fs.FsUtil
import dev.cluvex.strata.core.fs.LocalFileAccess
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.image.PngChunks
import dev.cluvex.strata.core.ninepatch.Insets
import dev.cluvex.strata.core.ninepatch.NinePatch
import dev.cluvex.strata.core.ninepatch.NinePatchException
import dev.cluvex.strata.core.root.RootManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class NinePatchForm(@StringRes val label: Int) {
    SOURCE(R.string.ninepatch_form_source),

    COMPILED(R.string.ninepatch_form_compiled),
}

data class NinePatchUiState(
    val path: String = "",
    val loading: Boolean = true,
    val patch: NinePatch? = null,
    val form: NinePatchForm = NinePatchForm.SOURCE,
    val dirty: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
    val raw: Raw? = null,
    val message: String? = null,
) {
    class Raw(val pixels: IntArray, val width: Int, val height: Int)
}

class NinePatchViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(NinePatchUiState())
    val state: StateFlow<NinePatchUiState> = _state.asStateFlow()
    private var access: FileAccess = LocalFileAccess
    private var saved: NinePatch? = null

    fun load(path: String, rootEnabled: Boolean) {
        val current = _state.value
        if (current.path == path && !current.loading && current.dirty) return
        _state.value = NinePatchUiState(path = path)
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val local = File(path)
                    val a = if (local.canRead() && (local.canWrite() || !rootEnabled)) LocalFileAccess else RootManager.access() ?: LocalFileAccess
                    access = a
                    val bytes = a.openInput(a.file(path)).use { it.readBytes() }
                    read(bytes)
                }
            }
            saved = result.getOrNull()?.patch
            _state.value = result.fold(
                onSuccess = { it.copy(path = path) },
                onFailure = { e -> NinePatchUiState(path = path, loading = false, error = e.message ?: e.javaClass.simpleName) },
            )
        }
    }

    private fun read(bytes: ByteArray): NinePatchUiState {
        val chunks = runCatching { PngChunks.read(bytes) }.getOrElse { throw NinePatchException(tr(R.string.ninepatch_not_png)) }
        val opts = BitmapFactory.Options().apply { inPremultiplied = false }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: throw NinePatchException(tr(R.string.ninepatch_cant_decode))
        val px = IntArray(bmp.width * bmp.height).also { bmp.getPixels(it, 0, bmp.width, 0, 0, bmp.width, bmp.height) }
        val npTc = chunks.firstOrNull { it.type == "npTc" }?.data
        if (npTc != null) {
            val patch = NinePatch.fromCompiled(px, bmp.width, bmp.height, npTc, chunks.firstOrNull { it.type == "npLb" }?.data)
            return NinePatchUiState(loading = false, patch = patch, form = NinePatchForm.COMPILED)
        }
        return try {
            NinePatchUiState(loading = false, patch = NinePatch.fromSource(px, bmp.width, bmp.height), form = NinePatchForm.SOURCE)
        } catch (e: NinePatchException) {
            val message = e.message.orEmpty()
            val error = if (e.x >= 0) tr(R.string.ninepatch_error_at_pixel, message, e.x, e.y) else message
            NinePatchUiState(loading = false, error = error, raw = NinePatchUiState.Raw(px, bmp.width, bmp.height))
        }
    }

    fun startOver(keepBorder: Boolean) {
        val raw = _state.value.raw ?: return
        val patch = if (keepBorder && raw.width > 2 && raw.height > 2) {
            val w = raw.width - 2
            val h = raw.height - 2
            val px = IntArray(w * h)
            for (y in 0 until h) System.arraycopy(raw.pixels, (y + 1) * raw.width + 1, px, y * w, w)
            NinePatch(w, h, px, IntArray(0), IntArray(0), Insets.ZERO, explicitPadding = false)
        } else {
            NinePatch(raw.width, raw.height, raw.pixels, IntArray(0), IntArray(0), Insets.ZERO, explicitPadding = false)
        }
        _state.update { it.copy(patch = patch, error = null, raw = null, dirty = true, form = NinePatchForm.SOURCE) }
    }

    fun setStretch(vertical: Boolean, from: Int, to: Int, on: Boolean) = edit { p ->
        val n = if (vertical) p.height else p.width
        val marks = BooleanArray(n).also { fill(it, if (vertical) p.yDivs else p.xDivs) }
        for (i in minOf(from, to).coerceIn(0, n - 1)..maxOf(from, to).coerceIn(0, n - 1)) marks[i] = on
        val divs = runs(marks)
        if (vertical) p.copy(yDivs = divs) else p.copy(xDivs = divs)
    }

    fun setContent(vertical: Boolean, from: Int, to: Int) = edit { p ->
        val lo = minOf(from, to)
        val hi = maxOf(from, to)
        val pad = if (vertical) p.padding.copy(top = lo, bottom = p.height - 1 - hi) else p.padding.copy(left = lo, right = p.width - 1 - hi)
        p.copy(padding = pad, explicitPadding = true)
    }

    fun clearStretch() = edit { it.copy(xDivs = IntArray(0), yDivs = IntArray(0)) }

    fun clearContent() = edit { p ->
        val x = if (p.xDivs.isNotEmpty()) p.xDivs.first() to p.width - p.xDivs.last() else 0 to 0
        val y = if (p.yDivs.isNotEmpty()) p.yDivs.first() to p.height - p.yDivs.last() else 0 to 0
        p.copy(padding = Insets(x.first, y.first, x.second, y.second), explicitPadding = false)
    }

    private fun edit(change: (NinePatch) -> NinePatch) {
        val p = _state.value.patch ?: return
        val next = runCatching { change(p) }.getOrNull() ?: return
        val fixed = if (!next.explicitPadding) {
            val x = if (next.xDivs.isNotEmpty()) next.xDivs.first() to next.width - next.xDivs.last() else 0 to 0
            val y = if (next.yDivs.isNotEmpty()) next.yDivs.first() to next.height - next.yDivs.last() else 0 to 0
            next.copy(padding = Insets(x.first, y.first, x.second, y.second))
        } else next
        _state.update { it.copy(patch = fixed, dirty = saved?.let { s -> !fixed.sameAs(s) } ?: true) }
    }

    fun save(asOtherForm: Boolean = false) {
        val s = _state.value
        val p = s.patch ?: return
        if (s.saving) return
        val form = if (asOtherForm) (if (s.form == NinePatchForm.SOURCE) NinePatchForm.COMPILED else NinePatchForm.SOURCE) else s.form
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val bytes = encode(p, form)
                    val target = if (asOtherForm) copyPath(s.path, form) else File(s.path)
                    val a = access
                    a.openOutput(a.file(target.path)).use { it.write(bytes) }
                    target
                }
            }
            _state.update {
                result.fold(
                    onSuccess = { f ->
                        if (!asOtherForm) saved = p
                        it.copy(saving = false, dirty = if (asOtherForm) it.dirty else it.patch?.sameAs(p) == false, message = tr(R.string.ninepatch_saved, f.name))
                    },
                    onFailure = { e -> it.copy(saving = false, message = tr(R.string.ninepatch_couldnt_save, e.message ?: e.javaClass.simpleName)) },
                )
            }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private fun copyPath(path: String, form: NinePatchForm): File {
        val f = File(path)
        val base = f.name.removeSuffix(".png").removeSuffix(".9")
        val suffix = if (form == NinePatchForm.SOURCE) "source" else "compiled"
        return FsUtil.uniqueSibling(File(f.parentFile, "$base.$suffix.9.png"), isDirectory = false)
    }

    companion object {
        fun encode(p: NinePatch, form: NinePatchForm): ByteArray = when (form) {
            NinePatchForm.SOURCE -> PngChunks.encode(p.toSource(), p.width + 2, p.height + 2)
            NinePatchForm.COMPILED -> PngChunks.encode(
                p.pixels, p.width, p.height,
                listOfNotNull(PngChunks.Chunk("npTc", p.npTc()), p.npLb()?.let { PngChunks.Chunk("npLb", it) }),
            )
        }

        private fun fill(marks: BooleanArray, divs: IntArray) {
            for (i in divs.indices step 2) for (x in divs[i] until divs[i + 1]) marks[x] = true
        }

        private fun runs(marks: BooleanArray): IntArray {
            val out = ArrayList<Int>()
            var i = 0
            while (i < marks.size) {
                if (marks[i]) {
                    val s = i
                    while (i < marks.size && marks[i]) i++
                    out += s; out += i
                } else i++
            }
            return out.toIntArray()
        }
    }
}
