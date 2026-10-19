package dev.cluvex.strata.feature.explorer.components

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.cluvex.strata.R
import dev.cluvex.strata.core.media.FolderIconStore

private enum class IconChoice { DEFAULT, APP, AUTO, IMAGE }

/**
 * Lets the user pick the small icon shown on one folder: back to automatic, an installed app (by package name),
 * an image/video/APK found inside the folder, or a picture of their own.
 */
@Composable
fun FolderIconDialog(path: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val current = remember(path) { FolderIconStore.spec(path) }
    var choice by remember {
        mutableStateOf(
            when {
                current == null -> IconChoice.DEFAULT
                current.startsWith("app:") -> IconChoice.APP
                current == "auto" -> IconChoice.AUTO
                else -> IconChoice.IMAGE
            },
        )
    }
    var packageName by remember { mutableStateOf(current?.takeIf { it.startsWith("app:") }?.removePrefix("app:").orEmpty()) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            if (FolderIconStore.setImage(context, path, uri)) {
                onDismiss()
            } else {
                Toast.makeText(context, context.getString(R.string.folder_icon_image_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    val valid = when (choice) {
        IconChoice.APP -> packageName.isNotBlank()
        IconChoice.IMAGE -> false // applied by the picker itself
        else -> true
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.model_folder_icon)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ChoiceRow(choice == IconChoice.DEFAULT, stringResource(R.string.folder_icon_default)) { choice = IconChoice.DEFAULT }
                ChoiceRow(choice == IconChoice.APP, stringResource(R.string.folder_icon_app)) { choice = IconChoice.APP }
                if (choice == IconChoice.APP) {
                    OutlinedTextField(
                        value = packageName,
                        onValueChange = { packageName = it.trim() },
                        label = { Text(stringResource(R.string.folder_icon_package)) },
                        placeholder = { Text("com.example.app") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
                    )
                }
                ChoiceRow(choice == IconChoice.AUTO, stringResource(R.string.folder_icon_auto)) { choice = IconChoice.AUTO }
                ChoiceRow(choice == IconChoice.IMAGE, stringResource(R.string.folder_icon_image)) { choice = IconChoice.IMAGE }
                if (choice == IconChoice.IMAGE) {
                    OutlinedButton(
                        onClick = { picker.launch("image/*") },
                        modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
                    ) { Text(stringResource(R.string.folder_icon_choose_image)) }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    when (choice) {
                        IconChoice.DEFAULT -> FolderIconStore.clear(path)
                        IconChoice.APP -> FolderIconStore.setApp(path, packageName)
                        IconChoice.AUTO -> FolderIconStore.setAuto(path)
                        IconChoice.IMAGE -> Unit
                    }
                    onDismiss()
                },
            ) { Text(stringResource(R.string.common_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun ChoiceRow(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(8.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}
