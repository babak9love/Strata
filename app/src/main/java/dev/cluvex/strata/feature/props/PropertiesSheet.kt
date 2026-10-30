package dev.cluvex.strata.feature.props

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessTime
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Person
import dev.cluvex.strata.ui.expressive.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.i18n.trPlural
import dev.cluvex.strata.core.model.FileItem
import dev.cluvex.strata.core.props.AndroidIds
import dev.cluvex.strata.core.props.PermissionMode
import dev.cluvex.strata.core.util.DateTimeText
import dev.cluvex.strata.core.util.Formatters
import dev.cluvex.strata.ui.asPath
import dev.cluvex.strata.ui.isolated
import dev.cluvex.strata.ui.rememberTextClipboard
import java.text.NumberFormat
import dev.cluvex.strata.core.media.MediaInfoReader
import dev.cluvex.strata.core.media.MediaSection
import dev.cluvex.strata.core.util.FileKind
import dev.cluvex.strata.core.util.FileKinds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PropertiesSheet(
    items: List<FileItem>,
    onDismiss: () -> Unit,
    onChanged: () -> Unit,
    rootEnabled: Boolean = false,
    vm: PropertiesViewModel = viewModel(),
) {
    LaunchedEffect(items, rootEnabled) { vm.load(items, rootEnabled) }
    val ui by vm.state.collectAsStateWithLifecycle()
    var showPermissions by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }
    var showOwner by remember { mutableStateOf(false) }
    val clipboard = rememberTextClipboard()

    LaunchedEffect(ui.changes) { if (ui.changes > 0) onChanged() }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            val single = ui.single
            Text(
                single?.name?.let { isolated(it) } ?: pluralStringResource(R.plurals.common_items, ui.items.size, ui.items.size),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            HorizontalDivider()

            if (single != null) {
                InfoRow(stringResource(R.string.props_location), single.parentPath ?: "/", ltr = true)
                val typeText = when {
                    single.isSymlink -> stringResource(R.string.props_symlink)
                    single.isDirectory -> stringResource(R.string.common_folder)
                    single.extension.isNotEmpty() -> stringResource(R.string.props_ext_file, single.extension.uppercase())
                    else -> stringResource(R.string.common_file)
                }
                val mediaKind = if (single.isDirectory || single.isSymlink) null else FileKinds.of(single)
                    .takeIf { it == FileKind.AUDIO || it == FileKind.VIDEO }
                if (mediaKind != null) {
                    var expanded by remember(single.path) { mutableStateOf(false) }
                    var sections by remember(single.path) { mutableStateOf<List<MediaSection>?>(null) }
                    LaunchedEffect(single.path, expanded) {
                        if (expanded && sections == null) {
                            sections = withContext(Dispatchers.IO) { MediaInfoReader.read(single.path) }
                                .filter { mediaKind == FileKind.VIDEO || it.title != R.string.media_section_video }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().clickable { expanded = !expanded },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                            Text(
                                stringResource(if (mediaKind == FileKind.VIDEO) R.string.model_sheet_video_info else R.string.model_sheet_audio_info),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            Text(typeText, style = MaterialTheme.typography.bodyMedium)
                        }
                        Icon(
                            if (expanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                    AnimatedVisibility(expanded) { MediaInfoBody(sections) }
                } else {
                    InfoRow(stringResource(R.string.common_type), typeText)
                }
                val imagePath = single.path.takeIf { !single.isDirectory && !single.isSymlink && FileKinds.of(single) == FileKind.IMAGE }
                var resolution by remember(imagePath) { mutableStateOf<String?>(null) }
                LaunchedEffect(imagePath) {
                    resolution = imagePath?.let { withContext(Dispatchers.IO) { MediaInfoReader.imageResolution(it) } }
                }
                resolution?.let { InfoRow(stringResource(R.string.media_resolution), it, ltr = true) }
                ui.linkTarget?.let { InfoRow(stringResource(R.string.props_linked_to), it, ltr = true) }
            } else {
                val dirs = ui.items.count { it.isDirectory }
                InfoRow(stringResource(R.string.props_selected), stringResource(R.string.props_selected_value, ui.items.size - dirs, dirs))
                InfoRow(stringResource(R.string.props_location), ui.items.firstOrNull()?.parentPath ?: "/", ltr = true)
            }

            val stats = ui.stats
            val nf = remember { NumberFormat.getIntegerInstance() }
            Row(verticalAlignment = Alignment.CenterVertically) {
                InfoRow(
                    stringResource(R.string.common_size),
                    if (stats == null) "…" else stringResource(R.string.props_size_value, Formatters.size(stats.bytes), nf.format(stats.bytes)),
                    modifier = Modifier.weight(1f),
                )
                if (ui.counting) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            }
            if (ui.hasDirectories && stats != null) {
                InfoRow(stringResource(R.string.props_contains), stringResource(R.string.props_contains_value, nf.format(stats.files), nf.format(stats.folders)))
            }
            if (single != null) InfoRow(stringResource(R.string.props_modified), DateTimeText.format(single.lastModified), ltr = true)

            ui.stat?.let { st ->
                val type = PermissionMode.typeChar(st.mode)
                InfoRow(stringResource(R.string.common_permissions), "${PermissionMode.toSymbolic(st.mode, type)}  (${PermissionMode.toOctal(st.mode)})", mono = true)
                InfoRow(stringResource(R.string.common_owner), "${ui.owner ?: "?"} (${st.uid})", ltr = true)
                InfoRow(stringResource(R.string.common_group), "${ui.group ?: "?"} (${st.gid})", ltr = true)
            }
            ui.statError?.let { InfoRow(stringResource(R.string.props_info), it) }

            ui.message?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }

            FlowRow(
                Modifier.fillMaxWidth().padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                FilledTonalButton(onClick = { showPermissions = true }, enabled = !ui.busy) {
                    Icon(Icons.Rounded.Lock, null, Modifier.size(18.dp))
                    Text(stringResource(R.string.common_permissions), Modifier.padding(start = 6.dp))
                }
                FilledTonalButton(onClick = { showTime = true }, enabled = !ui.busy) {
                    Icon(Icons.Rounded.AccessTime, null, Modifier.size(18.dp))
                    Text(stringResource(R.string.props_time), Modifier.padding(start = 6.dp))
                }
                if (ui.viaRoot) {
                    FilledTonalButton(onClick = { showOwner = true }, enabled = !ui.busy) {
                        Icon(Icons.Rounded.Person, null, Modifier.size(18.dp))
                        Text(stringResource(R.string.common_owner), Modifier.padding(start = 6.dp))
                    }
                }
                FilledTonalButton(onClick = {
                    clipboard.setText(ui.items.joinToString("\n") { it.path })
                    vm.notify(if (ui.items.size == 1) tr(R.string.props_path_copied) else trPlural(R.plurals.props_paths_copied, ui.items.size, ui.items.size))
                }) {
                    Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp))
                    Text(stringResource(R.string.common_path), Modifier.padding(start = 6.dp))
                }
            }
        }
    }

    if (showPermissions) {
        PermissionsEditorDialog(
            initialMode = ui.stat?.mode ?: 0x1A4,
            hasDirectories = ui.hasDirectories,
            onApply = { mode, subFiles, subDirs ->
                showPermissions = false
                vm.applyPermissions(mode, subFiles, subDirs)
            },
            onDismiss = { showPermissions = false },
        )
    }
    if (showOwner) {
        OwnerEditorDialog(
            initialOwner = ui.owner ?: ui.stat?.uid?.toString().orEmpty(),
            initialGroup = ui.group ?: ui.stat?.gid?.toString().orEmpty(),
            hasDirectories = ui.hasDirectories,
            onApply = { uid, gid, subFiles, subDirs ->
                showOwner = false
                vm.applyOwner(uid, gid, subFiles, subDirs)
            },
            onDismiss = { showOwner = false },
        )
    }
    if (showTime) {
        ModifyTimeDialog(
            initialMillis = ui.single?.lastModified ?: System.currentTimeMillis(),
            onApply = { millis ->
                showTime = false
                vm.applyModifiedTime(millis)
            },
            onDismiss = { showTime = false },
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String, modifier: Modifier = Modifier, mono: Boolean = false, ltr: Boolean = mono) {
    Column(modifier.padding(vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        SelectionContainer {
            Text(
                value,
                style = if (ltr) MaterialTheme.typography.bodyMedium.asPath() else MaterialTheme.typography.bodyMedium,
                fontFamily = if (mono) FontFamily.Monospace else null,
            )
        }
    }
}

@Composable
fun PermissionsEditorDialog(
    initialMode: Int,
    hasDirectories: Boolean,
    onApply: (mode: Int, applyToSubFiles: Boolean, applyToSubDirs: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var mode by remember { mutableIntStateOf(initialMode and PermissionMode.PERMISSION_MASK) }
    var octal by remember { mutableStateOf(PermissionMode.toOctal(initialMode)) }
    var subFiles by remember { mutableStateOf(false) }
    var subDirs by remember { mutableStateOf(false) }

    fun setBit(bit: Int, on: Boolean) {
        mode = PermissionMode.with(mode, bit, on)
        octal = PermissionMode.toOctal(mode)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.common_permissions)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(Modifier.fillMaxWidth()) {
                    Spacer(80)
                    listOf(R.string.props_read, R.string.props_write, R.string.props_exec).forEach {
                        Text(stringResource(it), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                    }
                }
                val rows = listOf(
                    Triple(R.string.common_owner, PermissionMode.OWNER_READ, listOf(PermissionMode.OWNER_WRITE, PermissionMode.OWNER_EXEC)),
                    Triple(R.string.common_group, PermissionMode.GROUP_READ, listOf(PermissionMode.GROUP_WRITE, PermissionMode.GROUP_EXEC)),
                    Triple(R.string.props_other, PermissionMode.OTHER_READ, listOf(PermissionMode.OTHER_WRITE, PermissionMode.OTHER_EXEC)),
                )
                rows.forEach { (label, read, rest) ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(label), Modifier.width(80.dp))
                        (listOf(read) + rest).forEach { bit ->
                            Checkbox(
                                checked = PermissionMode.has(mode, bit),
                                onCheckedChange = { setBit(bit, it) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                Text(stringResource(R.string.props_special), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                CheckRow(stringResource(R.string.props_setuid), PermissionMode.has(mode, PermissionMode.SETUID)) { setBit(PermissionMode.SETUID, it) }
                CheckRow(stringResource(R.string.props_setgid), PermissionMode.has(mode, PermissionMode.SETGID)) { setBit(PermissionMode.SETGID, it) }
                CheckRow(stringResource(R.string.props_sticky), PermissionMode.has(mode, PermissionMode.STICKY)) { setBit(PermissionMode.STICKY, it) }

                OutlinedTextField(
                    value = octal,
                    onValueChange = { text ->
                        octal = text
                        PermissionMode.parseOctal(text)?.let { mode = it }
                    },
                    label = { Text(stringResource(R.string.props_octal)) },
                    isError = PermissionMode.parseOctal(octal) == null,
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Text(
                    PermissionMode.toSymbolic(mode),
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (hasDirectories) {
                    CheckRow(stringResource(R.string.props_sub_files), subFiles) { subFiles = it }
                    CheckRow(stringResource(R.string.props_sub_folders), subDirs) { subDirs = it }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onApply(mode, subFiles, subDirs) },
                enabled = PermissionMode.parseOctal(octal) != null,
            ) { Text(stringResource(R.string.common_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, onValueChange = onChange).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(label, Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun Spacer(widthDp: Int) {
    androidx.compose.foundation.layout.Spacer(Modifier.width(widthDp.dp))
}

@Composable
fun OwnerEditorDialog(
    initialOwner: String,
    initialGroup: String,
    hasDirectories: Boolean,
    onApply: (uid: Int, gid: Int, applyToSubFiles: Boolean, applyToSubDirs: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var owner by remember { mutableStateOf(initialOwner) }
    var group by remember { mutableStateOf(initialGroup) }
    var subFiles by remember { mutableStateOf(false) }
    var subDirs by remember { mutableStateOf(false) }
    val uid = AndroidIds.idOf(owner)
    val gid = AndroidIds.idOf(group)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.props_owner_group)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = owner,
                    onValueChange = { owner = it },
                    label = { Text(stringResource(R.string.common_owner)) },
                    supportingText = { Text(uid?.let { "uid $it" } ?: stringResource(R.string.props_unknown_user)) },
                    isError = uid == null,
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = group,
                    onValueChange = { group = it },
                    label = { Text(stringResource(R.string.common_group)) },
                    supportingText = { Text(gid?.let { "gid $it" } ?: stringResource(R.string.props_unknown_group)) },
                    isError = gid == null,
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
                if (hasDirectories) {
                    CheckRow(stringResource(R.string.props_sub_files), subFiles) { subFiles = it }
                    CheckRow(stringResource(R.string.props_sub_folders), subDirs) { subDirs = it }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (uid != null && gid != null) onApply(uid, gid, subFiles, subDirs) },
                enabled = uid != null && gid != null,
            ) { Text(stringResource(R.string.common_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
fun ModifyTimeDialog(
    initialMillis: Long,
    onApply: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(DateTimeText.format(initialMillis)) }
    val parsed = DateTimeText.parse(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.props_modify_time)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(DateTimeText.PATTERN) },
                    isError = parsed == null,
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { text = DateTimeText.format(System.currentTimeMillis()) }) { Text(stringResource(R.string.props_now)) }
            }
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onApply) }, enabled = parsed != null) { Text(stringResource(R.string.common_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
