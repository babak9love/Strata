package dev.cluvex.strata.feature.apk

import dev.cluvex.strata.ui.isolated
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import dev.cluvex.strata.R
import dev.cluvex.strata.core.apk.KeyIdentity
import dev.cluvex.strata.core.apk.KeystoreSpec
import dev.cluvex.strata.core.apk.SavedKeys
import dev.cluvex.strata.core.apk.SignOptions
import dev.cluvex.strata.core.model.FileItem
import dev.cluvex.strata.feature.explorer.components.PasswordField
import dev.cluvex.strata.feature.keys.commonName
import dev.cluvex.strata.feature.keys.keyDescription
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface SignRequest {
    val options: SignOptions
    fun wipe()

    data class Quick(override val options: SignOptions) : SignRequest {
        override fun wipe() {}
    }

    data class SavedKey(val id: String, override val options: SignOptions) : SignRequest {
        override fun wipe() {}
    }

    data class NewKey(
        val identity: KeyIdentity,
        val alias: String,
        val storePassword: CharArray,
        val keyPassword: CharArray,
        override val options: SignOptions,
        val jksCopy: Boolean = true,
    ) : SignRequest {
        override fun wipe() { storePassword.fill('\u0000'); keyPassword.fill('\u0000') }
    }

    data class ExistingKeystore(val spec: KeystoreSpec, override val options: SignOptions) : SignRequest {
        override fun wipe() { spec.storePassword.fill('\u0000'); spec.keyPassword.fill('\u0000') }
    }
}

private enum class SignMode { QUICK, SAVED, CUSTOM, KEYSTORE }

@Composable
fun SignApkDialog(item: FileItem, onConfirm: (SignRequest) -> Unit, onDismiss: () -> Unit) {
    var mode by remember { mutableStateOf(SignMode.QUICK) }
    var name by remember { mutableStateOf("") }
    var alias by remember { mutableStateOf("key") }
    var password by remember { mutableStateOf("") }
    var jksCopy by remember { mutableStateOf(true) }
    val context = LocalContext.current
    val saved by produceState<List<SavedKeys.Saved>?>(null) {
        value = withContext(Dispatchers.IO) { runCatching { SavedKeys(File(context.filesDir, "keystores")).list() }.getOrDefault(emptyList()) }
    }
    var savedId by remember { mutableStateOf<String?>(null) }
    var ksPath by remember { mutableStateOf("") }
    var ksStorePw by remember { mutableStateOf("") }
    var ksAlias by remember { mutableStateOf("") }
    var ksKeyPw by remember { mutableStateOf("") }
    var v1 by remember { mutableStateOf(true) }
    var v2 by remember { mutableStateOf(true) }
    var v3 by remember { mutableStateOf(true) }

    val valid = when (mode) {
        SignMode.QUICK -> true
        SignMode.SAVED -> savedId != null
        SignMode.CUSTOM -> name.isNotBlank() && alias.isNotBlank() && password.isNotEmpty()
        SignMode.KEYSTORE -> ksPath.isNotBlank() && ksStorePw.isNotEmpty()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.common_sign_apk)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(isolated(item.name), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = mode == SignMode.QUICK, onClick = { mode = SignMode.QUICK }, label = { Text(stringResource(R.string.apk_sign_quick)) })
                    FilterChip(selected = mode == SignMode.SAVED, onClick = { mode = SignMode.SAVED }, label = { Text(stringResource(R.string.apk_sign_saved)) })
                    FilterChip(selected = mode == SignMode.CUSTOM, onClick = { mode = SignMode.CUSTOM }, label = { Text(stringResource(R.string.apk_sign_custom)) })
                    FilterChip(selected = mode == SignMode.KEYSTORE, onClick = { mode = SignMode.KEYSTORE }, label = { Text(stringResource(R.string.apk_sign_keystore)) })
                }
                when (mode) {
                    SignMode.QUICK -> Text(
                        stringResource(R.string.apk_sign_quick_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SignMode.SAVED -> {
                        val keys = saved.orEmpty().filterNot { it.isDefault }
                        if (saved != null && keys.isEmpty()) {
                            Text(
                                stringResource(R.string.apk_sign_no_saved),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        for (k in keys) {
                            Row(
                                Modifier.fillMaxWidth().selectable(selected = savedId == k.id, role = Role.RadioButton) { savedId = k.id },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = savedId == k.id, onClick = null)
                                Column(Modifier.padding(start = 8.dp)) {
                                    Text(k.name)
                                    Text(
                                        "${commonName(k.certificate)} · ${keyDescription(k.certificate)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    SignMode.CUSTOM -> {
                        OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.keys_name_cn)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(
                            alias,
                            { alias = it },
                            label = { Text(stringResource(R.string.keys_alias)) },
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        PasswordField(password, { password = it }, stringResource(R.string.common_password))
                        Row(Modifier.fillMaxWidth().toggleable(jksCopy, onValueChange = { jksCopy = it }), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = jksCopy, onCheckedChange = null)
                            Text(stringResource(R.string.apk_sign_save_jks, alias.trim().ifEmpty { "key" }), modifier = Modifier.padding(start = 8.dp))
                        }
                        Text(
                            stringResource(R.string.apk_sign_custom_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    SignMode.KEYSTORE -> {
                        OutlinedTextField(
                            ksPath,
                            { ksPath = it },
                            label = { Text(stringResource(R.string.apk_sign_keystore_path)) },
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        PasswordField(ksStorePw, { ksStorePw = it }, stringResource(R.string.apk_sign_store_password))
                        OutlinedTextField(
                            ksAlias,
                            { ksAlias = it },
                            label = { Text(stringResource(R.string.apk_sign_alias_blank)) },
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        PasswordField(ksKeyPw, { ksKeyPw = it }, stringResource(R.string.apk_sign_key_password_blank))
                    }
                }
                Text(stringResource(R.string.apk_sign_schemes), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                SchemeRow("v1 (JAR)", v1) { v1 = it }
                SchemeRow(stringResource(R.string.apk_scheme_v2), v2) { v2 = it }
                SchemeRow(stringResource(R.string.apk_scheme_v3), v3) { v3 = it }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid && (v1 || v2 || v3),
                onClick = {
                    val options = SignOptions(v1 = v1, v2 = v2, v3 = v3)
                    val request = when (mode) {
                        SignMode.QUICK -> SignRequest.Quick(options)
                        SignMode.SAVED -> SignRequest.SavedKey(savedId!!, options)
                        SignMode.CUSTOM -> SignRequest.NewKey(
                            identity = KeyIdentity(commonName = name.trim()),
                            alias = alias.trim(),
                            storePassword = password.toCharArray(),
                            keyPassword = password.toCharArray(),
                            options = options,
                            jksCopy = jksCopy,
                        )
                        SignMode.KEYSTORE -> SignRequest.ExistingKeystore(
                            spec = KeystoreSpec(
                                file = File(ksPath.trim()),
                                storePassword = ksStorePw.toCharArray(),
                                alias = ksAlias.trim(),
                                keyPassword = ksKeyPw.ifEmpty { ksStorePw }.toCharArray(),
                                type = keystoreType(ksPath),
                            ),
                            options = options,
                        )
                    }
                    onConfirm(request)
                },
            ) { Text(stringResource(R.string.apk_sign_button)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun SchemeRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, onValueChange = onChange).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, modifier = Modifier.padding(start = 8.dp))
    }
}

private fun keystoreType(path: String): String = when (path.substringAfterLast('.').lowercase()) {
    "p12", "pfx" -> "PKCS12"
    "bks" -> "BKS"
    else -> "JKS"
}
