package dev.cluvex.strata.feature.explorer.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import dev.cluvex.strata.R
import kotlinx.coroutines.delay

private enum class RenameMode { NAME, FULL, EXTENSION }

private fun splitName(full: String): Pair<String, String> {
    val dot = full.lastIndexOf('.')
    return if (dot > 0 && dot < full.length - 1) full.substring(0, dot) to full.substring(dot + 1) else full to ""
}

private fun joinName(base: String, ext: String): String {
    val e = ext.trim().trimStart('.')
    return if (e.isEmpty()) base else "$base.$e"
}

/**
 * Rename dialog with three editing modes (like Mi Explorer):
 *  - Name: edit only the part before the extension
 *  - Full name: edit the whole file name
 *  - Extension: edit only the extension
 * Folders have no extension, so they always edit the full name.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RenameDialog(
    name: String,
    isDirectory: Boolean,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var mode by remember { mutableStateOf(if (isDirectory) RenameMode.FULL else RenameMode.NAME) }
    // The full name is the single source of truth; each mode edits one slice of it.
    var full by remember { mutableStateOf(name) }
    fun slice(m: RenameMode, f: String): String = when (m) {
        RenameMode.NAME -> splitName(f).first
        RenameMode.FULL -> f
        RenameMode.EXTENSION -> splitName(f).second
    }
    // Start with the cursor at the END so long names are scrolled to their tail (like Mi Explorer);
    // the select-all highlight is applied right after (see LaunchedEffect below) without moving the scroll.
    var value by remember { mutableStateOf(TextFieldValue(slice(mode, name), selection = TextRange(slice(mode, name).length))) }
    var selectTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(selectTick) {
        val text = value.text
        delay(80)
        if (value.text == text) value = value.copy(selection = TextRange(0, text.length))
    }

    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }

    fun merged(m: RenameMode, text: String, f: String): String = when (m) {
        RenameMode.NAME -> joinName(text, splitName(f).second)
        RenameMode.FULL -> text
        RenameMode.EXTENSION -> joinName(splitName(f).first, text)
    }

    fun switchTo(newMode: RenameMode) {
        if (newMode == mode) return
        full = merged(mode, value.text, full)
        mode = newMode
        val s = slice(newMode, full)
        value = TextFieldValue(s, selection = TextRange(s.length))
        selectTick++
    }

    val result = merged(mode, value.text, full).trim()
    val valid = when (mode) {
        RenameMode.EXTENSION -> result.isNotBlank()
        else -> value.text.isNotBlank() && result.isNotBlank()
    }
    val confirm = { if (valid) onConfirm(result) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.common_rename)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = {
                        Text(
                            stringResource(
                                when (mode) {
                                    RenameMode.EXTENSION -> R.string.rename_extension
                                    else -> R.string.explorer_new_name
                                },
                            ),
                        )
                    },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(
                        textDirection = if (mode == RenameMode.EXTENSION) TextDirection.Ltr else TextDirection.Content,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { confirm() }),
                    modifier = Modifier.focusRequester(focus),
                )
                if (!isDirectory) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(
                            RenameMode.NAME to R.string.rename_mode_name,
                            RenameMode.FULL to R.string.rename_mode_full,
                            RenameMode.EXTENSION to R.string.rename_mode_extension,
                        ).forEach { (m, label) ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .selectable(selected = mode == m, onClick = { switchTo(m) }, role = Role.RadioButton)
                                    .padding(end = 8.dp),
                            ) {
                                RadioButton(selected = mode == m, onClick = null, modifier = Modifier.padding(8.dp))
                                Text(stringResource(label))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = confirm, enabled = valid) { Text(stringResource(R.string.common_ok)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
