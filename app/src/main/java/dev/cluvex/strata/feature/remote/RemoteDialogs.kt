package dev.cluvex.strata.feature.remote

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.cluvex.strata.R
import dev.cluvex.strata.core.remote.RemoteConnection
import dev.cluvex.strata.core.sound.UiSound
import dev.cluvex.strata.ui.expressive.ArtTone
import dev.cluvex.strata.ui.expressive.ExpressiveDialog
import dev.cluvex.strata.ui.expressive.HeroShapes

@Composable
fun StoragePasswordDialog(connection: RemoteConnection, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.common_password)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.remote_password_prompt, connection.displayAddress), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    password, { password = it }, label = { Text(stringResource(R.string.common_password)) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(password) }) { Text(stringResource(R.string.remote_connect)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
fun HostKeyChangedDialog(connection: RemoteConnection, old: String, presented: String, onTrust: () -> Unit, onDismiss: () -> Unit) {
    ExpressiveDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = stringResource(R.string.remote_identity_changed),
        icon = Icons.Rounded.GppMaybe,
        tone = ArtTone.ERROR,
        shapes = HeroShapes.alert,
        sound = UiSound.ATTENTION,
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.remote_identity_changed_body, connection.displayAddress))
                Text(stringResource(R.string.remote_trusted_key), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(old, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr))
                Text(stringResource(R.string.remote_presented_key), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                Text(presented, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = onTrust) { Text(stringResource(R.string.remote_trust_new_key)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
