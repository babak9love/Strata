package dev.cluvex.strata.feature.explorer.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.cluvex.strata.R
import dev.cluvex.strata.core.archive.ArchiveFormat
import dev.cluvex.strata.core.archive.ArchiveOptions
import dev.cluvex.strata.core.archive.CompressionLevel
import dev.cluvex.strata.core.crypto.FileCipher
import dev.cluvex.strata.core.fs.FileOperations
import dev.cluvex.strata.core.fs.FsUtil
import dev.cluvex.strata.core.fs.LinkRequest
import dev.cluvex.strata.core.pdf.PdfMerger
import dev.cluvex.strata.core.model.FileItem
import dev.cluvex.strata.core.tools.FileSplitter
import dev.cluvex.strata.core.tools.SplitMethod
import dev.cluvex.strata.core.util.Formatters
import dev.cluvex.strata.core.util.NaturalOrder

@Composable
private fun CheckRow(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, enabled = enabled, onValueChange = onChange).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = enabled)
        Text(
            label,
            modifier = Modifier.padding(start = 8.dp),
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        )
    }
}

@Composable
fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean = true,
    isError: Boolean = false,
    supporting: String? = null,
    onDone: (() -> Unit)? = null,
    focus: FocusRequester? = null,
) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = isError,
        supportingText = supporting?.let { { Text(it) } },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            autoCorrectEnabled = false,
            imeAction = if (onDone != null) ImeAction.Done else ImeAction.Next,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }, enabled = enabled, modifier = Modifier.focusProperties { canFocus = false }) {
                Icon(
                    if (visible) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = stringResource(if (visible) R.string.explorer_hide_password else R.string.explorer_show_password),
                )
            }
        },
        modifier = Modifier.fillMaxWidth().let { m -> if (focus != null) m.focusRequester(focus) else m },
    )
}

@Composable
private fun AutoFocus(focus: FocusRequester) {
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }
}

@Composable
private fun ltrText(): TextStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr)

@Composable
private fun Hint(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CreateArchiveDialog(
    items: List<FileItem>,
    defaultName: String,
    onConfirm: (name: String, options: ArchiveOptions, independent: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(defaultName) }
    var format by remember { mutableStateOf(ArchiveFormat.ZIP) }
    var level by remember { mutableStateOf(CompressionLevel.NORMAL) }
    var password by remember { mutableStateOf("") }
    var encryptNames by remember { mutableStateOf(false) }
    var independent by remember { mutableStateOf(false) }
    val fileName = format.fileName(name)
    val nameError = if (independent) null else FileOperations.validateName(fileName).takeIf { name.isNotBlank() }
    val canEncryptNames = format == ArchiveFormat.SEVEN_Z && password.isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.explorer_create_archive)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.explorer_file_name)) },
                    singleLine = true,
                    enabled = !independent,
                    isError = nameError != null,
                    supportingText = { Text(if (independent) stringResource(R.string.explorer_archive_each_named) else nameError ?: fileName) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.explorer_format), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ArchiveFormat.entries.forEach { f ->
                        FilterChip(selected = format == f, onClick = { format = f }, label = { Text(f.label) })
                    }
                }
                Text(stringResource(R.string.explorer_level), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CompressionLevel.entries.forEach { l ->
                        FilterChip(
                            selected = level == l && format.supportsLevel,
                            onClick = { level = l },
                            enabled = format.supportsLevel,
                            label = { Text(stringResource(l.label)) },
                        )
                    }
                }
                PasswordField(
                    value = if (format.supportsPassword) password else "",
                    onValueChange = { password = it },
                    label = stringResource(R.string.common_password),
                    enabled = format.supportsPassword,
                    supporting = when {
                        !format.supportsPassword -> stringResource(R.string.explorer_archive_no_password, format.label)
                        password.isEmpty() -> stringResource(R.string.explorer_archive_no_encryption)
                        format == ArchiveFormat.ZIP -> stringResource(R.string.explorer_archive_zip_aes)
                        else -> "AES-256"
                    },
                )
                CheckRow(stringResource(R.string.explorer_encrypt_names), encryptNames && canEncryptNames, enabled = canEncryptNames) { encryptNames = it }
                if (items.size > 1) CheckRow(stringResource(R.string.explorer_compress_each), independent) { independent = it }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val options = ArchiveOptions(
                        format = format,
                        level = level,
                        password = password.takeIf { it.isNotEmpty() && format.supportsPassword }?.toCharArray(),
                        encryptNames = encryptNames && canEncryptNames,
                    )
                    onConfirm(name.trim(), options, independent)
                },
                enabled = independent || (name.isNotBlank() && nameError == null),
            ) { Text(stringResource(R.string.explorer_compress)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
fun PasswordDialog(title: String, fileName: String, wrong: Boolean, onConfirm: (CharArray) -> Unit, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    AutoFocus(focus)
    val submit = { if (password.isNotEmpty()) onConfirm(password.toCharArray()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(fileName, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                PasswordField(password, { password = it }, stringResource(R.string.common_password), isError = wrong, onDone = submit, focus = focus)
                if (wrong) Hint(stringResource(R.string.files_cipher_wrong_password), error = true)
            }
        },
        confirmButton = {
            TextButton(onClick = submit, enabled = password.isNotEmpty()) { Text(stringResource(R.string.common_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
fun EncryptDialog(item: FileItem, onConfirm: (CharArray) -> Unit, onDismiss: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val mismatch = confirm.isNotEmpty() && confirm != password
    val focus = remember { FocusRequester() }
    AutoFocus(focus)
    val submit = { if (password.isNotEmpty() && password == confirm) onConfirm(password.toCharArray()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.explorer_encrypt_file)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${item.name} → ${FileCipher.encryptedName(item.name)}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                PasswordField(password, { password = it }, stringResource(R.string.common_password), focus = focus)
                PasswordField(confirm, { confirm = it }, stringResource(R.string.explorer_confirm_password), isError = mismatch, onDone = submit)
                if (mismatch) Hint(stringResource(R.string.explorer_passwords_mismatch), error = true)
                Hint(stringResource(R.string.explorer_encrypt_warning))
            }
        },
        confirmButton = {
            TextButton(onClick = submit, enabled = password.isNotEmpty() && password == confirm) { Text(stringResource(R.string.explorer_encrypt)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
fun CreateLinkDialog(items: List<FileItem>, defaultParent: String, onConfirm: (parent: String, requests: List<LinkRequest>) -> Unit, onDismiss: () -> Unit) {
    val single = items.singleOrNull()
    var name by remember { mutableStateOf(single?.name.orEmpty()) }
    var parent by remember { mutableStateOf(defaultParent) }
    var target by remember { mutableStateOf(single?.path.orEmpty()) }
    val nameError = single?.let { FileOperations.validateName(name) }
    val valid = parent.isNotBlank() && (single == null || (nameError == null && target.isNotBlank()))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.explorer_create_link)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (single != null) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.explorer_file_name)) }, singleLine = true, isError = nameError != null, modifier = Modifier.fillMaxWidth())
                } else {
                    Text(pluralStringResource(R.plurals.explorer_links_named, items.size, items.size))
                }
                OutlinedTextField(value = parent, onValueChange = { parent = it }, label = { Text(stringResource(R.string.explorer_link_parent)) }, singleLine = true, textStyle = ltrText(), modifier = Modifier.fillMaxWidth())
                if (single != null) {
                    OutlinedTextField(value = target, onValueChange = { target = it }, label = { Text(stringResource(R.string.explorer_linked_to)) }, singleLine = true, textStyle = ltrText(), modifier = Modifier.fillMaxWidth())
                }
                Hint(stringResource(R.string.explorer_link_hint))
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val requests = if (single != null) listOf(LinkRequest(name.trim(), target.trim())) else items.map { LinkRequest(it.name, it.path) }
                    onConfirm(parent.trim(), requests)
                },
                enabled = valid,
            ) { Text(stringResource(R.string.common_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
fun SplitDialog(item: FileItem, onConfirm: (SplitMethod, String) -> Unit, onDismiss: () -> Unit) {
    var bySize by remember { mutableStateOf(item.size > 20L * 1024 * 1024) }
    var sizeText by remember { mutableStateOf("10M") }
    var countText by remember { mutableStateOf("2") }
    var pattern by remember { mutableStateOf(FileSplitter.DEFAULT_PATTERN) }
    val method: SplitMethod? = if (bySize) FileSplitter.parseSize(sizeText)?.let { SplitMethod.BySize(it) } else countText.trim().toIntOrNull()?.takeIf { it > 0 }?.let { SplitMethod.ByCount(it) }
    val partSize = method?.let { FileSplitter.partSize(item.size, it) } ?: 0
    val parts = FileSplitter.partCount(item.size, partSize)
    val names = FileSplitter.partNames(item.name, pattern, parts.coerceIn(1, FileSplitter.MAX_PARTS))
    val problem = when {
        method == null -> stringResource(if (bySize) R.string.explorer_split_enter_size else R.string.explorer_split_enter_count)
        parts < 2 -> stringResource(R.string.files_split_one_part)
        parts > FileSplitter.MAX_PARTS -> stringResource(R.string.files_split_too_many, parts, FileSplitter.MAX_PARTS)
        names.isFailure -> names.exceptionOrNull()?.message
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.explorer_split_file)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("${item.name} · ${Formatters.size(item.size)}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                Text(stringResource(R.string.explorer_split_method), style = MaterialTheme.typography.labelLarge)
                listOf(true to stringResource(R.string.explorer_split_by_size), false to stringResource(R.string.explorer_split_by_count)).forEach { (value, label) ->
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = bySize == value, onClick = { bySize = value }, role = Role.RadioButton).padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = bySize == value, onClick = null)
                        Text(label, modifier = Modifier.padding(start = 8.dp))
                    }
                }
                if (bySize) {
                    OutlinedTextField(value = sizeText, onValueChange = { sizeText = it }, label = { Text(stringResource(R.string.common_size)) }, singleLine = true, textStyle = ltrText(), modifier = Modifier.fillMaxWidth())
                } else {
                    OutlinedTextField(
                        value = countText,
                        onValueChange = { countText = it },
                        label = { Text(stringResource(R.string.explorer_split_count)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(value = pattern, onValueChange = { pattern = it }, label = { Text(stringResource(R.string.explorer_name_pattern)) }, singleLine = true, textStyle = ltrText(), modifier = Modifier.fillMaxWidth())
                if (problem != null) {
                    Hint(problem, error = true)
                } else {
                    Hint(stringResource(R.string.explorer_split_summary, Formatters.size(partSize), parts))
                    Hint("${names.getOrNull()?.first()} … ${names.getOrNull()?.last()}")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { method?.let { onConfirm(it, pattern) } }, enabled = problem == null) { Text(stringResource(R.string.explorer_split)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
fun MergeDialog(parts: List<FileItem>, existingNames: Set<String>, onConfirm: (List<FileItem>, String, Boolean) -> Unit, onDismiss: () -> Unit) {
    val taken = remember(existingNames) { existingNames.mapTo(HashSet()) { it.lowercase() } }
    val allPdf = remember(parts) { parts.all { it.extension.equals("pdf", ignoreCase = true) } }
    var asPdf by remember { mutableStateOf(allPdf) }
    var ordered by remember { mutableStateOf(parts.sortedWith(compareBy(NaturalOrder) { it.name })) }
    fun suggestion(pdf: Boolean): String {
        val base = if (pdf) PdfMerger.outputName(ordered.first().name) else FileSplitter.mergedName(ordered.first().name)
        return FsUtil.uniqueName(base, isDirectory = false) { it.lowercase() in taken }
    }
    var output by remember { mutableStateOf(suggestion(asPdf)) }
    var edited by remember { mutableStateOf(false) }
    val nameError = FileOperations.validateName(output) ?: when {
        parts.any { it.name.equals(output, ignoreCase = true) } -> stringResource(R.string.files_merge_output_is_part)
        output.trim().lowercase() in taken -> stringResource(R.string.files_name_taken)
        else -> null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (asPdf) R.string.explorer_merge_pdf_title else R.string.explorer_merge_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (allPdf) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = asPdf,
                            onClick = { asPdf = true; if (!edited) output = suggestion(true) },
                            label = { Text(stringResource(R.string.explorer_join_pages)) },
                        )
                        FilterChip(
                            selected = !asPdf,
                            onClick = { asPdf = false; if (!edited) output = suggestion(false) },
                            label = { Text(stringResource(R.string.explorer_join_bytes)) },
                        )
                    }
                    Text(
                        stringResource(if (asPdf) R.string.explorer_join_pages_hint else R.string.explorer_join_bytes_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val count = pluralStringResource(if (asPdf) R.plurals.common_files else R.plurals.explorer_parts, ordered.size, ordered.size)
                Text("$count · ${Formatters.size(ordered.sumOf { it.size })}", style = MaterialTheme.typography.labelLarge)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
                    itemsIndexed(ordered, key = { _, p -> p.path }) { i, p ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${Formatters.number((i + 1).toLong())}. ${p.name}", modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.MiddleEllipsis, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            IconButton(onClick = { ordered = ordered.moved(i, i - 1) }, enabled = i > 0) { Icon(Icons.Rounded.ArrowUpward, contentDescription = stringResource(R.string.explorer_move_part_up, p.name)) }
                            IconButton(onClick = { ordered = ordered.moved(i, i + 1) }, enabled = i < ordered.lastIndex) { Icon(Icons.Rounded.ArrowDownward, contentDescription = stringResource(R.string.explorer_move_part_down, p.name)) }
                        }
                    }
                }
                OutlinedTextField(
                    value = output,
                    onValueChange = { output = it; edited = true },
                    label = { Text(stringResource(R.string.explorer_output_file)) },
                    singleLine = true,
                    isError = nameError != null,
                    supportingText = { nameError?.let { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(ordered, output.trim(), asPdf) }, enabled = nameError == null) { Text(stringResource(R.string.explorer_merge)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

private fun <T> List<T>.moved(from: Int, to: Int): List<T> =
    if (to !in indices) this else toMutableList().apply { add(to, removeAt(from)) }
