package dev.cluvex.strata.feature.explorer.components

import android.content.Context
import android.content.res.ColorStateList
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.cluvex.strata.R

private enum class RenameMode { NAME, FULL, EXTENSION }

private fun splitName(full: String): Pair<String, String> {
    val dot = full.lastIndexOf('.')
    return if (dot > 0 && dot < full.length - 1) full.substring(0, dot) to full.substring(dot + 1) else full to ""
}

private fun joinName(base: String, ext: String): String {
    val e = ext.trim().trimStart('.')
    return if (e.isEmpty()) base else "$base.$e"
}

private fun slice(mode: RenameMode, full: String): String = when (mode) {
    RenameMode.NAME -> splitName(full).first
    RenameMode.FULL -> full
    RenameMode.EXTENSION -> splitName(full).second
}

private fun merged(mode: RenameMode, text: String, full: String): String = when (mode) {
    RenameMode.NAME -> joinName(text, splitName(full).second)
    RenameMode.FULL -> text
    RenameMode.EXTENSION -> joinName(splitName(full).first, text)
}

/** Selects all text with the caret/scroll at the END, so a long name shows its tail (like Mi Explorer). */
private fun EditText.selectAllScrolledToEnd() {
    postDelayed({
        val len = text?.length ?: 0
        setSelection(0, len)
    }, 60)
}

private fun EditText.focusAndShowKeyboard(context: Context) {
    postDelayed({
        requestFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }, 120)
}

/**
 * Rename dialog with three editing modes (like Mi Explorer):
 *  - Name: edit only the part before the extension
 *  - Full name: edit the whole file name
 *  - Extension: edit only the extension
 * Folders have no extension, so they always edit the full name.
 *
 * The text field is a native EditText: Compose's text field scrolls to the START of a selection, so a
 * select-all would show the beginning of a long name, whereas EditText scrolls to the END of the
 * selection, which is the behaviour we want.
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
    var fieldText by remember { mutableStateOf(slice(mode, name)) }
    var edit by remember { mutableStateOf<EditText?>(null) }

    fun switchTo(newMode: RenameMode) {
        if (newMode == mode) return
        val current = edit?.text?.toString() ?: fieldText
        full = merged(mode, current, full)
        mode = newMode
        val s = slice(newMode, full)
        fieldText = s
        edit?.apply {
            setText(s)
            selectAllScrolledToEnd()
        }
    }

    val result = merged(mode, fieldText, full).trim()
    val valid = when (mode) {
        RenameMode.EXTENSION -> result.isNotBlank()
        else -> fieldText.isNotBlank() && result.isNotBlank()
    }
    val confirm = { if (valid) onConfirm(result) }
    val currentConfirm by rememberUpdatedState(confirm)

    val textColor = MaterialTheme.colorScheme.onSurface
    val accent = MaterialTheme.colorScheme.primary
    val hint = MaterialTheme.colorScheme.onSurfaceVariant

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.common_rename)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(if (mode == RenameMode.EXTENSION) R.string.rename_extension else R.string.explorer_new_name),
                    style = MaterialTheme.typography.labelMedium,
                    color = hint,
                )
                AndroidView(
                    modifier = Modifier.fillMaxWidth(),
                    factory = { ctx ->
                        EditText(ctx).apply {
                            setSingleLine(true)
                            inputType = InputType.TYPE_CLASS_TEXT
                            imeOptions = EditorInfo.IME_ACTION_DONE
                            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                            setText(fieldText)
                            addTextChangedListener(object : TextWatcher {
                                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                                override fun afterTextChanged(s: Editable?) { fieldText = s?.toString().orEmpty() }
                            })
                            setOnEditorActionListener { _, actionId, _ ->
                                if (actionId == EditorInfo.IME_ACTION_DONE) { currentConfirm(); true } else false
                            }
                            edit = this
                            selectAllScrolledToEnd()
                            focusAndShowKeyboard(ctx)
                        }
                    },
                    update = { et ->
                        et.setTextColor(textColor.toArgb())
                        et.highlightColor = accent.copy(alpha = 0.35f).toArgb()
                        et.backgroundTintList = ColorStateList.valueOf(accent.toArgb())
                        et.textDirection = if (mode == RenameMode.EXTENSION) View.TEXT_DIRECTION_LTR else View.TEXT_DIRECTION_FIRST_STRONG
                    },
                )
                if (!isDirectory) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
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
