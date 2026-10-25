package dev.cluvex.strata.feature.apps

import android.app.Application
import android.graphics.Bitmap
import android.os.Environment
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.cluvex.strata.R
import dev.cluvex.strata.core.apk.InstalledSigners
import dev.cluvex.strata.core.apk.AppSigners
import dev.cluvex.strata.core.apk.ApkExtractor
import dev.cluvex.strata.core.apk.InstalledApp
import dev.cluvex.strata.core.apk.InstalledApps
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.i18n.trPlural
import dev.cluvex.strata.feature.explorer.TaskState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class AppsUiState(
    val loading: Boolean = true,
    val all: List<InstalledApp> = emptyList(),
    val showSystem: Boolean = false,
    val query: String = "",
    val selected: Set<String> = emptySet(),
    val task: TaskState? = null,
    val message: String? = null,
    val lastExtracted: File? = null,
) {
    val visible: List<InstalledApp>
        get() = all.asSequence()
            .filter { showSystem || !it.system }
            .filter { query.isBlank() || it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }
            .toList()
}

data class SignerSummary(
    val label: String,
    val shortHash: String,
    val platform: Boolean,
    val debug: Boolean,
    val rotated: Boolean,
    val multiple: Boolean,
)

class AppsViewModel(app: Application) : AndroidViewModel(app) {

    private val pm = app.packageManager
    private val _state = MutableStateFlow(AppsUiState())
    val state: StateFlow<AppsUiState> = _state.asStateFlow()
    private val icons = LruCache<String, Bitmap>(256)
    private val signerSummaries = LruCache<String, SignerSummary>(1024)

    private val platformSha256: String? by lazy {
        InstalledSigners.of(pm, "android")?.primary?.let(InstalledSigners::sha256)
    }

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val apps = withContext(Dispatchers.IO) { InstalledApps(pm).list(includeSystem = true) }
            _state.update { it.copy(loading = false, all = apps, selected = it.selected.filterTo(HashSet()) { p -> apps.any { a -> a.packageName == p } }) }
        }
    }

    fun setQuery(q: String) = _state.update { it.copy(query = q) }
    fun setShowSystem(on: Boolean) = _state.update { it.copy(showSystem = on) }

    fun toggle(pkg: String) = _state.update {
        it.copy(selected = if (pkg in it.selected) it.selected - pkg else it.selected + pkg)
    }

    fun selectAll() = _state.update { s -> s.copy(selected = s.visible.mapTo(HashSet()) { it.packageName }) }
    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }

    suspend fun signerSummary(pkg: String): SignerSummary? {
        signerSummaries.get(pkg)?.let { return it }
        return withContext(Dispatchers.IO) {
            val signers = InstalledSigners.of(pm, pkg) ?: return@withContext null
            val cert = signers.primary ?: return@withContext null
            SignerSummary(
                label = InstalledSigners.label(cert),
                shortHash = InstalledSigners.shortHash(cert),
                platform = platformSha256 != null && InstalledSigners.sha256(cert) == platformSha256,
                debug = InstalledSigners.isDebugKey(cert),
                rotated = signers.past.isNotEmpty(),
                multiple = signers.multipleSigners,
            ).also { signerSummaries.put(pkg, it) }
        }
    }

    suspend fun signers(pkg: String): AppSigners? = withContext(Dispatchers.IO) { InstalledSigners.of(pm, pkg) }

    suspend fun icon(pkg: String): Bitmap? {
        icons.get(pkg)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching { pm.getApplicationIcon(pkg).toBitmap(96, 96) }.getOrNull()?.also { icons.put(pkg, it) }
        }
    }

    fun extract(apps: List<InstalledApp>, dir: String, pattern: String) {
        if (apps.isEmpty()) return
        val target = if (dir.isBlank()) defaultDir() else File(dir)
        viewModelScope.launch {
            var done = 0
            val failed = ArrayList<String>()
            try {
                for ((i, app) in apps.withIndex()) {
                    _state.update { it.copy(task = TaskState(tr(R.string.apps_extracting), app.label, i.toFloat() / apps.size, indeterminate = false)) }
                    val result = runCatching { withContext(Dispatchers.IO) { ApkExtractor.extract(app, target, pattern) } }
                    if (result.isSuccess) done++ else failed += app.label
                    _state.update { it.copy(task = TaskState(tr(R.string.apps_extracting), app.label, (i + 1f) / apps.size, indeterminate = false)) }
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                _state.update { it.copy(task = null) }
            }
            val msg = if (failed.isEmpty()) {
                trPlural(R.plurals.apps_extracted, done, done, target.path)
            } else {
                tr(R.string.apps_extract_partial, done, failed.size, failed.joinToString())
            }
            _state.update { it.copy(message = msg, selected = emptySet(), lastExtracted = if (done > 0) target else null) }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    companion object {
        fun defaultDir(): File = File(Environment.getExternalStorageDirectory(), "Strata/APKs")
    }
}
