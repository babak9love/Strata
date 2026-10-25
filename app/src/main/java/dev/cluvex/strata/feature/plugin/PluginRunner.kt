package dev.cluvex.strata.feature.plugin

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.cluvex.strata.R
import dev.cluvex.strata.core.plugin.PluginInfo
import dev.cluvex.strata.core.plugin.PluginTrust
import dev.cluvex.strata.core.util.Formatters
import dev.cluvex.strata.ui.rememberTextClipboard

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PluginRunner(vm: PluginRunViewModel, onSave: (PluginStep.Ready, replace: Boolean) -> Unit) {
    val step by vm.step.collectAsStateWithLifecycle()
    val pending by vm.launch.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r -> vm.onResult(r.resultCode, r.data) }
    LaunchedEffect(pending) {
        val intent = pending ?: return@LaunchedEffect
        try {
            launcher.launch(intent)
            vm.launched()
        } catch (e: ActivityNotFoundException) {
            vm.launchFailed(e)
        } catch (e: SecurityException) {
            vm.launchFailed(e)
        }
    }
    val clipboard = rememberTextClipboard()

    when (val s = step) {
        PluginStep.Idle -> Unit
        is PluginStep.Choose -> ModalBottomSheet(onDismissRequest = vm::dismiss) {
            Column(Modifier.padding(bottom = 24.dp).testTag("plugin-choose")) {
                Text(stringResource(R.string.plugin_for, s.name), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                HorizontalDivider()
                when {
                    s.plugins == null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
                    s.plugins.isEmpty() -> Text(
                        stringResource(R.string.plugin_none_for_file),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(24.dp),
                    )
                    else -> s.plugins.forEach { p -> PluginRow(p) { vm.choose(p) } }
                }
            }
        }
        is PluginStep.Confirm -> AlertDialog(
            onDismissRequest = vm::dismiss,
            icon = { Icon(Icons.Rounded.Extension, contentDescription = null) },
            title = { Text(if (s.trust == PluginTrust.CHANGED) stringResource(R.string.plugin_changed_title, s.plugin.label) else stringResource(R.string.plugin_run_title, s.plugin.label)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (s.trust == PluginTrust.CHANGED) {
                        Text(
                            stringResource(R.string.plugin_changed_body),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(s.plugin.packageName, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    Fingerprint(stringResource(R.string.plugin_signed_by), s.plugin.signer, "plugin-signer")
                    s.pinned?.takeIf { s.trust == PluginTrust.CHANGED }?.let { Fingerprint(stringResource(R.string.plugin_trusted_before), it, "plugin-pinned") }
                    val itemName = vm.item?.name
                    Text(
                        if (itemName != null) stringResource(R.string.plugin_gets_copy, itemName) else stringResource(R.string.plugin_gets_copy_file),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { vm.trustAndRun(s.plugin) }, modifier = Modifier.testTag("plugin-trust")) { Text(stringResource(R.string.plugin_trust_and_run)) } },
            dismissButton = { TextButton(onClick = vm::dismiss) { Text(stringResource(R.string.common_cancel)) } },
        )
        is PluginStep.Running -> AlertDialog(
            onDismissRequest = {},
            title = { Text(s.plugin.label) },
            text = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) { LoadingIndicator(); Text(stringResource(R.string.plugin_working)) } },
            confirmButton = {},
        )
        is PluginStep.Said -> AlertDialog(
            onDismissRequest = vm::dismiss,
            title = { Text(s.plugin.label) },
            text = { SelectionContainer { Text(s.message, modifier = Modifier.verticalScroll(rememberScrollState()).testTag("plugin-message")) } },
            confirmButton = { TextButton(onClick = vm::dismiss) { Text(stringResource(R.string.common_close)) } },
            dismissButton = { TextButton(onClick = { clipboard.setText(s.message) }) { Text(stringResource(R.string.common_copy)) } },
        )
        is PluginStep.Ready -> ReadyDialog(s, vm, onSave, onCopy = { clipboard.setText(it) })
        is PluginStep.Failed -> AlertDialog(
            onDismissRequest = vm::dismiss,
            title = { Text(stringResource(R.string.plugin_plugin)) },
            text = { Text(s.message, modifier = Modifier.testTag("plugin-failed")) },
            confirmButton = { TextButton(onClick = vm::dismiss) { Text(stringResource(R.string.common_ok)) } },
        )
    }
}

@Composable
private fun PluginRow(p: PluginInfo, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 12.dp).testTag("plugin-${p.packageName}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Extension, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 20.dp))
        Column(Modifier.weight(1f)) {
            Text(p.label, style = MaterialTheme.typography.bodyLarge)
            p.description?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
internal fun Fingerprint(label: String, value: String, tag: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium)
        SelectionContainer {
            Text(value.replace(",", "\n"), style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr), fontFamily = FontFamily.Monospace, modifier = Modifier.testTag(tag))
        }
    }
}

@Composable
private fun ReadyDialog(s: PluginStep.Ready, vm: PluginRunViewModel, onSave: (PluginStep.Ready, Boolean) -> Unit, onCopy: (String) -> Unit) {
    var confirmReplace by remember { mutableStateOf(false) }
    val original = vm.item?.name
    if (confirmReplace) {
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            title = { Text(if (original != null) stringResource(R.string.plugin_replace_title, original) else stringResource(R.string.plugin_replace_title_file)) },
            text = { Text(stringResource(R.string.plugin_replace_body, s.plugin.label)) },
            confirmButton = {
                TextButton(onClick = { vm.handOff()?.let { onSave(it, true) } }, modifier = Modifier.testTag("plugin-replace-confirm")) {
                    Text(stringResource(R.string.common_replace), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = vm::dismiss,
        title = { Text(s.plugin.label) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                s.message?.let { Text(it, modifier = Modifier.testTag("plugin-message")) }
                val resultName = s.name ?: original
                val size = Formatters.size(s.file.length())
                Text(
                    if (resultName != null) stringResource(R.string.plugin_result, resultName, size) else stringResource(R.string.plugin_result_file, size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (s.preview != null) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.small) {
                        Text(
                            s.preview,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()).padding(10.dp).testTag("plugin-preview"),
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { confirmReplace = true }, modifier = Modifier.testTag("plugin-replace")) { Text(stringResource(R.string.plugin_replace_original)) }
                    if (s.preview != null && s.file.length() <= PluginRunViewModel.PREVIEW) TextButton(onClick = { onCopy(s.preview) }) { Text(stringResource(R.string.common_copy)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { vm.handOff()?.let { onSave(it, false) } }, modifier = Modifier.testTag("plugin-save-new")) { Text(stringResource(R.string.plugin_save_new)) } },
        dismissButton = { TextButton(onClick = vm::dismiss) { Text(stringResource(R.string.common_discard)) } },
    )
}
