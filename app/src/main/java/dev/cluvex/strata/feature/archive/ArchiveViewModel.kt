package dev.cluvex.strata.feature.archive

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.cluvex.strata.R
import dev.cluvex.strata.core.archive.ArchiveEntry
import dev.cluvex.strata.core.archive.ArchiveManager
import dev.cluvex.strata.core.archive.ArchiveReader
import dev.cluvex.strata.core.archive.ArchiveSelection
import dev.cluvex.strata.core.archive.ArchiveTree
import dev.cluvex.strata.core.archive.PasswordRequiredException
import dev.cluvex.strata.core.archive.WrongPasswordException
import dev.cluvex.strata.core.archive.ZipEditor
import dev.cluvex.strata.core.build.BuildRun
import dev.cluvex.strata.core.fs.FsUtil
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.i18n.trPlural
import dev.cluvex.strata.core.res.ArscDocument
import dev.cluvex.strata.core.res.AxmlCodec
import dev.cluvex.strata.core.res.AxmlEncodeException
import dev.cluvex.strata.core.res.BinaryResources
import dev.cluvex.strata.core.fs.OpProgress
import dev.cluvex.strata.core.sound.UiSound
import dev.cluvex.strata.core.sound.UiSounds
import dev.cluvex.strata.feature.explorer.TaskState
import dev.cluvex.strata.feature.explorer.taskState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

enum class EntryCodec { NONE, AXML }

data class OpenedEntry(
    val entry: String,
    val file: File,
    val stamp: Long,
    val length: Long,
    val codec: EntryCodec = EntryCodec.NONE,
) {
    val modified: Boolean get() = file.exists() && (file.lastModified() != stamp || file.length() != length)
}

data class EncodeError(val entry: String, val file: File, val message: String)

data class ArchiveUiState(
    val path: String = "",
    val dir: String = "",
    val entries: List<ArchiveEntry> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val passwordNeeded: Boolean = false,
    val wrongPassword: Boolean = false,
    val selected: Set<String> = emptySet(),
    val modified: List<OpenedEntry> = emptyList(),
    val apkChanged: Boolean = false,
    val encodeError: EncodeError? = null,
    val task: TaskState? = null,
    val build: dev.cluvex.strata.core.build.BuildRun? = null,
    val message: String? = null,
    val totalEntries: Int = 0,
) {
    val name: String get() = File(path).name
    val canUpdate: Boolean get() = ArchiveReader.canUpdate(name)
    val isApk: Boolean get() = name.lowercase().endsWith(".apk")
    val atRoot: Boolean get() = dir.isEmpty()
}

class ArchiveViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(ArchiveUiState())
    val state: StateFlow<ArchiveUiState> = _state.asStateFlow()

    private var tree = ArchiveTree(emptyList())
    private var password: CharArray? = null
    private var stamp = 0L
    private val opened = LinkedHashMap<String, OpenedEntry>()

    private val cacheRoot: File get() = File(getApplication<Application>().cacheDir, "archive/" + digest(_state.value.path))

    fun open(path: String) {
        val file = File(path)
        if (_state.value.path == path && !_state.value.loading && _state.value.error == null) {
            if (file.lastModified() != stamp) reload(keepDir = true)
            checkModified()
            return
        }
        _state.value = ArchiveUiState(path = path)
        opened.clear()
        reload(keepDir = false)
    }

    private fun reload(keepDir: Boolean) {
        val path = _state.value.path
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val file = File(path)
            val result = runCatching { withContext(Dispatchers.IO) { ArchiveReader.list(file, password) } }
            result.onSuccess { list ->
                tree = ArchiveTree(list)
                stamp = file.lastModified()
                val dir = _state.value.dir.takeIf { keepDir && tree.isDirectory(it) } ?: ""
                _state.update {
                    it.copy(
                        loading = false,
                        dir = dir,
                        entries = sorted(tree.list(dir)),
                        passwordNeeded = false,
                        wrongPassword = false,
                        selected = it.selected.filterTo(HashSet()) { s -> tree[s] != null },
                        totalEntries = tree.size,
                    )
                }
            }.onFailure { e ->
                when (e) {
                    is PasswordRequiredException -> _state.update { it.copy(loading = false, passwordNeeded = true) }
                    is WrongPasswordException -> _state.update { it.copy(loading = false, passwordNeeded = true, wrongPassword = true) }
                    else -> _state.update { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) }
                }
            }
        }
    }

    fun submitPassword(value: CharArray) {
        password?.fill('\u0000')
        password = value
        if (tree.size == 0 || _state.value.passwordNeeded) reload(keepDir = true)
    }

    fun enter(dir: String) {
        if (!tree.isDirectory(dir)) return
        val clean = ArchiveTree.normalize(dir)
        _state.update { it.copy(dir = clean, entries = sorted(tree.list(clean)), selected = emptySet()) }
    }

    fun up(): Boolean {
        val s = _state.value
        if (s.atRoot) return false
        enter(s.dir.substringBeforeLast('/', ""))
        return true
    }

    fun toggle(path: String) = _state.update {
        it.copy(selected = if (path in it.selected) it.selected - path else it.selected + path)
    }

    fun selectAll() = _state.update { s -> s.copy(selected = s.entries.mapTo(HashSet()) { it.path }) }
    fun clearSelection() = _state.update { it.copy(selected = emptySet()) }

    suspend fun materialize(entry: ArchiveEntry): File {
        opened[entry.path]?.file?.takeIf { it.isFile }?.let { return it }
        val path = _state.value.path
        val target = File(cacheRoot, entry.path)
        withContext(Dispatchers.IO) {
            target.parentFile?.mkdirs()
            ArchiveManager.extract(path, cacheRoot.path, password = password, selection = ArchiveSelection(setOf(entry.path)))
                .collect { }
        }
        if (!target.isFile) throw java.io.IOException(tr(R.string.archive_couldnt_extract, entry.name))
        val decoded = withContext(Dispatchers.IO) { decodeIfBinary(entry, target) }
        if (decoded != null) {
            val (file, codec) = decoded
            if (codec != null) opened[entry.path] = OpenedEntry(entry.path, file, file.lastModified(), file.length(), codec)
            return file
        }
        opened[entry.path] = OpenedEntry(entry.path, target, target.lastModified(), target.length())
        return target
    }

    private fun decodeIfBinary(entry: ArchiveEntry, target: File): Pair<File, EntryCodec?>? {
        val s = _state.value
        return runCatching {
            when {
                BinaryResources.isBinaryXml(target) -> {
                    val xml = if (s.isApk) BinaryResources.decodeXmlInApk(File(s.path), entry.path) else BinaryResources.decodeXml(target)
                    val copy = File(cacheRoot, entry.path + ".decoded.xml").apply { parentFile?.mkdirs(); writeText(xml) }
                    copy to (if (s.canUpdate) EntryCodec.AXML else null)
                }
                entry.name == ArscDocument.RESOURCES_ARSC -> {
                    val dump = BinaryResources.dumpResourcesFromArsc(target)
                    File(cacheRoot, entry.path + ".resources.txt").apply { parentFile?.mkdirs(); writeText(dump) } to null
                }
                else -> null
            }
        }.getOrNull()
    }

    fun checkModified() {
        val changed = opened.values.filter { it.modified }
        _state.update { it.copy(modified = changed) }
    }

    fun applyUpdates() {
        val s = _state.value
        val changes = s.modified
        if (changes.isEmpty()) return
        if (!s.canUpdate) {
            discardUpdates(deleteCopies = false)
            return
        }
        val archive = File(s.path)
        val encodes = changes.filter { it.codec == EntryCodec.AXML }.map { ArscDocument.encodeStep(it.entry) }
        val write = tr(R.string.build_step_write_into, archive.name)
        val count = BuildRun.number(changes.size)
        viewModelScope.launch {
            val result = dev.cluvex.strata.feature.build.runBuild(
                tr(if (s.isApk) R.string.archive_updating_apk else R.string.archive_updating),
                trPlural(R.plurals.archive_files_into, changes.size, count, archive.name),
                encodes + write,
                publish = { run -> _state.update { it.copy(build = run) } },
                summary = { _: Unit -> trPlural(R.plurals.archive_updated_files, changes.size, count) },
            ) { log ->
                withContext(Dispatchers.IO) {
                    val staged = File(cacheRoot, ".encoded").apply { FsUtil.deleteRecursively(this); mkdirs() }
                    try {
                        val contents = LinkedHashMap<String, File>()
                        for (change in changes) {
                            if (change.codec == EntryCodec.AXML) log.step(ArscDocument.encodeStep(change.entry))
                            val file = stage(change, archive, s.isApk, staged)
                            if (change.codec == EntryCodec.AXML) log.info(tr(R.string.archive_binary_xml, BuildRun.size(file.length())))
                            contents[change.entry] = file
                        }
                        log.step(write)
                        for ((entry, file) in contents) log.info("$entry · ${BuildRun.size(file.length())}")
                        ZipEditor.putAll(archive, contents)
                        log.info("${archive.name} · ${BuildRun.size(archive.length())}")
                    } finally {
                        FsUtil.deleteRecursively(staged)
                    }
                }
            }
            result.onSuccess {
                changes.forEach { opened[it.entry] = it.copy(stamp = it.file.lastModified(), length = it.file.length()) }
                _state.update { it.copy(modified = emptyList(), apkChanged = it.apkChanged || it.isApk) }
                reload(keepDir = true)
            }.onFailure { e ->
                if (e is EntryEncodeException) {
                    _state.update { it.copy(modified = emptyList(), encodeError = EncodeError(e.entry, e.file, e.message ?: tr(R.string.archive_invalid_xml))) }
                }
            }
        }
    }

    fun closeBuild() = _state.update { it.copy(build = null) }

    private class EntryEncodeException(val entry: String, val file: File, message: String) : java.io.IOException(message)

    private fun stage(change: OpenedEntry, archive: File, isApk: Boolean, staged: File): File = when (change.codec) {
        EntryCodec.NONE -> change.file
        EntryCodec.AXML -> {
            val text = change.file.readText()
            val bytes = try {
                if (isApk) AxmlCodec.encodeForApk(text, archive) else AxmlCodec.encodeStandalone(text)
            } catch (e: AxmlEncodeException) {
                throw EntryEncodeException(change.entry, change.file, e.message ?: tr(R.string.archive_invalid_xml))
            }
            File(staged, change.entry.replace('/', '_')).apply { writeBytes(bytes) }
        }
    }

    fun dismissEncodeError() = _state.update { it.copy(encodeError = null) }

    fun discardEdits(entry: String) {
        opened.remove(entry)?.file?.delete()
        _state.update { it.copy(encodeError = null) }
    }

    fun discardUpdates(deleteCopies: Boolean = true) {
        _state.value.modified.forEach {
            if (deleteCopies) {
                opened.remove(it.entry)
                it.file.delete()
            } else {
                opened[it.entry] = it.copy(stamp = it.file.lastModified(), length = it.file.length())
            }
        }
        _state.update { it.copy(modified = emptyList()) }
    }

    fun extract(paths: Set<String>, destDir: String, onDone: (String) -> Unit = {}) {
        val s = _state.value
        val selection = if (paths.isEmpty()) null else ArchiveSelection(paths, base = s.dir)
        viewModelScope.launch {
            val title = tr(R.string.archive_extracting)
            _state.update { it.copy(task = TaskState(title, indeterminate = true)) }
            try {
                ArchiveManager.extract(s.path, destDir, password = password, selection = selection).collect { p ->
                    if (p is OpProgress.Running) _state.update { it.copy(task = taskState(title, p)) }
                }
                _state.update { it.copy(task = null, selected = emptySet(), message = tr(R.string.archive_extracted_to, destDir)) }
                onDone(destDir)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(task = null, message = tr(R.string.archive_extract_failed, e.message ?: e.javaClass.simpleName)) }
            }
        }
    }

    fun deleteStats(paths: Set<String>): ArchiveTree.Stats = tree.stats(paths)

    fun delete(paths: Set<String>) {
        val s = _state.value
        if (!s.canUpdate || paths.isEmpty()) return
        runTask(R.string.archive_deleting, R.string.archive_delete_failed, "Deleting", onSuccess = {
            _state.update { it.copy(selected = emptySet(), apkChanged = it.apkChanged || it.isApk) }
            reload(keepDir = true)
        }) {
            val removed = ZipEditor.delete(File(s.path), paths)
            if (removed == 0) throw java.io.IOException(tr(R.string.archive_nothing_to_delete))
        }
    }

    fun rename(path: String, newName: String) {
        val s = _state.value
        val name = newName.trim()
        if (!s.canUpdate || name.isEmpty() || name.contains('/')) return
        val parent = path.substringBeforeLast('/', "")
        runTask(R.string.archive_renaming, R.string.archive_rename_failed, "Renaming", onSuccess = {
            _state.update { it.copy(selected = emptySet(), apkChanged = it.apkChanged || it.isApk) }
            reload(keepDir = true)
        }) {
            ZipEditor.rename(File(s.path), path, if (parent.isEmpty()) name else "$parent/$name")
        }
    }

    fun add(sources: List<File>) {
        val s = _state.value
        if (!s.canUpdate || sources.isEmpty()) return
        runTask(R.string.archive_adding, R.string.archive_add_failed, "Adding", onSuccess = {
            _state.update { it.copy(apkChanged = it.apkChanged || it.isApk, message = trPlural(R.plurals.archive_added_items, sources.size, sources.size)) }
            reload(keepDir = true)
        }) {
            ZipEditor.add(File(s.path), sources, s.dir)
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun acknowledgeApkChange() = _state.update { it.copy(apkChanged = false) }

    private fun runTask(@StringRes title: Int, @StringRes failed: Int, soundKey: String, onSuccess: () -> Unit, work: () -> Unit) {
        viewModelScope.launch {
            _state.update { it.copy(task = TaskState(tr(title), indeterminate = true)) }
            val result = runCatching { withContext(Dispatchers.IO) { work() } }
            _state.update { it.copy(task = null) }
            result.onSuccess {
                UiSounds.forTask(soundKey, failed = false, okCount = 1)
                onSuccess()
            }.onFailure { e ->
                if (e is CancellationException) throw e
                UiSounds.play(UiSound.ERROR)
                _state.update { it.copy(message = tr(failed, e.message ?: e.javaClass.simpleName)) }
            }
        }
    }

    override fun onCleared() {
        password?.fill('\u0000')
        val root = runCatching { cacheRoot }.getOrNull()
        if (root != null && _state.value.path.isNotEmpty()) FsUtil.deleteRecursively(root)
    }

    private companion object {
        fun sorted(list: List<ArchiveEntry>): List<ArchiveEntry> =
            list.sortedWith(compareBy<ArchiveEntry>({ !it.isDirectory }, { it.name.lowercase() }))

        fun digest(text: String): String =
            MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    }
}
