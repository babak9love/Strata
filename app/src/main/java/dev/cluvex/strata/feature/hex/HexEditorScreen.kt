package dev.cluvex.strata.feature.hex

import dev.cluvex.strata.core.i18n.separator
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.cluvex.strata.R
import dev.cluvex.strata.core.hex.CopyFormat
import dev.cluvex.strata.core.hex.HexCodec
import dev.cluvex.strata.core.hex.HexDataType
import dev.cluvex.strata.core.hex.PasteFormat
import dev.cluvex.strata.core.sound.UiSound
import dev.cluvex.strata.core.util.Formatters
import dev.cluvex.strata.ui.expressive.ArtTone
import dev.cluvex.strata.ui.expressive.ExpressiveDialog
import dev.cluvex.strata.ui.expressive.HeroShapes
import dev.cluvex.strata.ui.rememberTextClipboard

private sealed interface HexDialog {
    data object None : HexDialog
    data object Goto : HexDialog
    data object SelectRange : HexDialog
    data object CopyAs : HexDialog
    data object PasteFrom : HexDialog
    data object Unsaved : HexDialog
}

private val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp)

private const val HEX_DIGITS = "0123456789ABCDEF"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HexEditorScreen(
    path: String,
    onBack: () -> Unit,
    rootEnabled: Boolean = false,
    vm: HexEditorViewModel = viewModel(),
) {
    LaunchedEffect(path) { vm.open(path, rootEnabled) }
    val ui by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val clipboard = rememberTextClipboard()
    val listState = rememberLazyListState()
    var menuOpen by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf<HexDialog>(HexDialog.None) }

    LaunchedEffect(ui.message) {
        ui.message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }
    LaunchedEffect(ui.scrollSeq) {
        if (ui.scrollSeq > 0 && ui.bytesPerRow > 0) {
            val row = (ui.scrollTo / ui.bytesPerRow).toInt()
            listState.scrollToItem(maxOf(0, row - 3))
        }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.layoutInfo.visibleItemsInfo.size }
            .collect { (first, count) -> vm.onViewport(first, count) }
    }

    fun leave() {
        if (ui.dirty) {
            dialog = HexDialog.Unsaved
        } else {
            vm.discard()
            onBack()
        }
    }
    BackHandler {
        when {
            searchOpen -> searchOpen = false
            ui.pendingNibble >= 0 -> vm.cancelPendingNibble()
            ui.hasSelection -> vm.clearSelection()
            else -> leave()
        }
    }

    Scaffold(
        modifier = Modifier.testTag("hex-editor"),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = ::leave) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back)) }
                },
                title = {
                    Column {
                        Text(
                            ui.name + if (ui.dirty) " ●" else "",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            buildString {
                                append("0x").append(HexCodec.formatOffset(ui.cursor, ui.size))
                                if (ui.hasSelection) append(separator()).append(stringResource(R.string.hex_status_selection, ui.selectionLength))
                                append(separator()).append(Formatters.size(ui.size))
                                if (ui.readOnly) append(separator()).append(stringResource(R.string.hex_status_read_only))
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = vm::undo, enabled = ui.canUndo) {
                        Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = stringResource(R.string.editor_undo))
                    }
                    IconButton(onClick = vm::redo, enabled = ui.canRedo) {
                        Icon(Icons.AutoMirrored.Rounded.Redo, contentDescription = stringResource(R.string.editor_redo))
                    }
                    IconButton(onClick = { vm.save() }, enabled = ui.dirty) {
                        Icon(Icons.Rounded.Save, contentDescription = stringResource(R.string.common_save))
                    }
                    IconButton(onClick = { searchOpen = !searchOpen }) {
                        Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.common_search))
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.common_more)) }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.hex_menu_go_to_offset)) },
                                onClick = { menuOpen = false; dialog = HexDialog.Goto },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.hex_menu_select_range)) },
                                onClick = { menuOpen = false; dialog = HexDialog.SelectRange },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.hex_menu_copy_as)) },
                                onClick = { menuOpen = false; dialog = HexDialog.CopyAs },
                            )
                            if (!ui.readOnly) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.hex_menu_paste_from)) },
                                    onClick = { menuOpen = false; dialog = HexDialog.PasteFrom },
                                )
                                DropdownMenuItem(text = { Text(stringResource(R.string.hex_zero_fill)) }, onClick = { menuOpen = false; vm.fillSelection(0) })
                            }
                            if (ui.hasSelection) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.common_clear_selection)) },
                                    onClick = { menuOpen = false; vm.clearSelection() },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text(stringResource(if (ui.bigEndian) R.string.hex_big_endian_checked else R.string.hex_big_endian)) },
                                onClick = { menuOpen = false; vm.toggleEndian() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(if (ui.showInspector) R.string.hex_hide_inspector else R.string.hex_show_inspector)) },
                                onClick = { menuOpen = false; vm.toggleInspector() },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val perByte = 33.5f
            val available = maxWidth.value - 86f
            val bpr = when {
                available >= 16 * perByte -> 16
                available >= 8 * perByte -> 8
                else -> 4
            }
            LaunchedEffect(bpr) { vm.setBytesPerRow(bpr) }
            when {
                ui.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                ui.error != null -> Text(
                    ui.error ?: "",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
                else -> Column(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        ) {
                            items(count = ui.rowCount) { row ->
                                HexRow(row = row, ui = ui, onTap = vm::tap, onLongPress = vm::longPress)
                            }
                        }
                    }
                    if (searchOpen) {
                        SearchPanel(
                            readOnly = ui.readOnly,
                            bigEndianDefault = ui.bigEndian,
                            onFind = vm::find,
                            onReplace = vm::replace,
                            onClose = { searchOpen = false },
                        )
                    }
                    if (ui.showInspector) InspectorPanel(ui.inspector, ui.bigEndian, onToggleEndian = vm::toggleEndian)
                    if (!ui.readOnly) HexKeypad(pending = ui.pendingNibble, onNibble = vm::typeNibble)
                }
            }
        }
    }

    when (dialog) {
        HexDialog.None -> Unit
        HexDialog.Goto -> OffsetDialog(
            title = stringResource(R.string.hex_go_to_offset),
            fields = listOf(stringResource(R.string.hex_offset)),
            onConfirm = { values -> vm.goto(values[0]); dialog = HexDialog.None },
            onDismiss = { dialog = HexDialog.None },
        )
        HexDialog.SelectRange -> OffsetDialog(
            title = stringResource(R.string.hex_select_range),
            fields = listOf(stringResource(R.string.hex_start), stringResource(R.string.hex_length)),
            onConfirm = { values -> vm.selectRange(values[0], values[1]); dialog = HexDialog.None },
            onDismiss = { dialog = HexDialog.None },
        )
        HexDialog.CopyAs -> ChoiceDialog(
            title = stringResource(R.string.hex_copy_as),
            options = CopyFormat.entries.map { stringResource(it.label) },
            onPick = { i ->
                dialog = HexDialog.None
                vm.copyAs(CopyFormat.entries[i]) { text -> clipboard.setText(text) }
            },
            onDismiss = { dialog = HexDialog.None },
        )
        HexDialog.PasteFrom -> ChoiceDialog(
            title = stringResource(R.string.hex_paste_from),
            options = PasteFormat.entries.map { stringResource(it.label) },
            onPick = { i ->
                dialog = HexDialog.None
                clipboard.readText { vm.pasteFrom(it, PasteFormat.entries[i]) }
            },
            onDismiss = { dialog = HexDialog.None },
        )
        HexDialog.Unsaved -> ExpressiveDialog(
            onDismissRequest = { dialog = HexDialog.None },
            title = stringResource(R.string.common_unsaved_changes),
            icon = Icons.Rounded.EditNote,
            tone = ArtTone.TERTIARY,
            shapes = HeroShapes.alert,
            sound = UiSound.ATTENTION,
            text = { Text(stringResource(R.string.editor_save_before_closing, ui.name)) },
            confirmButton = {
                TextButton(onClick = {
                    dialog = HexDialog.None
                    vm.save { vm.discard(); onBack() }
                }) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { dialog = HexDialog.None; vm.discard(); onBack() }) {
                        Text(stringResource(R.string.common_discard), color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { dialog = HexDialog.None }) { Text(stringResource(R.string.common_cancel)) }
                }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HexRow(row: Int, ui: HexUiState, onTap: (Long) -> Unit, onLongPress: (Long) -> Unit) {
    val bpr = ui.bytesPerRow
    val rowStart = row.toLong() * bpr
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            HexCodec.formatOffset(rowStart, ui.size),
            style = Mono,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        for (col in 0 until bpr) {
            val offset = rowStart + col
            if (offset >= ui.size) {
                Box(Modifier.width(26.dp))
                continue
            }
            val value = ui.byteAt(offset)
            val isCursor = offset == ui.cursor
            val text = when {
                isCursor && ui.pendingNibble >= 0 -> "${HEX_DIGITS[ui.pendingNibble]}_"
                value == null -> "··"
                else -> HexCodec.byteToHex(value)
            }
            val bg = when {
                isCursor -> colors.primaryContainer
                ui.isSelected(offset) -> colors.secondaryContainer
                else -> Color.Transparent
            }
            val fg = when {
                offset in ui.modified -> colors.error
                isCursor -> colors.onPrimaryContainer
                else -> colors.onSurface
            }
            Text(
                text,
                style = Mono,
                color = fg,
                modifier = Modifier
                    .width(26.dp)
                    .background(bg, RoundedCornerShape(4.dp))
                    .combinedClickable(onClick = { onTap(offset) }, onLongClick = { onLongPress(offset) })
                    .padding(vertical = 2.dp),
            )
        }
        val ascii = buildAnnotatedString {
            for (col in 0 until bpr) {
                val offset = rowStart + col
                if (offset >= ui.size) break
                val v = ui.byteAt(offset)
                val ch = if (v == null) '·' else HexCodec.asciiChar(v)
                when {
                    offset == ui.cursor -> withStyle(SpanStyle(background = colors.primaryContainer)) { append(ch) }
                    ui.isSelected(offset) -> withStyle(SpanStyle(background = colors.secondaryContainer)) { append(ch) }
                    else -> append(ch)
                }
            }
        }
        Text(ascii, style = Mono, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun InspectorPanel(values: List<Pair<String, String>>, bigEndian: Boolean, onToggleEndian: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.hex_inspector), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                FilterChip(selected = bigEndian, onClick = onToggleEndian, label = { Text(stringResource(R.string.hex_big_endian)) })
            }
            val pairs = values.chunked(2)
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Column(Modifier.height(96.dp).verticalScroll(rememberScrollState())) {
                    pairs.forEach { line ->
                        Row(Modifier.fillMaxWidth()) {
                            line.forEach { (label, value) ->
                                Row(Modifier.weight(1f)) {
                                    Text("$label: ", style = Mono.copy(textDirection = TextDirection.Ltr), color = MaterialTheme.colorScheme.primary)
                                    Text(value, style = Mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HexKeypad(pending: Int, onNibble: (Int) -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(4.dp)) {
                for (rowIndex in 0 until 2) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (i in 0 until 8) {
                            val v = rowIndex * 8 + i
                            val highlighted = v == pending
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                modifier = Modifier
                                    .weight(1f)
                                    .height(40.dp)
                                    .padding(vertical = 2.dp)
                                    .clickable { onNibble(v) },
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        HEX_DIGITS[v].toString(),
                                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                                        color = if (highlighted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchPanel(
    readOnly: Boolean,
    bigEndianDefault: Boolean,
    onFind: (String, HexDataType, Boolean, Boolean) -> Unit,
    onReplace: (String, String, HexDataType, Boolean, Boolean) -> Unit,
    onClose: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(HexDataType.HEX) }
    var bigEndian by remember { mutableStateOf(bigEndianDefault) }
    var typeMenu by remember { mutableStateOf(false) }
    val textual = type == HexDataType.UTF8 || type == HexDataType.UTF16 || type == HexDataType.GBK
    val fieldStyle = LocalTextStyle.current.copy(textDirection = if (textual) TextDirection.Unspecified else TextDirection.Ltr)

    Card(Modifier.fillMaxWidth().padding(8.dp)) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.hex_find_type, stringResource(type.label))) },
                    singleLine = true,
                    textStyle = fieldStyle,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onFind(query, type, bigEndian, false) }) {
                    Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = stringResource(R.string.hex_previous))
                }
                IconButton(onClick = { onFind(query, type, bigEndian, true) }) {
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = stringResource(R.string.hex_next))
                }
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.hex_close_search)) }
            }
            if (!readOnly) {
                OutlinedTextField(
                    value = replacement,
                    onValueChange = { replacement = it },
                    label = { Text(stringResource(R.string.editor_replace_with)) },
                    singleLine = true,
                    textStyle = fieldStyle,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    OutlinedButton(onClick = { typeMenu = true }) { Text(stringResource(type.label)) }
                    DropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                        HexDataType.entries.forEach { t ->
                            DropdownMenuItem(text = { Text(stringResource(t.label)) }, onClick = { type = t; typeMenu = false })
                        }
                    }
                }
                FilterChip(
                    selected = bigEndian,
                    onClick = { bigEndian = !bigEndian },
                    enabled = type.endianSensitive,
                    label = { Text(stringResource(R.string.hex_big_endian)) },
                )
                if (!readOnly) {
                    TextButton(onClick = { onReplace(query, replacement, type, bigEndian, false) }) { Text(stringResource(R.string.common_replace)) }
                    TextButton(onClick = { onReplace(query, replacement, type, bigEndian, true) }) { Text(stringResource(R.string.editor_replace_all)) }
                }
            }
        }
    }
}

@Composable
private fun OffsetDialog(
    title: String,
    fields: List<String>,
    onConfirm: (List<Long>) -> Unit,
    onDismiss: () -> Unit,
) {
    var hex by remember { mutableStateOf(true) }
    val values = remember { mutableStateOf(List(fields.size) { "" }) }
    val parsed = values.value.map { HexCodec.parseOffset(it, hex) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = hex, onClick = { hex = true }, label = { Text(stringResource(R.string.hex_radix_hex)) })
                    FilterChip(selected = !hex, onClick = { hex = false }, label = { Text(stringResource(R.string.hex_radix_decimal)) })
                }
                fields.forEachIndexed { i, label ->
                    OutlinedTextField(
                        value = values.value[i],
                        onValueChange = { v -> values.value = values.value.toMutableList().also { it[i] = v } },
                        label = { Text(label) },
                        isError = values.value[i].isNotEmpty() && parsed[i] == null,
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(parsed.map { it ?: 0L }) },
                enabled = parsed.all { it != null },
            ) { Text(stringResource(R.string.common_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun ChoiceDialog(title: String, options: List<String>, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEachIndexed { i, label ->
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(i) }.padding(vertical = 12.dp),
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
