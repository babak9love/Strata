package dev.cluvex.strata.feature.plugin

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.cluvex.strata.R
import dev.cluvex.strata.core.data.SettingsRepository
import dev.cluvex.strata.core.plugin.PluginInfo
import dev.cluvex.strata.core.plugin.PluginPins
import dev.cluvex.strata.core.plugin.PluginProtocol
import dev.cluvex.strata.core.plugin.PluginTrust
import dev.cluvex.strata.core.plugin.Plugins
import dev.cluvex.strata.core.plugin.TextApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PluginsViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = SettingsRepository(app)

    private val _installed = MutableStateFlow<List<PluginInfo>>(emptyList())
    val installed: StateFlow<List<PluginInfo>> = _installed.asStateFlow()

    private val _textApps = MutableStateFlow<List<TextApp>>(emptyList())
    val textApps: StateFlow<List<TextApp>> = _textApps.asStateFlow()

    val pins: StateFlow<PluginPins> = settings.pluginPins.stateIn(viewModelScope, SharingStarted.Eagerly, PluginPins.EMPTY)

    /** Plugins are separate apps: look again whenever the screen comes back (one may have been installed meanwhile). */
    fun refresh() {
        viewModelScope.launch {
            val ctx = getApplication<Application>()
            val (plugins, text) = withContext(Dispatchers.IO) { Plugins.discover(ctx) to Plugins.textApps(ctx) }
            _installed.value = plugins
            _textApps.value = text
        }
    }

    fun forget(packageName: String) {
        viewModelScope.launch { settings.forgetPlugin(packageName) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginsScreen(onBack: () -> Unit, vm: PluginsViewModel = viewModel()) {
    BackHandler(onBack = onBack)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.refresh() }
    val installed by vm.installed.collectAsStateWithLifecycle()
    val textApps by vm.textApps.collectAsStateWithLifecycle()
    val pins by vm.pins.collectAsStateWithLifecycle()
    val missing = pins.packages - installed.map { it.packageName }.toSet()

    Scaffold(
        modifier = Modifier.testTag("plugins-screen"),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.plugin_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back)) } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Heading(stringResource(R.string.plugin_files_title), stringResource(R.string.plugin_files_body)) }

            if (installed.isEmpty()) {
                item { Text(stringResource(R.string.plugin_none_installed), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(installed, key = { it.packageName + "/" + it.activityName }) { plugin ->
                PluginCard(plugin, pins.trust(plugin), onForget = { vm.forget(plugin.packageName) }, pinned = pins.pinned(plugin.packageName) != null)
            }

            items(missing.sorted(), key = { "missing/$it" }) { pkg ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(pkg, style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.plugin_trusted_not_installed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { vm.forget(pkg) }) { Text(stringResource(R.string.plugin_forget)) }
                    }
                }
            }

            item { Heading(stringResource(R.string.plugin_text_title), stringResource(R.string.plugin_text_body)) }
            if (textApps.isEmpty()) {
                item { Text(stringResource(R.string.plugin_no_text_apps), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(textApps, key = { "text/" + it.packageName + "/" + it.activityName }) { app ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(app.label, style = MaterialTheme.typography.titleSmall)
                        Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            item { Heading(stringResource(R.string.plugin_writing_title), stringResource(R.string.plugin_writing_body, PluginProtocol.ACTION_FILE)) }
        }
    }
}

@Composable
private fun Heading(title: String, body: String) {
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun PluginCard(plugin: PluginInfo, trust: PluginTrust, pinned: Boolean, onForget: () -> Unit) {
    Card(Modifier.fillMaxWidth().testTag("plugin-" + plugin.packageName)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(plugin.label, style = MaterialTheme.typography.titleSmall)
            Text(plugin.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            plugin.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Text(
                plugin.extensions?.sorted()?.joinToString(", ") ?: stringResource(R.string.plugin_any_file),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (plugin.api > PluginProtocol.API) {
                Text(stringResource(R.string.plugin_newer_api, plugin.api), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Text(
                stringResource(
                    when (trust) {
                        PluginTrust.NEW -> R.string.plugin_status_new
                        PluginTrust.TRUSTED -> R.string.plugin_status_trusted
                        PluginTrust.CHANGED -> R.string.plugin_status_changed
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (trust == PluginTrust.CHANGED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(
                    stringResource(R.string.plugin_signed_by) + "\n" + plugin.signer.substringBefore(',').take(32) + "…",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (pinned) TextButton(onClick = onForget) { Text(stringResource(R.string.plugin_forget_trust)) }
            }
        }
    }
}
