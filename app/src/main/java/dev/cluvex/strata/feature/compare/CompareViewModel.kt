package dev.cluvex.strata.feature.compare

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.cluvex.strata.R
import dev.cluvex.strata.core.compare.ArchiveEntryDiff
import dev.cluvex.strata.core.compare.DiffLine
import dev.cluvex.strata.core.compare.DiffStats
import dev.cluvex.strata.core.compare.EntryDiff
import dev.cluvex.strata.core.compare.FileCompare
import dev.cluvex.strata.core.fs.LocalFileAccess
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.root.RootManager
import dev.cluvex.strata.core.text.TextEncoding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

sealed interface CompareResult {
    data class Text(val lines: List<DiffLine>, val stats: DiffStats, val exact: Boolean) : CompareResult
    data class Archive(val entries: List<ArchiveEntryDiff>, val identical: Boolean) : CompareResult
    data class Binary(
        val identical: Boolean,
        val leftSha: String,
        val rightSha: String,
        val leftSize: Long,
        val rightSize: Long,
    ) : CompareResult
}

data class CompareUiState(
    val leftName: String = "",
    val rightName: String = "",
    val loading: Boolean = true,
    val result: CompareResult? = null,
    val error: String? = null,
)

class CompareViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(CompareUiState())
    val state: StateFlow<CompareUiState> = _state.asStateFlow()

    private var started = false

    fun start(leftPath: String, rightPath: String, rootEnabled: Boolean) {
        if (started) return
        started = true
        _state.update { it.copy(leftName = File(leftPath).name, rightName = File(rightPath).name, loading = true) }
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { compute(leftPath, rightPath, rootEnabled) }
            }
            result.onSuccess { r -> _state.update { it.copy(loading = false, result = r) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message ?: tr(R.string.compare_failed)) } }
        }
    }

    private suspend fun compute(leftPath: String, rightPath: String, rootEnabled: Boolean): CompareResult {
        val temps = ArrayList<File>()
        try {
            val left = resolve(leftPath, rootEnabled, temps)
            val right = resolve(rightPath, rootEnabled, temps)
            return when {
                FileCompare.isArchivePair(leftPath, rightPath) -> {
                    val entries = FileCompare.archiveDiff(left, right)
                    CompareResult.Archive(entries, entries.all { it.diff == EntryDiff.SAME })
                }
                isTextCandidate(left) && isTextCandidate(right) -> {
                    val lt = TextEncoding.decode(left.readBytes()).text
                    val rt = TextEncoding.decode(right.readBytes()).text
                    val diff = FileCompare.textDiff(lt, rt)
                    CompareResult.Text(diff, FileCompare.stats(diff), exact = lt == rt)
                }
                else -> CompareResult.Binary(
                    identical = FileCompare.bytesEqual(left, right),
                    leftSha = FileCompare.sha256(left),
                    rightSha = FileCompare.sha256(right),
                    leftSize = left.length(),
                    rightSize = right.length(),
                )
            }
        } finally {
            temps.forEach { it.delete() }
        }
    }

    private suspend fun resolve(path: String, rootEnabled: Boolean, temps: MutableList<File>): File {
        val local = File(path)
        if (local.canRead()) return local
        val access = if (rootEnabled) RootManager.access() ?: LocalFileAccess else LocalFileAccess
        val dir = File(getApplication<Application>().cacheDir, "compare").apply { mkdirs() }
        val tmp = File(dir, "cmp_" + digest(path))
        access.openInput(access.file(path)).use { input -> tmp.outputStream().use { input.copyTo(it) } }
        temps += tmp
        return tmp
    }

    private fun isTextCandidate(file: File): Boolean {
        if (file.length() > TEXT_LIMIT) return false
        val head = ByteArray(minOf(file.length().toInt(), 8192))
        file.inputStream().use { input ->
            var off = 0
            while (off < head.size) {
                val n = input.read(head, off, head.size - off)
                if (n < 0) break
                off += n
            }
        }
        return !TextEncoding.looksBinary(head)
    }

    private fun digest(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        const val TEXT_LIMIT = 4L * 1024 * 1024
    }
}
