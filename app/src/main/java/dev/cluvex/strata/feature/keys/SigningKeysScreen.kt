package dev.cluvex.strata.feature.keys

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.cluvex.strata.R
import dev.cluvex.strata.core.apk.KeyIdentity
import dev.cluvex.strata.core.apk.KeystoreFile
import dev.cluvex.strata.core.apk.KeystoreFormat
import dev.cluvex.strata.core.apk.SavedKeys
import dev.cluvex.strata.core.util.Formatters
import dev.cluvex.strata.feature.explorer.components.PasswordField
import java.io.File

private const val PK8_PAIR = "pk8 + x509.pem"

private const val MIN_PASSWORD = 6

private sealed interface KeysDialog {
    data object Generate : KeysDialog
    data class Export(val key: SavedKeys.Saved, val format: KeystoreFormat) : KeysDialog
    data class Rename(val key: SavedKeys.Saved) : KeysDialog
    data class Delete(val key: SavedKeys.Saved) : KeysDialog
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SigningKeysScreen(
    exportDir: String,
    onBack: () -> Unit,
    onLocate: (folder: String, reveal: String?) -> Unit,
    vm: SigningKeysViewModel = viewModel(key = "signing-keys"),
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var sheet by remember { mutableStateOf<SavedKeys.Saved?>(null) }
    var dialog by remember { mutableStateOf<KeysDialog?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importFrom(uri) }
    val showLabel = stringResource(R.string.common_show)
    BackHandler(onBack = onBack)

    LaunchedEffect(s.message) {
        val m = s.message ?: return@LaunchedEffect
        val created = s.created
        val r = snackbar.showSnackbar(m, actionLabel = if (created != null) showLabel else null, duration = SnackbarDuration.Long)
        vm.consumeMessage()
        if (r == SnackbarResult.ActionPerformed && created != null) onLocate(File(created).parent ?: "/", created)
    }

    Scaffold(
        modifier = Modifier.testTag("signing-keys"),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.keys_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back)) } },
                actions = {
                    IconButton(onClick = { pick.launch(arrayOf("*/*")) }) { Icon(Icons.Rounded.FileOpen, contentDescription = stringResource(R.string.keys_import_keystore)) }
                    IconButton(onClick = { dialog = KeysDialog.Generate }) { Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.keys_generate_key)) }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (s.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { LoadingIndicator() }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    Text(
                        stringResource(R.string.keys_intro, exportDir),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    )
                }
                items(s.keys, key = { it.id }) { k ->
                    ListItem(
                        content = { Text(k.name) },
                        supportingContent = {
                            Text(
                                "${commonName(k.certificate)} · ${keyDescription(k.certificate)} · " +
                                    stringResource(R.string.keys_until, Formatters.date(k.certificate.notAfter.time)),
                            )
                        },
                        leadingContent = { Icon(Icons.Rounded.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = if (k.isDefault) ({ AssistChip(onClick = { sheet = k }, label = { Text(stringResource(R.string.apk_sign_quick)) }) }) else null,
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                        modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable { sheet = k }.testTag("key-${k.name}"),
                    )
                }
            }
        }
    }

    sheet?.let { k ->
        ModalBottomSheet(onDismissRequest = { sheet = null }, modifier = Modifier.testTag("key-sheet")) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(k.name, style = MaterialTheme.typography.titleLarge)
                CertificateDetails(k.certificate)
                HorizontalDivider()
                Text(stringResource(R.string.keys_export_to, exportDir), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (f in KeystoreFormat.entries) FilterChip(selected = false, onClick = { sheet = null; dialog = KeysDialog.Export(k, f) }, label = { Text(".${f.extension}") })
                    FilterChip(selected = false, onClick = { sheet = null; vm.exportPk8(k.id, exportDir) }, label = { Text(PK8_PAIR) })
                }
                Text(
                    stringResource(R.string.keys_pk8_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!k.isDefault) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { sheet = null; dialog = KeysDialog.Rename(k) }) { Text(stringResource(R.string.common_rename)) }
                        TextButton(onClick = { sheet = null; dialog = KeysDialog.Delete(k) }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }

    when (val d = dialog) {
        null -> Unit
        KeysDialog.Generate -> GenerateKeyDialog(onGenerate = { name, id -> dialog = null; vm.generate(name, id) }, onDismiss = { dialog = null })
        is KeysDialog.Export -> ExportKeyDialog(d.key, d.format, onExport = { alias, sp, kp -> dialog = null; vm.export(d.key.id, d.format, alias, sp, kp, exportDir) }, onDismiss = { dialog = null })
        is KeysDialog.Rename -> {
            var name by remember { mutableStateOf(d.key.name) }
            AlertDialog(
                onDismissRequest = { dialog = null },
                title = { Text(stringResource(R.string.keys_rename_title)) },
                text = { OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
                confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { dialog = null; vm.rename(d.key.id, name) }) { Text(stringResource(R.string.common_rename)) } },
                dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.common_cancel)) } },
            )
        }
        is KeysDialog.Delete -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text(stringResource(R.string.keys_delete_title, d.key.name)) },
            text = { Text(stringResource(R.string.keys_delete_body)) },
            confirmButton = { TextButton(onClick = { dialog = null; vm.delete(d.key.id) }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { dialog = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    s.importing?.let { path -> ImportKeyDialog(path, onDone = vm::importDone) }
}

@Composable
private fun GenerateKeyDialog(onGenerate: (name: String, identity: KeyIdentity) -> Unit, onDismiss: () -> Unit) {
    var cn by remember { mutableStateOf("") }
    var org by remember { mutableStateOf("") }
    var years by remember { mutableStateOf("30") }
    var algorithm by remember { mutableStateOf("RSA 2048") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.keys_generate_key)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(cn, { cn = it }, label = { Text(stringResource(R.string.keys_name_cn)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("gen-cn"))
                OutlinedTextField(org, { org = it }, label = { Text(stringResource(R.string.keys_organization)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(years, { years = it.filter(Char::isDigit).take(3) }, label = { Text(stringResource(R.string.keys_valid_years)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (a in listOf("RSA 2048", "RSA 4096", "EC P-256")) FilterChip(selected = algorithm == a, onClick = { algorithm = a }, label = { Text(a) })
                }
            }
        },
        confirmButton = {
            TextButton(enabled = cn.isNotBlank() && (years.toIntOrNull() ?: 0) > 0, onClick = {
                val (alg, bits) = when (algorithm) {
                    "RSA 4096" -> "RSA" to 4096
                    "EC P-256" -> "EC" to 256
                    else -> "RSA" to 2048
                }
                onGenerate(cn.trim(), KeyIdentity(commonName = cn.trim(), organization = org.trim(), validityYears = years.toInt(), keyAlgorithm = alg, keySizeBits = bits))
            }) { Text(stringResource(R.string.keys_generate)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun ExportKeyDialog(
    key: SavedKeys.Saved,
    format: KeystoreFormat,
    onExport: (alias: String, storePassword: CharArray, keyPassword: CharArray) -> Unit,
    onDismiss: () -> Unit,
) {
    var alias by remember { mutableStateOf(key.name.lowercase().replace(Regex("[^a-z0-9_.-]"), "-").trim('-').ifEmpty { "key" }) }
    var pw by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var same by remember { mutableStateOf(true) }
    var keyPw by remember { mutableStateOf("") }
    val problem = when {
        pw.isNotEmpty() && pw.length < MIN_PASSWORD -> stringResource(R.string.keys_pw_min, MIN_PASSWORD)
        confirm.isNotEmpty() && confirm != pw -> stringResource(R.string.keys_pw_mismatch)
        !same && keyPw.isNotEmpty() && keyPw.length < MIN_PASSWORD -> stringResource(R.string.keys_key_pw_min, MIN_PASSWORD)
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.keys_export_as, format.label)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    alias,
                    { alias = it.trim() },
                    label = { Text(stringResource(R.string.keys_alias)) },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                    modifier = Modifier.fillMaxWidth(),
                )
                PasswordField(pw, { pw = it }, stringResource(R.string.keys_store_password))
                PasswordField(confirm, { confirm = it }, stringResource(R.string.keys_repeat_password))
                if (format != KeystoreFormat.PKCS12) {
                    Row(Modifier.fillMaxWidth().toggleable(same, onValueChange = { same = it }), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = same, onCheckedChange = null)
                        Text(stringResource(R.string.keys_same_password), modifier = Modifier.padding(start = 8.dp))
                    }
                    if (!same) PasswordField(keyPw, { keyPw = it }, stringResource(R.string.keys_key_password))
                }
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Text(
                    "SHA-256 " + KeystoreFile.fingerprint(key.certificate).take(23) + "…",
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = alias.isNotBlank() && pw.length >= MIN_PASSWORD && confirm == pw && (same || format == KeystoreFormat.PKCS12 || keyPw.length >= MIN_PASSWORD),
                onClick = {
                    val keyPassword = if (same || format == KeystoreFormat.PKCS12) pw else keyPw
                    onExport(alias, pw.toCharArray(), keyPassword.toCharArray())
                },
            ) { Text(stringResource(R.string.keys_export)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
