package dev.cluvex.strata.feature.arsc

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.cluvex.strata.R
import dev.cluvex.strata.core.build.BuildRun
import dev.cluvex.strata.core.fs.LocalFileAccess
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.i18n.trPlural
import dev.cluvex.strata.core.res.ArscDocument
import dev.cluvex.strata.core.res.ArscResource
import dev.cluvex.strata.core.res.ArscTypeCount
import dev.cluvex.strata.core.res.ArscValue
import dev.cluvex.strata.core.root.RootManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class ArscUiState(
    val fileName: String = "",
    val archiveName: String? = null,
    val packageName: String = "",
    val loading: Boolean = true,
    val error: String? = null,
    val types: List<ArscTypeCount> = emptyList(),
    val type: String? = null,
    val query: String = "",
    val resources: List<ArscResource> = emptyList(),
    val sheet: ArscResource? = null,
    val dirty: Boolean = false,
    val saving: Boolean = false,
    val apkNeedsSigning: Boolean = false,
    val build: BuildRun? = null,
    val message: String? = null,
)

class ArscEditorViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(ArscUiState())
    val state: StateFlow<ArscUiState> = _state.asStateFlow()

    private val io: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
    private var doc: ArscDocument? = null
    private var path = ""
    private var entry: String? = null
    private var rootEnabled = false
    private var listJob: Job? = null
    private val originals = HashMap<Pair<Int, String>, String>()
    private val changed = HashSet<Pair<Int, String>>()

    fun load(path: String, entry: String?, rootEnabled: Boolean) {
        val same = this.path == path && this.entry == entry
        if (same && (doc != null || _state.value.loading)) return
        this.path = path
        this.entry = entry
        this.rootEnabled = rootEnabled
        doc = null
        originals.clear()
        changed.clear()
        _state.value = ArscUiState(
            fileName = entry?.substringAfterLast('/') ?: File(path).name,
            archiveName = entry?.let { File(path).name },
        )
        viewModelScope.launch {
            val result = runCatching {
                withContext(io) {
                    val d = if (entry != null) ArscDocument.readFromZip(File(path), entry) else ArscDocument.read(readBytes(path))
                    doc = d
                    (d.packageNames.firstOrNull() ?: "") to d.types()
                }
            }
            result.onSuccess { (pkg, types) -> _state.update { it.copy(loading = false, packageName = pkg, types = types) } }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.message ?: tr(R.string.arsc_cant_read_table)) } }
        }
    }

    fun openType(type: String?) {
        _state.update { it.copy(type = type, query = "", resources = emptyList()) }
        refreshList()
    }

    fun setQuery(query: String) {
        _state.update { it.copy(query = query) }
        refreshList()
    }

    private fun refreshList() {
        val d = doc ?: return
        val s = _state.value
        listJob?.cancel()
        listJob = viewModelScope.launch {
            val list = withContext(io) {
                when {
                    s.type != null -> d.resources(s.type, s.query)
                    s.query.isNotBlank() -> d.search(s.query)
                    else -> emptyList()
                }
            }
            _state.update { it.copy(resources = list) }
        }
    }

    fun openSheet(resource: ArscResource?) = _state.update { it.copy(sheet = resource) }

    fun setValue(resource: ArscResource, value: ArscValue, text: String, onDone: (String?) -> Unit) {
        val d = doc ?: return
        viewModelScope.launch {
            val result = withContext(io) { d.setValue(resource.id, value.qualifiers, text) }
            result.onSuccess { updated ->
                val key = resource.id to value.qualifiers
                val original = originals.getOrPut(key) { value.text }
                val now = updated.values.firstOrNull { it.qualifiers == value.qualifiers }?.text
                if (now == original) changed.remove(key) else changed.add(key)
                _state.update { s ->
                    s.copy(dirty = changed.isNotEmpty(), sheet = updated, resources = s.resources.map { if (it.id == updated.id) updated else it })
                }
                onDone(null)
            }.onFailure { e -> onDone(e.message ?: tr(R.string.arsc_invalid_value_short)) }
        }
    }

    fun save(onSaved: () -> Unit = {}) {
        val d = doc ?: return
        if (_state.value.saving) return
        _state.update { it.copy(saving = true) }
        val e = entry
        val target = File(path)
        val plan = if (e != null) {
            listOf(ArscDocument.encodeStep(e), ArscDocument.writeStep(target))
        } else {
            listOf(ArscDocument.encodeStep(target.name), tr(R.string.build_step_write, target.name))
        }
        viewModelScope.launch {
            val result = dev.cluvex.strata.feature.build.runBuild(
                tr(R.string.arsc_saving),
                (e ?: target.name) + " → " + target.name,
                plan,
                publish = { run -> _state.update { it.copy(build = run) } },
                summary = { _: Unit -> changed.size.coerceAtLeast(1).let { n -> trPlural(R.plurals.arsc_saved_changes, n, BuildRun.number(n)) } },
            ) { log ->
                withContext(io) {
                    if (e != null) {
                        d.writeInto(target, e, File(getApplication<Application>().cacheDir, "arsc"), log)
                    } else {
                        log.step(plan[0])
                        val bytes = d.encode()
                        log.info(BuildRun.size(bytes.size.toLong()))
                        log.step(plan[1])
                        writeBytes(path, bytes)
                    }
                }
            }
            result.onSuccess {
                originals.clear()
                changed.clear()
                val intoApk = e != null && path.endsWith(".apk", ignoreCase = true)
                _state.update { it.copy(saving = false, dirty = false, apkNeedsSigning = intoApk) }
                onSaved()
            }.onFailure {
                _state.update { it.copy(saving = false) }
            }
        }
    }

    fun acknowledgeSigning() = _state.update { it.copy(apkNeedsSigning = false) }

    fun closeBuild() = _state.update { it.copy(build = null) }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    val apkPath: String get() = path

    private suspend fun readBytes(path: String): ByteArray {
        val f = File(path)
        if (f.canRead()) return f.readBytes()
        val a = (if (rootEnabled) RootManager.access() else null) ?: LocalFileAccess
        return a.openInput(a.file(path)).use { it.readBytes() }
    }

    private suspend fun writeBytes(path: String, bytes: ByteArray) {
        val f = File(path)
        val a = if (f.canWrite() || !rootEnabled) LocalFileAccess else RootManager.access() ?: LocalFileAccess
        a.openOutput(a.file(path)).use { it.write(bytes) }
    }
}
