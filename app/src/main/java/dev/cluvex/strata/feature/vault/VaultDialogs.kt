package dev.cluvex.strata.feature.vault

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.selection.toggleable
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.sound.UiSound
import dev.cluvex.strata.core.sound.UiSounds
import dev.cluvex.strata.core.vault.Vault
import dev.cluvex.strata.core.vault.WrongVaultPasswordException
import dev.cluvex.strata.feature.explorer.components.PasswordField
import dev.cluvex.strata.ui.expressive.ArtTone
import dev.cluvex.strata.ui.expressive.ExpressiveDialog
import dev.cluvex.strata.ui.expressive.HeroShapes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Busy() {
    LoadingIndicator(Modifier.size(36.dp))
}

@Composable
fun VaultSetupDialog(onDismiss: () -> Unit, onReady: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current as? FragmentActivity
    val scope = rememberCoroutineScope()
    val biometric = remember { VaultAccess.biometricReady(context) }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var useBiometric by remember { mutableStateOf(biometric) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val short = password.isNotEmpty() && password.length < VaultAccess.MIN_PASSWORD
    val mismatch = confirm.isNotEmpty() && confirm != password
    val ready = password.length >= VaultAccess.MIN_PASSWORD && confirm == password && !busy
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun create() {
        if (!ready) return
        busy = true
        error = null
        scope.launch {
            val result = runCatching { VaultAccess.create(password.toCharArray()) }
            result.onFailure {
                busy = false
                error = it.message ?: it.javaClass.simpleName
            }
            result.onSuccess {
                UiSounds.play(UiSound.LOCK)
                if (useBiometric && activity != null) {
                    VaultAccess.enableBiometric(activity) {
                        busy = false
                        onReady()
                    }
                } else {
                    busy = false
                    onReady()
                }
            }
        }
    }

    ExpressiveDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = stringResource(R.string.vault_setup_title),
        icon = Icons.Rounded.Lock,
        tone = ArtTone.TERTIARY,
        shapes = HeroShapes.calm,
        modifier = Modifier.testTag("vault-setup"),
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.vault_setup_body))
                PasswordField(
                    value = password,
                    onValueChange = { password = it },
                    label = stringResource(R.string.vault_password),
                    enabled = !busy,
                    isError = short,
                    supporting = if (short) stringResource(R.string.vault_password_short, VaultAccess.MIN_PASSWORD) else null,
                    focus = focus,
                )
                PasswordField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = stringResource(R.string.vault_password_again),
                    enabled = !busy,
                    isError = mismatch,
                    supporting = if (mismatch) stringResource(R.string.vault_password_mismatch) else null,
                    onDone = ::create,
                )
                if (biometric) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .toggleable(useBiometric, enabled = !busy, role = Role.Switch) { useBiometric = it }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Rounded.Fingerprint, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                        Text(stringResource(R.string.vault_use_biometric), Modifier.weight(1f))
                        Switch(checked = useBiometric, onCheckedChange = null, enabled = !busy)
                    }
                }
                Text(
                    stringResource(R.string.vault_setup_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            if (busy) Busy() else Button(onClick = ::create, enabled = ready, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.vault_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
fun VaultUnlockDialog(onDismiss: () -> Unit, onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val activity = LocalActivity.current as? FragmentActivity
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val biometric = remember { Vault.store.hasBiometric && VaultAccess.biometricReady(context) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }

    fun done() {
        UiSounds.play(UiSound.UNLOCK)
        onUnlocked()
    }

    fun fingerprint() {
        val a = activity ?: return
        if (busy) return
        busy = true
        error = null
        VaultAccess.unlockWithBiometric(a, scope) { outcome ->
            busy = false
            when (outcome) {
                VaultAccess.Outcome.Done -> done()
                VaultAccess.Outcome.UsePassword, VaultAccess.Outcome.Canceled -> runCatching { focus.requestFocus() }
                is VaultAccess.Outcome.Error -> error = outcome.message
            }
        }
    }

    fun withPassword() {
        if (password.isEmpty() || busy) return
        busy = true
        error = null
        scope.launch {
            val result = runCatching { VaultAccess.unlock(password.toCharArray()) }
            busy = false
            result.onSuccess { done() }.onFailure { e ->
                error = if (e is WrongVaultPasswordException) tr(R.string.vault_err_wrong_password) else e.message ?: e.javaClass.simpleName
                UiSounds.play(UiSound.ERROR)
            }
        }
    }

    LaunchedEffect(Unit) {
        if (biometric) {
            lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
            fingerprint()
        } else {
            runCatching { focus.requestFocus() }
        }
    }

    ExpressiveDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = stringResource(R.string.vault_unlock_title),
        icon = Icons.Rounded.LockOpen,
        tone = ArtTone.TERTIARY,
        shapes = HeroShapes.calm,
        modifier = Modifier.testTag("vault-unlock"),
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(if (biometric) R.string.vault_unlock_body_biometric else R.string.vault_unlock_body))
                PasswordField(
                    value = password,
                    onValueChange = {
                        password = it
                        error = null
                    },
                    label = stringResource(R.string.vault_password),
                    enabled = !busy,
                    isError = error != null,
                    supporting = error,
                    onDone = ::withPassword,
                    focus = focus,
                )
                if (biometric) {
                    FilledTonalButton(onClick = ::fingerprint, enabled = !busy, shapes = ButtonDefaults.shapes(), modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Fingerprint, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Text(stringResource(R.string.vault_use_fingerprint), Modifier.padding(start = ButtonDefaults.IconSpacing))
                    }
                }
            }
        },
        confirmButton = {
            if (busy) Busy() else Button(onClick = ::withPassword, enabled = password.isNotEmpty(), shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.vault_unlock))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
fun VaultPasswordDialog(onDismiss: () -> Unit, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var old by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val short = password.isNotEmpty() && password.length < VaultAccess.MIN_PASSWORD
    val mismatch = confirm.isNotEmpty() && confirm != password
    val ready = old.isNotEmpty() && password.length >= VaultAccess.MIN_PASSWORD && confirm == password && !busy

    fun change() {
        if (!ready) return
        busy = true
        error = null
        scope.launch {
            val result = runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Vault.store.changePassword(old.toCharArray(), password.toCharArray()) }
            }
            busy = false
            result.onSuccess { onChanged() }.onFailure { e ->
                error = if (e is WrongVaultPasswordException) tr(R.string.vault_err_wrong_password) else e.message ?: e.javaClass.simpleName
            }
        }
    }

    ExpressiveDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = stringResource(R.string.vault_change_password),
        icon = Icons.Rounded.Key,
        tone = ArtTone.TERTIARY,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PasswordField(old, { old = it; error = null }, stringResource(R.string.vault_password_current), enabled = !busy, isError = error != null, supporting = error)
                PasswordField(
                    password, { password = it }, stringResource(R.string.vault_password_new), enabled = !busy, isError = short,
                    supporting = if (short) stringResource(R.string.vault_password_short, VaultAccess.MIN_PASSWORD) else null,
                )
                PasswordField(
                    confirm, { confirm = it }, stringResource(R.string.vault_password_again), enabled = !busy, isError = mismatch,
                    supporting = if (mismatch) stringResource(R.string.vault_password_mismatch) else null, onDone = ::change,
                )
            }
        },
        confirmButton = {
            if (busy) Busy() else Button(onClick = ::change, enabled = ready, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
fun VaultDeleteDialog(onDismiss: () -> Unit, onDeleted: () -> Unit) {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun delete() {
        if (password.isEmpty() || busy) return
        busy = true
        error = null
        scope.launch {
            val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Vault.store.checkPassword(password.toCharArray()) }
            if (!ok) {
                busy = false
                error = tr(R.string.vault_err_wrong_password)
                return@launch
            }
            runCatching { VaultAccess.destroy() }
            busy = false
            UiSounds.play(UiSound.TRASH)
            onDeleted()
        }
    }

    ExpressiveDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = stringResource(R.string.vault_delete_title),
        icon = Icons.Rounded.DeleteForever,
        tone = ArtTone.ERROR,
        shapes = HeroShapes.danger,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.vault_delete_body))
                PasswordField(password, { password = it; error = null }, stringResource(R.string.vault_password), enabled = !busy, isError = error != null, supporting = error, onDone = ::delete)
            }
        },
        confirmButton = {
            if (busy) Busy() else Button(
                onClick = ::delete,
                enabled = password.isNotEmpty(),
                shapes = ButtonDefaults.shapes(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError),
            ) { Text(stringResource(R.string.vault_delete_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.common_cancel)) } },
    )
}
