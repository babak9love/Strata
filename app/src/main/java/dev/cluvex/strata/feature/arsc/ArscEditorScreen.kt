package dev.cluvex.strata.feature.arsc

import dev.cluvex.strata.core.i18n.separator
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.automirrored.rounded.ViewQuilt
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.FontDownload
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Numbers
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material.icons.rounded.Tag
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.ToggleOn
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.cluvex.strata.R
import dev.cluvex.strata.core.color.ColorCodec
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.res.ArscDocument
import dev.cluvex.strata.core.res.ArscResource
import dev.cluvex.strata.core.res.ArscValue
import dev.cluvex.strata.core.sound.UiSound
import dev.cluvex.strata.feature.color.ColorPickerDialog
import dev.cluvex.strata.ui.expressive.ArtTone
import dev.cluvex.strata.ui.expressive.ExpressiveDialog
import dev.cluvex.strata.ui.expressive.HeroShapes
import dev.cluvex.strata.ui.rememberSheetState

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ArscEditorScreen(
    path: String,
    entry: String?,
    rootEnabled: Boolean,
    onBack: () -> Unit,
    onSignApk: (String) -> Unit,
    vm: ArscEditorViewModel = viewModel(),
) {
    LaunchedEffect(path, entry) { vm.load(path, entry, rootEnabled) }
    val s by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmLeave by remember { mutableStateOf(false) }
    var leaveAfterSign by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Pair<ArscResource, ArscValue>?>(null) }

    LaunchedEffect(s.message) { s.message?.let { snackbar.showSnackbar(it); vm.consumeMessage() } }

    fun leave() = if (s.dirty) confirmLeave = true else onBack()

    BackHandler {
        when {
            s.type != null -> vm.openType(null)
            s.query.isNotEmpty() -> vm.setQuery("")
            else -> leave()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(s.type ?: s.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sub = listOfNotNull(s.archiveName, s.packageName.ifEmpty { null }).joinToString(separator())
                        if (sub.isNotEmpty()) {
                            Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (s.type != null) vm.openType(null) else leave() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    if (s.saving) {
                        LoadingIndicator(Modifier.size(36.dp).padding(end = 8.dp))
                    } else if (s.dirty) {
                        Button(onClick = { vm.save() }, modifier = Modifier.padding(end = 8.dp).testTag("arsc_save")) { Text(stringResource(R.string.common_save)) }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when {
                s.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { LoadingIndicator() }
                s.error != null -> Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
                    Text(s.error!!, color = MaterialTheme.colorScheme.error)
                }
                else -> {
                    OutlinedTextField(
                        value = s.query,
                        onValueChange = vm::setQuery,
                        placeholder = {
                            val type = s.type
                            Text(if (type != null) stringResource(R.string.arsc_search_type, type) else stringResource(R.string.arsc_search_all))
                        },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        trailingIcon = {
                            if (s.query.isNotEmpty()) IconButton(onClick = { vm.setQuery("") }) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.common_clear)) }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(28.dp),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp).testTag("arsc_search"),
                    )
                    if (s.type == null && s.query.isBlank()) {
                        TypeList(s.types) { vm.openType(it) }
                    } else {
                        ResourceList(s.resources, showType = s.type == null) { vm.openSheet(it) }
                    }
                }
            }
        }
    }

    s.sheet?.let { res ->
        ModalBottomSheet(onDismissRequest = { vm.openSheet(null) }, sheetState = rememberSheetState()) {
            ValuesSheet(res) { value -> editing = res to value }
        }
    }

    editing?.let { (res, value) ->
        EditValueDialog(
            resource = res,
            value = value,
            onSave = { text, done ->
                vm.setValue(res, value, text) { error ->
                    done(error)
                    if (error == null) editing = null
                }
            },
            onDismiss = { editing = null },
        )
    }

    if (confirmLeave) {
        ExpressiveDialog(
            onDismissRequest = { confirmLeave = false },
            title = stringResource(R.string.common_unsaved_changes),
            icon = Icons.Rounded.EditNote,
            tone = ArtTone.TERTIARY,
            shapes = HeroShapes.alert,
            sound = UiSound.ATTENTION,
            text = { Text(stringResource(R.string.arsc_save_before_leaving, s.fileName)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    vm.save(onSaved = { if (vm.state.value.apkNeedsSigning) leaveAfterSign = true else onBack() })
                }) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { confirmLeave = false; onBack() }) { Text(stringResource(R.string.common_discard)) }
                    TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.common_cancel)) }
                }
            },
        )
    }

    s.build?.let { dev.cluvex.strata.feature.build.BuildDialog(it, onClose = vm::closeBuild) }

    if (s.apkNeedsSigning && s.build == null) {
        AlertDialog(
            onDismissRequest = { vm.acknowledgeSigning(); if (leaveAfterSign) onBack() },
            title = { Text(stringResource(R.string.archive_apk_modified)) },
            text = { Text(stringResource(R.string.arsc_apk_changed, s.archiveName.orEmpty())) },
            confirmButton = {
                TextButton(onClick = { vm.acknowledgeSigning(); onSignApk(vm.apkPath); if (leaveAfterSign) onBack() }) { Text(stringResource(R.string.arsc_sign)) }
            },
            dismissButton = { TextButton(onClick = { vm.acknowledgeSigning(); if (leaveAfterSign) onBack() }) { Text(stringResource(R.string.common_later)) } },
        )
    }
}

@Composable
private fun TypeList(types: List<dev.cluvex.strata.core.res.ArscTypeCount>, onOpen: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().testTag("arsc_types")) {
        items(types, key = { it.type }) { t ->
            ListItem(
                leadingContent = { Icon(iconFor(t.type), contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                content = { Text(t.type) },
                trailingContent = { Text("${t.count}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) },
                modifier = Modifier.clickable { onOpen(t.type) },
            )
        }
    }
}

@Composable
private fun ResourceList(resources: List<ArscResource>, showType: Boolean, onOpen: (ArscResource) -> Unit) {
    if (resources.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(24.dp), Alignment.TopCenter) {
            Text(stringResource(R.string.arsc_nothing_found), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize().testTag("arsc_resources")) {
        items(resources, key = { it.id }) { r ->
            val preview = r.preview
            ListItem(
                overlineContent = {
                    Text(
                        if (showType) "${r.type} · ${r.hexId}" else r.hexId,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelSmall,
                    )
                },
                content = { Text(r.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = preview?.let { v -> { Text(v.text.ifEmpty { "\"\"" }, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                leadingContent = preview?.let { v -> colorOf(v)?.let { c -> { Swatch(c) } } },
                trailingContent = if (r.values.size > 1) ({ Text(pluralStringResource(R.plurals.arsc_configs, r.values.size, r.values.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }) else null,
                modifier = Modifier.clickable { onOpen(r) },
            )
            HorizontalDivider(Modifier.padding(start = 16.dp))
        }
    }
}

@Composable
private fun ValuesSheet(res: ArscResource, onEdit: (ArscValue) -> Unit) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
        Column(Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
            Text(res.name, style = MaterialTheme.typography.titleLarge)
            Text("${res.type} · ${res.hexId}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        LazyColumn(Modifier.heightIn(max = 480.dp)) {
            items(res.values, key = { it.qualifiers }) { v ->
                ListItem(
                    overlineContent = { Text(ArscDocument.displayQualifiers(v.qualifiers), color = MaterialTheme.colorScheme.primary) },
                    content = {
                        Text(
                            v.text.ifEmpty { "\"\"" },
                            fontFamily = if (v.valueType == "STRING") FontFamily.Default else FontFamily.Monospace,
                        )
                    },
                    supportingContent = { Text(typeLabel(v), style = MaterialTheme.typography.labelSmall) },
                    leadingContent = colorOf(v)?.let { c -> { Swatch(c) } },
                    trailingContent = if (v.editable) ({ Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.common_edit)) }) else null,
                    modifier = if (v.editable) Modifier.clickable { onEdit(v) }.testTag("arsc_value") else Modifier,
                )
            }
        }
    }
}

@Composable
private fun EditValueDialog(
    resource: ArscResource,
    value: ArscValue,
    onSave: (text: String, done: (String?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(value.text) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    val isString = value.valueType == "STRING"
    val isColor = value.valueType.startsWith("COLOR")
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(resource.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${ArscDocument.displayQualifiers(value.qualifiers)} · ${typeLabel(value)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    singleLine = !isString,
                    minLines = if (isString) 2 else 1,
                    isError = error != null,
                    supportingText = { error?.let { Text(it) } ?: hintFor(value)?.let { Text(it) } },
                    leadingIcon = colorOf(value.copy(text = text))?.let { c -> { Swatch(c) } },
                    trailingIcon = if (isColor) ({
                        IconButton(onClick = { picking = true }) { Icon(Icons.Rounded.Palette, contentDescription = stringResource(R.string.arsc_pick_color)) }
                    }) else null,
                    textStyle = if (isString) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr),
                    modifier = Modifier.fillMaxWidth().testTag("arsc_edit_field"),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    onSave(text) { e -> busy = false; error = e }
                },
            ) { Text(stringResource(R.string.common_apply)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
    if (picking) {
        ColorPickerDialog(
            initial = ColorCodec.parse(text) ?: 0xFF000000.toInt(),
            onPick = { c ->
                val typed = ColorCodec.literalAt(text.trim(), 0)?.takeIf { it.start == 0 && it.end == text.trim().length }
                text = typed?.render(c) ?: ColorCodec.androidHex(c)
                error = null
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

@Composable
private fun Swatch(color: Color) {
    Spacer(
        Modifier.size(24.dp).background(color, CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
    )
}

private fun typeLabel(v: ArscValue): String = when {
    v.valueType == "COMPLEX" -> tr(R.string.arsc_read_only_for_now)
    v.valueType.startsWith("COLOR") -> "color"
    else -> v.valueType.lowercase().replace('_', ' ')
}

private fun hintFor(v: ArscValue): String? = when {
    v.valueType.startsWith("COLOR") -> tr(R.string.arsc_hint_color)
    v.valueType == "DIMENSION" -> tr(R.string.arsc_hint_dimension)
    v.valueType == "BOOLEAN" -> tr(R.string.arsc_hint_boolean)
    v.valueType == "REFERENCE" -> tr(R.string.arsc_hint_reference)
    else -> null
}

private fun colorOf(v: ArscValue): Color? {
    if (!v.valueType.startsWith("COLOR")) return null
    val hex = v.text.trim().removePrefix("#")
    if (hex.isEmpty() || hex.any { it !in "0123456789abcdefABCDEF" }) return null
    val full = when (hex.length) {
        3 -> "ff" + hex.map { "$it$it" }.joinToString("")
        4 -> hex.map { "$it$it" }.joinToString("")
        6 -> "ff$hex"
        8 -> hex
        else -> return null
    }
    return Color(full.toLong(16).toInt())
}

private fun iconFor(type: String): ImageVector = when (type) {
    "string" -> Icons.Rounded.TextFields
    "color" -> Icons.Rounded.Palette
    "dimen" -> Icons.Rounded.Straighten
    "drawable", "mipmap" -> Icons.Rounded.Image
    "layout" -> Icons.AutoMirrored.Rounded.ViewQuilt
    "id" -> Icons.Rounded.Tag
    "style" -> Icons.Rounded.Style
    "xml" -> Icons.Rounded.Code
    "raw" -> Icons.AutoMirrored.Rounded.InsertDriveFile
    "array" -> Icons.AutoMirrored.Rounded.ViewList
    "plurals" -> Icons.Rounded.FormatListNumbered
    "bool" -> Icons.Rounded.ToggleOn
    "integer" -> Icons.Rounded.Numbers
    "attr" -> Icons.Rounded.Tune
    "anim", "animator", "interpolator", "transition" -> Icons.Rounded.Animation
    "font" -> Icons.Rounded.FontDownload
    "menu", "navigation" -> Icons.Rounded.Apps
    "fraction" -> Icons.Rounded.DataObject
    else -> Icons.Rounded.Category
}
