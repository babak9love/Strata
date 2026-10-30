package dev.cluvex.strata.feature.trash

import dev.cluvex.strata.ui.expressive.popupBorder
import dev.cluvex.strata.feature.explorer.components.FileNameText
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Settings
import dev.cluvex.strata.ui.expressive.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.cluvex.strata.R
import dev.cluvex.strata.core.model.FileItem
import dev.cluvex.strata.core.trash.TrashEntry
import dev.cluvex.strata.core.util.FileKinds
import dev.cluvex.strata.core.util.Formatters
import dev.cluvex.strata.feature.explorer.components.TaskOverlay
import dev.cluvex.strata.ui.expressive.Art
import dev.cluvex.strata.ui.expressive.ArtTone
import dev.cluvex.strata.ui.expressive.ExpressiveDialog
import dev.cluvex.strata.ui.expressive.ExpressiveEmptyState
import dev.cluvex.strata.ui.expressive.HeroShapes

private sealed interface BinDialog {
    data object None : BinDialog
    data object Empty : BinDialog
    data object Settings : BinDialog
    data class Purge(val entries: List<TrashEntry>) : BinDialog
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecycleBinScreen(
    onBack: () -> Unit,
    onChanged: () -> Unit,
    trashByDefault: Boolean = true,
    autoCleanDays: Int = 0,
    onTrashByDefault: (Boolean) -> Unit = {},
    onAutoCleanDays: (Int) -> Unit = {},
    vm: RecycleBinViewModel = viewModel(),
) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<BinDialog>(BinDialog.None) }

    LaunchedEffect(Unit) { vm.load() }
    LaunchedEffect(ui.message) {
        ui.message?.let {
            onChanged()
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back)) }
                },
                title = {
                    Column {
                        Text(stringResource(R.string.common_recycle_bin))
                        Text(
                            stringResource(R.string.trash_summary, ui.entries.size, Formatters.size(ui.totalSize)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    if (ui.entries.isNotEmpty()) {
                        IconButton(onClick = vm::selectAll) { Icon(Icons.Rounded.SelectAll, contentDescription = stringResource(R.string.common_select_all)) }
                        IconButton(onClick = { dialog = BinDialog.Empty }) {
                            Icon(Icons.Rounded.DeleteSweep, contentDescription = stringResource(R.string.trash_empty_bin))
                        }
                    }
                    // Last, so it stays in the same spot whether or not the other two are shown.
                    IconButton(onClick = { dialog = BinDialog.Settings }) {
                        Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.common_settings))
                    }
                },
            )
        },
        bottomBar = {
            if (ui.selected.isNotEmpty()) {
                BottomAppBar {
                    IconButton(onClick = vm::clearSelection) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.common_clear_selection)) }
                    Text(stringResource(R.string.trash_selected, ui.selected.size), Modifier.weight(1f))
                    TextButton(onClick = { vm.restore(ui.selectedEntries) }) {
                        Icon(Icons.Rounded.RestoreFromTrash, null)
                        Text(stringResource(R.string.trash_restore), Modifier.padding(start = 6.dp))
                    }
                    TextButton(onClick = { dialog = BinDialog.Purge(ui.selectedEntries) }) {
                        Icon(Icons.Rounded.DeleteForever, null, tint = MaterialTheme.colorScheme.error)
                        Text(stringResource(R.string.common_delete), Modifier.padding(start = 6.dp), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                ui.loading && ui.entries.isEmpty() -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                ui.entries.isEmpty() -> EmptyBin()
                else -> LazyColumn(
                    contentPadding = PaddingValues(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(ui.entries, key = { it.info.id }) { entry ->
                        TrashRow(
                            entry = entry,
                            selected = entry.info.id in ui.selected,
                            onToggle = { vm.toggle(entry.info.id) },
                            onRestore = { vm.restore(listOf(entry)) },
                            onPurge = { dialog = BinDialog.Purge(listOf(entry)) },
                        )
                    }
                }
            }
        }
    }

    when (val d = dialog) {
        BinDialog.None -> Unit
        BinDialog.Settings -> RecycleBinSettingsDialog(
            trashByDefault = trashByDefault,
            autoCleanDays = autoCleanDays,
            onTrashByDefault = onTrashByDefault,
            onAutoCleanDays = onAutoCleanDays,
            onDismiss = { dialog = BinDialog.None },
        )
        BinDialog.Empty -> ExpressiveDialog(
            onDismissRequest = { dialog = BinDialog.None },
            title = stringResource(R.string.trash_empty_bin),
            icon = Icons.Rounded.DeleteForever,
            tone = ArtTone.ERROR,
            shapes = HeroShapes.danger,
            text = { Text(stringResource(R.string.trash_empty_confirm, ui.entries.size)) },
            confirmButton = {
                TextButton(onClick = { vm.empty(); dialog = BinDialog.None }) {
                    Text(stringResource(R.string.trash_empty), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { dialog = BinDialog.None }) { Text(stringResource(R.string.common_cancel)) } },
        )
        is BinDialog.Purge -> ExpressiveDialog(
            onDismissRequest = { dialog = BinDialog.None },
            title = stringResource(R.string.trash_delete_permanently),
            icon = Icons.Rounded.DeleteForever,
            tone = ArtTone.ERROR,
            shapes = HeroShapes.danger,
            text = { Text(stringResource(R.string.trash_purge_confirm, d.entries.size)) },
            confirmButton = {
                TextButton(onClick = { vm.purge(d.entries); dialog = BinDialog.None }) {
                    Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { dialog = BinDialog.None }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    ui.task?.let { TaskOverlay(it) }
}

/** The same two recycle bin options as in Settings, so they can be changed from the bin itself. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecycleBinSettingsDialog(
    trashByDefault: Boolean,
    autoCleanDays: Int,
    onTrashByDefault: (Boolean) -> Unit,
    onAutoCleanDays: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.common_recycle_bin)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.settings_trash_default), style = MaterialTheme.typography.titleSmall)
                        Text(
                            stringResource(R.string.settings_trash_default_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = trashByDefault, onCheckedChange = onTrashByDefault)
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.settings_trash_clean), style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (days in listOf(0, 7, 30, 90)) {
                            FilterChip(
                                selected = autoCleanDays == days,
                                onClick = { onAutoCleanDays(days) },
                                label = {
                                    Text(
                                        if (days == 0) stringResource(R.string.settings_never)
                                        else pluralStringResource(R.plurals.settings_days, days, days),
                                    )
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_done)) } },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrashRow(
    entry: TrashEntry,
    selected: Boolean,
    onToggle: () -> Unit,
    onRestore: () -> Unit,
    onPurge: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val asItem = FileItem(
        name = entry.name,
        path = entry.info.originalPath,
        isDirectory = entry.info.isDirectory,
        size = entry.info.size,
        lastModified = entry.info.deletedAt,
        isHidden = false,
        canRead = true,
        canWrite = true,
    )
    ListItem(
        colors = ListItemDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        ),
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onToggle, onLongClick = onToggle),
        leadingContent = {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (selected) Icons.Rounded.Check else FileKinds.of(asItem).icon,
                    contentDescription = null,
                    tint = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        content = { FileNameText(entry.name, maxLines = 1) },
        supportingContent = {
            Text(
                entry.originalParent + "\n" + stringResource(R.string.trash_deleted_at, Formatters.date(entry.info.deletedAt), Formatters.size(entry.info.size)),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.common_more)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.trash_restore)) },
                        leadingIcon = { Icon(Icons.Rounded.RestoreFromTrash, null) },
                        onClick = { menu = false; onRestore() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.trash_delete_permanently)) },
                        leadingIcon = { Icon(Icons.Rounded.DeleteForever, null) },
                        onClick = { menu = false; onPurge() },
                    )
                }
            }
        },
    )
}

@Composable
private fun EmptyBin() {
    ExpressiveEmptyState(
        stringResource(R.string.trash_empty_title),
        Modifier.fillMaxSize().padding(32.dp),
        art = Art.EMPTY_BIN,
        body = stringResource(R.string.trash_empty_body),
        maxHero = 200.dp,
    )
}
