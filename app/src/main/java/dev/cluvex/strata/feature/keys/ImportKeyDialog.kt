package dev.cluvex.strata.feature.keys

import dev.cluvex.strata.ui.isolated
import dev.cluvex.strata.core.i18n.separator
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.cluvex.strata.R
import dev.cluvex.strata.core.apk.KeystoreFile
import dev.cluvex.strata.core.apk.OpenedKeystore
import dev.cluvex.strata.core.apk.SavedKeys
import dev.cluvex.strata.core.apk.SigningKey
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.util.Formatters
import dev.cluvex.strata.feature.explorer.components.PasswordField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object KeyFiles {
    private val keystores = setOf("jks", "keystore", "bks", "p12", "pfx")

    fun isKeyFile(name: String): Boolean {
        val n = name.lowercase()
        return n.substringAfterLast('.') in keystores || n.endsWith(".pk8") || n.endsWith(".x509.pem")
    }

    fun isPk8(name: String) = name.lowercase().let { it.endsWith(".pk8") || it.endsWith(".x509.pem") }
}

@Composable
fun ImportKeyDialog(path: String, onDone: (message: String?) -> Unit) {
    val file = remember(path) { File(path) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pk8 = remember(path) { KeyFiles.isPk8(file.name) }
    val pair = remember(path) { if (pk8) KeystoreFile.pk8Pair(file) else null }

    var storePw by remember { mutableStateOf("") }
    var opened by remember { mutableStateOf<OpenedKeystore?>(null) }
    var pk8Key by remember { mutableStateOf<SigningKey?>(null) }
    var needsPk8Password by remember { mutableStateOf(false) }
    var alias by remember { mutableStateOf<String?>(null) }
    var samePassword by remember { mutableStateOf(true) }
    var keyPw by remember { mutableStateOf("") }
    var name by remember { mutableStateOf(file.name.substringBefore('.')) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun open() {
        busy = true
        error = null
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    if (pk8) {
                        val (k, c) = pair ?: error(tr(R.string.keys_pk8_pair_missing, file.name.substringBefore('.')))
                        KeystoreFile.fromPk8(k.readBytes(), c.readBytes(), storePw.takeIf { it.isNotEmpty() }?.toCharArray())
                    } else {
                        KeystoreFile.open(file, storePw.toCharArray())
                    }
                }
            }
            busy = false
            result.onSuccess { r ->
                when (r) {
                    is SigningKey -> pk8Key = r
                    is OpenedKeystore -> {
                        opened = r
                        alias = r.keyAliases.firstOrNull()
                        if (r.keyAliases.size == 1) name = r.keyAliases.first()
                    }
                }
            }.onFailure { e ->
                if (e is KeystoreFile.NeedsPasswordException) needsPk8Password = true
                error = e.message ?: e.javaClass.simpleName
            }
        }
    }
    LaunchedEffect(path) { if (pk8) open() }

    fun import() {
        busy = true
        error = null
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val key = pk8Key ?: opened!!.key(alias!!, (if (samePassword) storePw else keyPw).toCharArray())
                    SavedKeys(File(context.filesDir, "keystores")).save(name.trim(), key)
                }
            }
            busy = false
            result.onSuccess { onDone(tr(R.string.keys_imported, it.name)) }.onFailure { error = it.message ?: it.javaClass.simpleName }
        }
    }

    val ready = (pk8Key != null || (opened != null && alias != null)) && name.isNotBlank()
    AlertDialog(
        onDismissRequest = { onDone(null) },
        properties = DialogProperties(dismissOnClickOutside = false),
        modifier = Modifier.testTag("import-key"),
        title = { Text(stringResource(R.string.keys_import_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(isolated(file.name), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                val store = opened
                val key = pk8Key
                when {
                    key != null -> {
                        Text("${commonName(key.certificates.first())} · ${keyDescription(key.certificates.first())}", style = MaterialTheme.typography.titleSmall)
                        CertificateDetails(key.certificates.first())
                    }
                    store == null -> {
                        if (!pk8 || needsPk8Password) {
                            PasswordField(storePw, { storePw = it; error = null }, stringResource(if (pk8) R.string.keys_key_password else R.string.keys_store_password))
                        }
                    }
                    else -> {
                        Text("${store.format.label} · " + pluralStringResource(R.plurals.keys_entries, store.entries.size, store.entries.size), style = MaterialTheme.typography.labelLarge)
                        for (e in store.entries) {
                            val cert = e.certificate
                            Row(
                                Modifier.fillMaxWidth().selectable(selected = alias == e.alias, enabled = e.isKey, role = Role.RadioButton) { alias = e.alias; name = e.alias },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = alias == e.alias, onClick = null, enabled = e.isKey)
                                Column(Modifier.padding(start = 8.dp)) {
                                    Text(e.alias, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        if (cert == null) stringResource(R.string.keys_no_certificate) else listOf(
                                            stringResource(if (e.isKey) R.string.keys_entry_key else R.string.keys_entry_trusted),
                                            commonName(cert),
                                            keyDescription(cert),
                                            stringResource(R.string.keys_until, Formatters.date(cert.notAfter.time)),
                                        ).joinToString(separator()),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        store.entries.firstOrNull { it.alias == alias }?.certificate?.let { cert ->
                            HorizontalDivider()
                            CertificateDetails(cert)
                            HorizontalDivider()
                            Row(Modifier.fillMaxWidth().toggleable(samePassword, onValueChange = { samePassword = it }), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(checked = samePassword, onCheckedChange = null)
                                Text(stringResource(R.string.keys_same_as_store), modifier = Modifier.padding(start = 8.dp))
                            }
                            if (!samePassword) PasswordField(keyPw, { keyPw = it; error = null }, stringResource(R.string.keys_key_password))
                        }
                    }
                }
                if (key != null || (store != null && alias != null)) {
                    OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.keys_name_in_vault)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            if (pk8Key == null && opened == null) {
                TextButton(enabled = !busy && (storePw.isNotEmpty() || pk8), onClick = ::open) { Text(stringResource(R.string.common_open)) }
            } else {
                TextButton(enabled = !busy && ready, onClick = ::import) { Text(stringResource(R.string.keys_import)) }
            }
        },
        dismissButton = { TextButton(onClick = { onDone(null) }) { Text(stringResource(R.string.common_cancel)) } },
    )
}
