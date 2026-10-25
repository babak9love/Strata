package dev.cluvex.strata.feature.markdown

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mikepenz.markdown.model.State
import com.mikepenz.markdown.model.parseMarkdown
import dev.cluvex.strata.R
import dev.cluvex.strata.core.fs.LocalFileAccess
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.root.RootManager
import dev.cluvex.strata.core.text.TextEncoding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class MarkdownUiState(
    val path: String = "",
    val loading: Boolean = true,
    val document: State? = null,
    val error: String? = null,
)

class MarkdownViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(MarkdownUiState())
    val state: StateFlow<MarkdownUiState> = _state.asStateFlow()

    fun load(path: String, rootEnabled: Boolean) {
        _state.value = MarkdownUiState(path = path, loading = true, document = _state.value.document.takeIf { _state.value.path == path })
        viewModelScope.launch {
            val result = runCatching {
                val text = withContext(Dispatchers.IO) {
                    val local = File(path)
                    val access = if (local.canRead() || !rootEnabled) LocalFileAccess else RootManager.access() ?: LocalFileAccess
                    val file = access.file(path)
                    if (file.length() > MAX_BYTES) throw TooLarge(file.length())
                    TextEncoding.decode(access.openInput(file).use { it.readBytes() }).text
                }
                withContext(Dispatchers.Default) { parseMarkdown(text) }
            }
            if (_state.value.path != path) return@launch
            _state.value = result.fold(
                onSuccess = { MarkdownUiState(path = path, loading = false, document = it) },
                onFailure = { e ->
                    MarkdownUiState(
                        path = path,
                        loading = false,
                        error = if (e is TooLarge) tr(R.string.markdown_too_large, e.size / 1024 / 1024) else e.message ?: e.javaClass.simpleName,
                    )
                },
            )
        }
    }

    private class TooLarge(val size: Long) : Exception()

    private companion object {
        const val MAX_BYTES = 8L * 1024 * 1024
    }
}
