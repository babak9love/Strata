package dev.cluvex.strata.feature.keys

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.cluvex.strata.R
import dev.cluvex.strata.core.apk.KeyIdentity
import dev.cluvex.strata.core.apk.KeyStoreFactory
import dev.cluvex.strata.core.apk.KeystoreFile
import dev.cluvex.strata.core.apk.KeystoreFormat
import dev.cluvex.strata.core.apk.SavedKeys
import dev.cluvex.strata.core.fs.FsUtil
import dev.cluvex.strata.core.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class SigningKeysState(
    val loading: Boolean = true,
    val keys: List<SavedKeys.Saved> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
    val created: String? = null,
    val importing: String? = null,
)

class SigningKeysViewModel(app: Application) : AndroidViewModel(app) {

    private val vault = SavedKeys(File(app.filesDir, "keystores"))
    private val _state = MutableStateFlow(SigningKeysState())
    val state: StateFlow<SigningKeysState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val keys = withContext(Dispatchers.IO) { runCatching { vault.list() }.getOrDefault(emptyList()) }
            _state.update { it.copy(loading = false, keys = keys) }
        }
    }

    private fun work(done: (String) -> Unit = {}, block: suspend () -> String) {
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val r = runCatching { withContext(Dispatchers.IO) { block() } }
            _state.update { it.copy(busy = false, message = r.getOrElse { e -> tr(R.string.keys_failed, e.message ?: e.javaClass.simpleName) }) }
            r.onSuccess(done)
            refresh()
        }
    }

    fun generate(name: String, identity: KeyIdentity) = work {
        vault.save(name, KeyStoreFactory.generateKey(identity))
        tr(R.string.keys_generated, name)
    }

    fun rename(id: String, name: String) = work { vault.rename(id, name); tr(R.string.keys_renamed, name) }

    fun delete(id: String) = work { vault.delete(id); tr(R.string.keys_deleted) }

    fun export(id: String, format: KeystoreFormat, alias: String, storePassword: CharArray, keyPassword: CharArray, dir: String) = work {
        try {
            val saved = vault.list().first { it.id == id }
            val bytes = KeystoreFile.export(format, listOf(alias to vault.load(id)), storePassword, keyPassword)
            val out = FsUtil.uniqueSibling(File(dir, "${safe(saved.name)}.${format.extension}"), isDirectory = false)
            out.writeBytes(bytes)
            _state.update { it.copy(created = out.path) }
            tr(R.string.keys_saved_file, out.name)
        } finally {
            storePassword.fill('\u0000')
            keyPassword.fill('\u0000')
        }
    }

    fun exportPk8(id: String, dir: String) = work {
        val saved = vault.list().first { it.id == id }
        val key = vault.load(id)
        val base = generateSequence(1) { it + 1 }.map { n -> if (n == 1) safe(saved.name) else "${safe(saved.name)} ($n)" }
            .first { !File(dir, "$it.pk8").exists() && !File(dir, "$it.x509.pem").exists() }
        File(dir, "$base.pk8").writeBytes(KeystoreFile.pk8(key))
        File(dir, "$base.x509.pem").writeText(KeystoreFile.x509Pem(key))
        _state.update { it.copy(created = File(dir, "$base.pk8").path) }
        tr(R.string.keys_saved_pk8, base)
    }

    fun importFrom(uri: Uri) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val copy = runCatching {
                withContext(Dispatchers.IO) {
                    val name = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    } ?: "keystore.jks"
                    val dir = File(app.cacheDir, "import-key").apply { FsUtil.deleteRecursively(this); mkdirs() }
                    File(dir, safe(name)).also { f -> app.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } } }
                }
            }
            copy.onSuccess { f -> _state.update { it.copy(importing = f.path) } }
                .onFailure { e -> _state.update { it.copy(message = tr(R.string.keys_read_failed, e.message)) } }
        }
    }

    fun importDone(message: String?) {
        _state.update { it.copy(importing = null, message = message ?: it.message) }
        File(getApplication<Application>().cacheDir, "import-key").let { FsUtil.deleteRecursively(it) }
        refresh()
    }

    fun consumeMessage() = _state.update { it.copy(message = null, created = null) }

    private fun safe(name: String) = name.replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_").trim().ifEmpty { "key" }
}
