package dev.cluvex.strata.feature.plugin

import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.cluvex.strata.R
import dev.cluvex.strata.core.data.SettingsRepository
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.model.FileItem
import dev.cluvex.strata.core.plugin.PluginInfo
import dev.cluvex.strata.core.plugin.PluginOutcome
import dev.cluvex.strata.core.plugin.PluginProtocol
import dev.cluvex.strata.core.plugin.PluginShare
import dev.cluvex.strata.core.plugin.PluginTrust
import dev.cluvex.strata.core.plugin.Plugins
import dev.cluvex.strata.core.plugin.StagedFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.Locale

sealed interface PluginStep {
    data object Idle : PluginStep

    data class Choose(val name: String, val plugins: List<PluginInfo>?) : PluginStep

    data class Confirm(val plugin: PluginInfo, val trust: PluginTrust, val pinned: String?) : PluginStep

    data class Running(val plugin: PluginInfo) : PluginStep

    data class Said(val plugin: PluginInfo, val message: String) : PluginStep

    data class Ready(val plugin: PluginInfo, val file: File, val name: String?, val message: String?, val preview: String?) : PluginStep

    data class Failed(val message: String) : PluginStep
}

class PluginRunViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsRepository(app)
    private val share = PluginShare(app)
    private val _step = MutableStateFlow<PluginStep>(PluginStep.Idle)
    val step: StateFlow<PluginStep> = _step.asStateFlow()

    private val _launch = MutableStateFlow<Intent?>(null)
    val launch: StateFlow<Intent?> = _launch.asStateFlow()

    var item: FileItem? = null
        private set
    private val fileName get() = item?.name.orEmpty()
    private var open: (suspend () -> InputStream)? = null
    private var staged: StagedFile? = null

    private val cleaned = viewModelScope.launch(Dispatchers.IO) { share.cleanUp() }

    fun begin(item: FileItem, open: suspend () -> InputStream) {
        val name = item.name
        this.item = item
        this.open = open
        _step.value = PluginStep.Choose(name, null)
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { Plugins.discover(getApplication(), mimeOf(name)).filter { it.handles(name) } }
            if (_step.value is PluginStep.Choose) _step.value = PluginStep.Choose(name, found)
        }
    }

    fun choose(plugin: PluginInfo) = viewModelScope.launch {
        val pins = settings.pluginPins.first()
        when (val trust = pins.trust(plugin)) {
            PluginTrust.TRUSTED -> start(plugin)
            else -> _step.value = PluginStep.Confirm(plugin, trust, pins.pinned(plugin.packageName))
        }
    }

    fun trustAndRun(plugin: PluginInfo) = viewModelScope.launch {
        settings.pinPlugin(plugin.packageName, plugin.signer)
        start(plugin)
    }

    private suspend fun start(plugin: PluginInfo) {
        val read = open ?: return dismiss()
        _step.value = PluginStep.Running(plugin)
        try {
            cleaned.join()
            val copy = withContext(Dispatchers.IO) { share.stage(read(), fileName) }
            staged = copy
            _launch.value = Intent(PluginProtocol.ACTION_FILE)
                .setClassName(plugin.packageName, plugin.activityName)
                .setDataAndType(copy.uri, mimeOf(fileName))
                .putExtra(PluginProtocol.EXTRA_API, PluginProtocol.API)
                .putExtra(PluginProtocol.EXTRA_FILE_NAME, fileName)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .apply { clipData = ClipData.newRawUri(fileName, copy.uri) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            finishRun()
            _step.value = PluginStep.Failed(tr(R.string.plugin_read_failed, fileName, e.message ?: e.javaClass.simpleName))
        }
    }

    fun launched() {
        _launch.value = null
    }

    fun launchFailed(e: Exception) {
        _launch.value = null
        finishRun()
        _step.value = PluginStep.Failed(tr(R.string.plugin_open_failed, e.message ?: e.javaClass.simpleName))
    }

    fun onResult(resultCode: Int, data: Intent?) {
        val plugin = (_step.value as? PluginStep.Running)?.plugin
        finishRun()
        if (plugin == null) return
        val app = getApplication<Application>()
        val outcome = PluginProtocol.interpret(Plugins.reply(resultCode, data)) { Plugins.ownsAuthority(app, it) }
        viewModelScope.launch {
            _step.value = try {
                when (outcome) {
                    PluginOutcome.Cancelled -> PluginStep.Idle
                    is PluginOutcome.Refused -> PluginStep.Failed(outcome.reason)
                    is PluginOutcome.Message -> PluginStep.Said(plugin, outcome.text)
                    is PluginOutcome.Text -> {
                        val file = withContext(Dispatchers.IO) { share.receiveText(outcome.text, outcome.name ?: fileName) }
                        PluginStep.Ready(plugin, file, outcome.name, outcome.message, outcome.text.take(PREVIEW))
                    }
                    is PluginOutcome.Content -> {
                        val file = withContext(Dispatchers.IO) { share.receive(Uri.parse(outcome.uri), outcome.name ?: fileName) }
                        PluginStep.Ready(plugin, file, outcome.name, outcome.message, withContext(Dispatchers.IO) { textPreview(file) })
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PluginStep.Failed(tr(R.string.plugin_take_failed, e.message ?: e.javaClass.simpleName))
            }
        }
    }

    fun dismiss() {
        (_step.value as? PluginStep.Ready)?.let { r -> viewModelScope.launch(Dispatchers.IO) { share.discard(r.file) } }
        _step.value = PluginStep.Idle
    }

    fun handOff(): PluginStep.Ready? {
        val ready = _step.value as? PluginStep.Ready ?: return null
        _step.value = PluginStep.Idle
        return ready
    }

    fun discard(file: File) {
        viewModelScope.launch(Dispatchers.IO) { share.discard(file) }
    }

    private fun finishRun() {
        staged?.let { s -> staged = null; share.release(s) }
    }

    override fun onCleared() {
        finishRun()
    }

    companion object {
        const val PREVIEW = 4_000

        fun mimeOf(name: String): String =
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase(Locale.ROOT))
                ?: "application/octet-stream"

        fun textPreview(file: File): String? {
            val head = file.inputStream().use { input -> ByteArray(PREVIEW).let { b -> b.copyOf(input.read(b).coerceAtLeast(0)) } }
            if (head.any { it == 0.toByte() }) return null
            return String(head, Charsets.UTF_8).trimEnd('�')
        }
    }
}
