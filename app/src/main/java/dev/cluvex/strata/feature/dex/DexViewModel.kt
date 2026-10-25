package dev.cluvex.strata.feature.dex

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.cluvex.strata.R
import dev.cluvex.strata.core.build.BuildRun
import dev.cluvex.strata.core.dex.ClassMember
import dev.cluvex.strata.core.dex.DexClass
import dev.cluvex.strata.core.dex.DexHit
import dev.cluvex.strata.core.dex.DexIndex
import dev.cluvex.strata.core.dex.DexSearch
import dev.cluvex.strata.core.dex.DexSearchMode
import dev.cluvex.strata.core.dex.DexSearchQuery
import dev.cluvex.strata.core.dex.DexTools
import dev.cluvex.strata.core.dex.DexTree
import dev.cluvex.strata.core.dex.JavaDecompiler
import dev.cluvex.strata.core.dex.SmaliOutline
import dev.cluvex.strata.core.dex.SmaliRef
import dev.cluvex.strata.core.dex.SmaliSyntaxException
import dev.cluvex.strata.core.dex.TextMatcher
import dev.cluvex.strata.core.fs.FsUtil
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.feature.build.runBuild
import dev.cluvex.strata.feature.editor.SearchQuery
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class DexView { SMALI, JAVA }

data class DexJump(val line: Int, val start: Int = 0, val end: Int = 0, val find: SearchQuery? = null, val seq: Long = System.nanoTime())

data class DexPlace(val type: String, val line: Int)

sealed interface DexLeave {
    data object Back : DexLeave
    data object Close : DexLeave
    data class Open(val cls: DexClass, val jump: DexJump?, val from: DexPlace?) : DexLeave
}

data class DexSearchState(
    val open: Boolean = false,
    val query: DexSearchQuery = DexSearchQuery(""),
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val hits: List<DexHit> = emptyList(),
    val truncated: Boolean = false,
    val finished: Boolean = false,
    val error: String? = null,
)

data class DexUiState(
    val path: String = "",
    val loading: Boolean = true,
    val error: String? = null,
    val editable: Boolean = false,
    val isApk: Boolean = false,
    val apkNeedsSigning: Boolean = false,
    val classes: List<DexClass> = emptyList(),
    val tree: DexTree? = null,
    val pkg: String = "",
    val query: String = "",
    val open: DexClass? = null,
    val fromSearch: Boolean = false,
    val view: DexView = DexView.SMALI,
    val code: String = "",
    val codeLoading: Boolean = false,
    val codeError: String? = null,
    val javaMembers: List<ClassMember> = emptyList(),
    val editing: Boolean = false,
    val dirty: Boolean = false,
    val jump: DexJump? = null,
    val history: List<DexPlace> = emptyList(),
    val leave: DexLeave? = null,
    val search: DexSearchState = DexSearchState(),
    val build: BuildRun? = null,
    val message: String? = null,
) {
    val name: String get() = File(path).name
}

class DexViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(DexUiState())
    val state: StateFlow<DexUiState> = _state.asStateFlow()
    private val work: File get() = File(getApplication<Application>().cacheDir, "dex-edit").apply { mkdirs() }
    private var savedSmali: String? = null
    private var codeJob: Job? = null
    private var searchJob: Job? = null

    @Volatile
    private var index: DexIndex? = null

    fun open(path: String) {
        if (_state.value.path == path && !_state.value.loading) return
        val lower = path.lowercase()
        val editable = lower.endsWith(".dex") || lower.endsWith(".apk") || lower.endsWith(".jar")
        _state.value = DexUiState(path = path, editable = editable, isApk = lower.endsWith(".apk"))
        viewModelScope.launch {
            val result = runCatching { withContext(Dispatchers.Default) { DexIndex.load(File(path)).also { index = it } } }
            result.onSuccess { loaded ->
                _state.update { it.copy(loading = false, classes = loaded.classes, tree = DexTree(loaded.classes)) }
            }.onFailure { e ->
                _state.update { it.copy(loading = false, error = e.message ?: tr(R.string.dex_cant_read)) }
            }
        }
    }

    fun setQuery(q: String) = _state.update { it.copy(query = q) }

    fun enterPackage(pkg: String) = _state.update { it.copy(pkg = pkg, query = "") }

    fun goUp(): Boolean {
        val s = _state.value
        val parent = s.tree?.parent(s.pkg) ?: return false
        _state.update { it.copy(pkg = parent) }
        return true
    }

    fun openClass(cls: DexClass, jump: DexJump? = null, from: DexPlace? = null) {
        val s = _state.value
        if (s.open != null && s.dirty && s.open != cls) {
            _state.update { it.copy(leave = DexLeave.Open(cls, jump, from)) }
            return
        }
        if (s.open == cls && s.view == DexView.SMALI && !s.codeLoading && s.codeError == null) {
            _state.update { st -> st.copy(jump = jump ?: DexJump(1), history = if (from != null) st.history + from else st.history) }
            return
        }
        show(cls, jump, from)
    }

    private fun show(cls: DexClass, jump: DexJump?, from: DexPlace?) {
        savedSmali = null
        _state.update {
            it.copy(
                open = cls,
                view = DexView.SMALI,
                editing = false,
                dirty = false,
                code = "",
                codeError = null,
                javaMembers = emptyList(),
                jump = jump,
                history = if (from != null) it.history + from else it.history,
                leave = null,
            )
        }
        loadCode()
    }

    fun back() {
        val s = _state.value
        if (s.open == null) return
        if (s.dirty) {
            _state.update { it.copy(leave = DexLeave.Back) }
            return
        }
        performBack()
    }

    fun closeClass() {
        if (_state.value.dirty) {
            _state.update { it.copy(leave = DexLeave.Close) }
            return
        }
        close()
    }

    private fun performBack() {
        val s = _state.value
        val last = s.history.lastOrNull()
        if (last == null) {
            close()
            return
        }
        _state.update { it.copy(history = it.history.dropLast(1)) }
        if (s.open?.type == last.type && s.view == DexView.SMALI) {
            _state.update { it.copy(jump = DexJump(last.line)) }
        } else {
            show(DexClass(last.type), DexJump(last.line), null)
        }
    }

    private fun close() {
        savedSmali = null
        codeJob?.cancel()
        val s = _state.value
        val pkg = s.open?.packageName?.takeIf { !s.fromSearch }?.let { s.tree?.landing(it) } ?: s.pkg
        _state.update {
            it.copy(open = null, code = "", dirty = false, editing = false, codeError = null, history = emptyList(), jump = null, leave = null, fromSearch = false, pkg = pkg)
        }
    }

    fun resolveLeave(save: Boolean) {
        val leave = _state.value.leave ?: return
        if (save) {
            save { proceed(leave) }
        } else {
            _state.update { it.copy(dirty = false) }
            proceed(leave)
        }
    }

    fun cancelLeave() = _state.update { it.copy(leave = null) }

    private fun proceed(leave: DexLeave) {
        _state.update { it.copy(leave = null) }
        when (leave) {
            DexLeave.Back -> performBack()
            DexLeave.Close -> close()
            is DexLeave.Open -> show(leave.cls, leave.jump, leave.from)
        }
    }

    fun setView(view: DexView) {
        if (_state.value.view == view) return
        if (view == DexView.JAVA && _state.value.dirty) {
            _state.update { it.copy(view = view) }
            loadJava()
        } else {
            _state.update { it.copy(view = view) }
            if (!(view == DexView.SMALI && _state.value.dirty)) loadCode()
        }
    }

    fun setEditing(on: Boolean) = _state.update { it.copy(editing = on && it.editable && it.view == DexView.SMALI) }

    fun onCodeEdited(text: String) {
        if (_state.value.view != DexView.SMALI) return
        _state.update { it.copy(code = text, dirty = text != savedSmali) }
    }

    fun consumeJump() = _state.update { it.copy(jump = null) }

    fun say(message: String) = _state.update { it.copy(message = message) }

    fun goTo(ref: SmaliRef, fromLine: Int) {
        val current = _state.value.open ?: return
        val idx = index ?: return
        viewModelScope.launch {
            val target = runCatching { withContext(Dispatchers.Default) { idx.target(ref) } }.getOrNull()
            if (target == null) {
                say(missing(ref, idx))
                return@launch
            }
            val m = target.member
            val jump = if (m != null) DexJump(m.line, m.nameColumn, m.nameColumn + m.name.length) else DexJump(1)
            openClass(DexClass(target.type), jump, DexPlace(current.type, fromLine))
        }
    }

    private fun missing(ref: SmaliRef, idx: DexIndex): String {
        val owner = when (ref) {
            is SmaliRef.Type -> ref.type
            is SmaliRef.Method -> ref.owner
            is SmaliRef.Field -> ref.owner
        }
        if (owner !in idx) return tr(R.string.dex_not_in_file, DexClass(owner).fqcn)
        val name = when (ref) {
            is SmaliRef.Method -> ref.name + "()"
            is SmaliRef.Field -> ref.name
            is SmaliRef.Type -> DexClass(ref.type).simpleName
        }
        return tr(R.string.dex_not_defined, name, DexClass(owner).simpleName)
    }

    private fun loadCode() {
        if (_state.value.view == DexView.JAVA) loadJava() else loadSmali()
    }

    private fun loadSmali() {
        val cls = _state.value.open ?: return
        val idx = index ?: return
        codeJob?.cancel()
        codeJob = viewModelScope.launch {
            _state.update { it.copy(codeLoading = true, codeError = null) }
            val result = runCatching { withContext(Dispatchers.Default) { idx.smali(cls.type) } }
            result.onSuccess { smali ->
                if (_state.value.open == cls) savedSmali = smali
                _state.update { if (it.open == cls) it.copy(code = smali, codeLoading = false, dirty = false) else it }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                _state.update { it.copy(codeLoading = false, codeError = e.message) }
            }
        }
    }

    private fun loadJava() {
        val cls = _state.value.open ?: return
        val path = _state.value.path
        codeJob?.cancel()
        codeJob = viewModelScope.launch {
            _state.update { it.copy(codeLoading = true, codeError = null, code = "", javaMembers = emptyList()) }
            val result = runCatching {
                withContext(Dispatchers.Default) {
                    val dex = DexTools.extractDexContaining(File(path), cls.type, File(work, "java-dex"))
                    JavaDecompiler.decompile(dex, cls.fqcn)
                }
            }
            result.onSuccess { java ->
                _state.update { if (it.open == cls && it.view == DexView.JAVA) it.copy(code = java.code, javaMembers = java.members, codeLoading = false) else it }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                _state.update { it.copy(codeLoading = false, codeError = e.message ?: tr(R.string.dex_decompile_failed)) }
            }
        }
    }

    fun save(after: () -> Unit = {}) {
        val s = _state.value
        val cls = s.open ?: return
        if (!s.editable || !s.dirty) {
            after()
            return
        }
        val code = s.code
        val input = File(s.path)
        val bareDex = !s.isApk && input.name.lowercase().endsWith(".dex")
        val write = when {
            bareDex -> DexTools.STEP_WRITE_DEX
            s.isApk -> DexTools.STEP_WRITE_APK
            else -> DexTools.STEP_WRITE_JAR
        }
        val plan = if (bareDex) {
            listOf(DexTools.STEP_ASSEMBLE, DexTools.STEP_REBUILD, write, STEP_RELOAD)
        } else {
            listOf(DexTools.STEP_FIND, DexTools.STEP_EXTRACT, DexTools.STEP_ASSEMBLE, DexTools.STEP_REBUILD, write, STEP_RELOAD)
        }
        val title = tr(
            when {
                s.isApk -> R.string.dex_rebuilding_apk
                bareDex -> R.string.dex_saving_dex
                else -> R.string.dex_rebuilding_jar
            },
        )
        val saved = tr(R.string.dex_saved_class, cls.simpleName)
        viewModelScope.launch {
            val result = runBuild(title, "${cls.fqcn} → ${input.name}", plan, publish = ::publish, summary = { _: DexIndex -> saved }) { log ->
                if (bareDex) {
                    val output = File(work, "out.dex")
                    DexTools.replaceClassSmali(input, cls.type, code, output, log)
                    log.step(write)
                    output.copyTo(input, overwrite = true)
                    output.delete()
                    log.info("${input.name} · ${BuildRun.size(input.length())}")
                } else {
                    DexTools.replaceClassInApk(input, cls.type, code, File(work, "apk-edit"), log)
                }
                log.step(STEP_RELOAD)
                DexIndex.load(input).also {
                    index = it
                    log.info(DexTools.classCount(it.size))
                }
            }
            result.onSuccess { loaded ->
                if (_state.value.open == cls) savedSmali = code
                _state.update {
                    val tree = DexTree(loaded.classes)
                    it.copy(
                        classes = loaded.classes,
                        tree = tree,
                        pkg = if (tree.exists(it.pkg)) it.pkg else tree.landing(it.pkg),
                        dirty = it.open == cls && it.view == DexView.SMALI && it.code != code,
                        apkNeedsSigning = it.apkNeedsSigning || s.isApk,
                    )
                }
                after()
            }.onFailure { e ->
                val first = (e as? SmaliSyntaxException)?.errors?.firstOrNull { it.line > 0 }
                _state.update {
                    it.copy(leave = null, jump = first?.let { err -> DexJump(err.line, (err.column - 1).coerceAtLeast(0)) } ?: it.jump)
                }
            }
        }
    }

    private fun publish(run: BuildRun) = _state.update { it.copy(build = run) }

    fun closeBuild() = _state.update { it.copy(build = null) }

    fun acknowledgeApkSigning() = _state.update { it.copy(apkNeedsSigning = false) }

    fun openSearch() = _state.update { it.copy(search = it.search.copy(open = true)) }

    fun closeSearch() {
        searchJob?.cancel()
        _state.update { it.copy(search = it.search.copy(open = false, running = false)) }
    }

    fun setSearchQuery(q: DexSearchQuery) = _state.update { it.copy(search = it.search.copy(query = q, error = null)) }

    fun runSearch() {
        val q = _state.value.search.query
        TextMatcher.check(q)?.let { msg ->
            _state.update { it.copy(search = it.search.copy(error = msg)) }
            return
        }
        val idx = index ?: return
        searchJob?.cancel()
        _state.update { it.copy(search = it.search.copy(running = true, done = 0, total = 0, hits = emptyList(), truncated = false, finished = false, error = null)) }
        searchJob = viewModelScope.launch {
            val lock = Any()
            val buffer = ArrayList<DexHit>()
            var progress = 0 to 0
            var last = 0L
            fun flush(force: Boolean) {
                val now = System.nanoTime()
                val fresh: List<DexHit>
                val p: Pair<Int, Int>
                synchronized(lock) {
                    if (!force && now - last < 150_000_000L) return
                    last = now
                    fresh = ArrayList(buffer)
                    buffer.clear()
                    p = progress
                }
                _state.update { st ->
                    if (st.search.query != q || !st.search.running) st else st.copy(search = st.search.copy(hits = st.search.hits + fresh, done = p.first, total = p.second))
                }
            }
            val result = runCatching {
                withContext(Dispatchers.Default) {
                    val job = coroutineContext
                    DexSearch.run(
                        idx,
                        q,
                        isCancelled = { !job.isActive },
                        onProgress = { d, t ->
                            synchronized(lock) { progress = d to t }
                            flush(false)
                        },
                        onHits = { hits -> synchronized(lock) { buffer += hits } },
                    )
                }
            }
            result.onSuccess { r ->
                _state.update { st ->
                    if (st.search.query != q) st else st.copy(search = st.search.copy(running = false, finished = true, hits = r.hits, truncated = r.truncated, done = r.searched, total = r.total))
                }
            }.onFailure { e ->
                if (e is CancellationException) throw e
                _state.update { st -> st.copy(search = st.search.copy(running = false, error = e.message ?: tr(R.string.dex_search_failed))) }
            }
        }
    }

    fun stopSearch() {
        searchJob?.cancel()
        _state.update { it.copy(search = it.search.copy(running = false, finished = true)) }
    }

    fun openHit(hit: DexHit) {
        val q = _state.value.search.query
        val find = if (q.mode == DexSearchMode.CLASSES) null else SearchQuery(q.text, regex = q.regex, matchCase = q.matchCase, wholeWord = q.wholeWord)
        _state.update { it.copy(fromSearch = true) }
        openClass(hit.cls, DexJump(hit.line, find = find))
    }

    fun toJar(onDone: (File) -> Unit) = fileTask(tr(R.string.dex_to_jar_title), ".jar", false, onDone) { input, out, log ->
        DexTools.dexToJar(input, out, log)
    }

    fun toSmali(onDone: (File) -> Unit) = fileTask(tr(R.string.dex_to_smali_title), "-smali", true, onDone) { input, out, log ->
        DexTools.disassemble(input, out, log = log)
    }

    fun toJava(onDone: (File) -> Unit) = fileTask(tr(R.string.dex_decompile_java), "-java", true, onDone) { input, out, log ->
        JavaDecompiler.decompileToDir(input, out, log)
    }

    private fun fileTask(title: String, suffix: String, isDir: Boolean, onDone: (File) -> Unit, work: (File, File, dev.cluvex.strata.core.build.BuildListener) -> Unit) {
        val input = File(_state.value.path)
        val output = FsUtil.uniqueSibling(File(input.parentFile, input.nameWithoutExtension + suffix), isDirectory = isDir)
        val wrote = tr(R.string.dex_wrote, output.name)
        viewModelScope.launch {
            val result = runBuild(title, "${input.name} → ${output.name}", emptyList(), publish = ::publish, summary = { _: Unit -> wrote }) { log ->
                work(input, output, log)
            }
            result.onSuccess { onDone(output) }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun smaliMembers(text: String): List<ClassMember> = SmaliOutline.parse(text)

    companion object {
        val STEP_RELOAD: String get() = tr(R.string.dex_step_reload)
    }

    override fun onCleared() {
        searchJob?.cancel()
        runCatching { FsUtil.deleteRecursively(work) }
    }
}
