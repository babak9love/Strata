package dev.cluvex.strata.feature.search

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import kotlinx.coroutines.delay
import androidx.compose.ui.platform.testTag
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import dev.cluvex.strata.core.archive.ArchiveFormat
import dev.cluvex.strata.core.model.SelectionAction
import dev.cluvex.strata.core.model.Zoned
import dev.cluvex.strata.core.util.IntentUtils
import dev.cluvex.strata.feature.explorer.components.BottomBarActions
import dev.cluvex.strata.feature.explorer.components.CreateArchiveDialog
import dev.cluvex.strata.feature.explorer.components.ExplorerBottomBar
import dev.cluvex.strata.feature.props.PropertiesSheet
import dev.cluvex.strata.feature.settings.ToolbarTab
import dev.cluvex.strata.ui.rememberTextClipboard
import dev.cluvex.strata.core.props.DirStats
import dev.cluvex.strata.feature.explorer.ExplorerViewModel
import dev.cluvex.strata.feature.explorer.components.DeleteDialog
import dev.cluvex.strata.feature.explorer.components.InputDialog
import dev.cluvex.strata.feature.explorer.components.RenameDialog
import dev.cluvex.strata.feature.explorer.components.InputSelection
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.cluvex.strata.R
import dev.cluvex.strata.core.model.FileItem
import dev.cluvex.strata.core.search.NameMatchMode
import dev.cluvex.strata.core.search.SearchResult
import dev.cluvex.strata.ui.asPath
import dev.cluvex.strata.ui.expressive.ArtTone
import dev.cluvex.strata.ui.expressive.ExpressiveEmptyState
import dev.cluvex.strata.ui.isolated

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    root: String,
    explorer: ExplorerViewModel,
    recycleByDefault: Boolean,
    onBack: () -> Unit,
    onOpenItem: (FileItem) -> Unit,
    onShowInFolder: (FileItem) -> Unit,
    selectionLayout: Zoned<SelectionAction> = dev.cluvex.strata.core.model.ActionLayout.DEFAULT_SELECTION,
    rootAccess: Boolean = false,
    onOpenWith: (FileItem) -> Unit = {},
    onCompare: (FileItem, FileItem) -> Unit = { _, _ -> },
    onCustomize: (ToolbarTab) -> Unit = {},
    vm: SearchViewModel = viewModel(),
) {
    LaunchedEffect(root) { vm.initRoot(root) }
    val ui by vm.state.collectAsStateWithLifecycle()
    val selecting = ui.selected.isNotEmpty()
    val chosen = remember(ui.results, ui.selected) { ui.results.map { it.item }.filter { it.path in ui.selected } }
    var dialog by remember { mutableStateOf<SearchDialog>(SearchDialog.None) }
    BackHandler(enabled = selecting) { vm.clearSelection() }
    val context = LocalContext.current
    val textClipboard = rememberTextClipboard()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                title = {
                    Column {
                        Text(stringResource(R.string.common_search))
                        Text(
                            ui.root,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
            )
        },
        bottomBar = {
            if (selecting) ExplorerBottomBar(
                selectionCount = chosen.size,
                canBack = false,
                canForward = false,
                canUp = false,
                activeIsLeft = true,
                dual = false,
                clipboardCount = 0,
                clipboardIsMove = false,
                selectedFiles = chosen.count { !it.isDirectory },
                selection = selectionLayout,
                hiddenSelection = SearchHiddenActions,
                actions = BottomBarActions(
                    onClearSelection = vm::clearSelection,
                    onSelectAll = vm::selectAll,
                    onInvert = vm::invertSelection,
                    onCopyToClipboard = { explorer.copyItemsToClipboard(chosen, move = false); vm.clearSelection(); onBack() },
                    onCutToClipboard = { explorer.copyItemsToClipboard(chosen, move = true); vm.clearSelection(); onBack() },
                    onDelete = { if (!ui.running) dialog = SearchDialog.Delete(chosen) },
                    onRename = { if (!ui.running) chosen.singleOrNull()?.let { dialog = SearchDialog.Rename(it) } },
                    onCompress = { dialog = SearchDialog.Compress(chosen) },
                    onShare = { IntentUtils.share(context, chosen) },
                    onProperties = { dialog = SearchDialog.Properties(chosen) },
                    onCopyPath = {
                        textClipboard.setText(chosen.joinToString("\n") { it.path })
                        explorer.notifyPathsCopied(chosen.size)
                    },
                    onBookmarkSelection = { explorer.addBookmarks(chosen.map { it.path }); vm.clearSelection() },
                    onOpenWith = { chosen.singleOrNull()?.takeIf { !it.isDirectory }?.let(onOpenWith) },
                    onCompare = {
                        val two = chosen.filterNot { it.isDirectory }
                        if (two.size == 2) onCompare(two[0], two[1])
                    },
                    onCustomize = onCustomize,
                ),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            QueryForm(ui, vm)
            StatusBar(ui)
            HorizontalDivider()
            if (ui.finished && ui.results.isEmpty()) {
                ExpressiveEmptyState(
                    stringResource(R.string.search_nothing_found),
                    Modifier.fillMaxSize().padding(24.dp),
                    icon = Icons.Rounded.SearchOff,
                    tone = ArtTone.SECONDARY,
                    body = stringResource(R.string.search_nothing_found_body),
                    maxHero = 136.dp,
                )
            } else {
                ResultsList(ui, vm, onOpenItem, onShowInFolder)
            }
        }
    }
    when (val d = dialog) {
        SearchDialog.None -> Unit
        is SearchDialog.Rename -> RenameDialog(
            name = d.item.name, isDirectory = d.item.isDirectory,
            onConfirm = { name ->
                explorer.renameItem(d.item, name) { renamed -> vm.renameResult(d.item.path, renamed) }
                dialog = SearchDialog.None
            },
            onDismiss = { dialog = SearchDialog.None },
        )
        is SearchDialog.Compress -> CreateArchiveDialog(
            items = d.items,
            defaultName = d.items.singleOrNull()?.let { ArchiveFormat.ZIP.defaultName(it).removeSuffix(ArchiveFormat.ZIP.extension) }
                ?: java.io.File(d.items.firstOrNull()?.parentPath ?: "").name.ifEmpty { "archive" },
            onConfirm = { name, options, independent ->
                explorer.createArchiveIn(d.items, name, options, independent)
                vm.clearSelection()
                dialog = SearchDialog.None
            },
            onDismiss = { dialog = SearchDialog.None },
        )
        is SearchDialog.Properties -> PropertiesSheet(
            items = d.items,
            rootEnabled = rootAccess,
            onDismiss = { dialog = SearchDialog.None },
            onChanged = { explorer.refreshAll() },
        )
        is SearchDialog.Delete -> {
            var stats by remember { mutableStateOf<DirStats?>(null) }
            var counting by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) {
                runCatching { explorer.deleteStatsFor(d.items).collect { stats = it } }
                counting = false
            }
            DeleteDialog(
                count = d.items.size,
                stats = stats,
                counting = counting,
                recycleByDefault = recycleByDefault,
                allowRecycle = remember(d.items) { explorer.canRecycleItems(d.items) },
                onConfirm = { toBin ->
                    val paths = d.items.map { it.path }.toSet()
                    explorer.deleteItems(d.items, toBin) { vm.removeResults(paths) }
                    dialog = SearchDialog.None
                },
                onDismiss = { dialog = SearchDialog.None },
            )
        }
    }
}

private sealed interface SearchDialog {
    data object None : SearchDialog
    data class Rename(val item: FileItem) : SearchDialog
    data class Delete(val items: List<FileItem>) : SearchDialog
    data class Compress(val items: List<FileItem>) : SearchDialog
    data class Properties(val items: List<FileItem>) : SearchDialog
}

// These need a single destination folder of the explorer pane, which search results don't have.
private val SearchHiddenActions = setOf(
    SelectionAction.BATCH_RENAME,
    SelectionAction.MERGE,
    SelectionAction.LINKS,
    SelectionAction.VAULT,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueryForm(ui: SearchUiState, vm: SearchViewModel) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // Opening the search screen puts the cursor in the name field and brings up the keyboard.
    LaunchedEffect(Unit) {
        delay(150)
        runCatching { focusRequester.requestFocus() }
        keyboard?.show()
    }
    val startSearch = {
        focusManager.clearFocus()
        vm.start()
    }
    Card(Modifier.fillMaxWidth().padding(12.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = ui.namePattern,
                onValueChange = vm::setNamePattern,
                label = { Text(stringResource(R.string.search_file_name)) },
                placeholder = { Text(stringResource(R.string.search_file_name_hint)) },
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { startSearch() }),
                trailingIcon = {
                    if (ui.running) {
                        IconButton(onClick = vm::stop, modifier = Modifier.testTag("search-stop")) {
                            Icon(Icons.Rounded.Stop, contentDescription = stringResource(R.string.common_stop))
                        }
                    } else {
                        IconButton(onClick = startSearch, modifier = Modifier.testTag("search-start")) {
                            Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.common_search))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                NameMatchMode.entries.forEachIndexed { i, mode ->
                    SegmentedButton(
                        selected = ui.nameMode == mode,
                        onClick = { vm.setNameMode(mode) },
                        shape = SegmentedButtonDefaults.itemShape(i, NameMatchMode.entries.size),
                    ) { Text(stringResource(mode.label)) }
                }
            }
            OutlinedTextField(
                value = ui.content,
                onValueChange = vm::setContent,
                label = { Text(stringResource(R.string.search_contains_text)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(ui.contentRegex, { vm.setContentRegex(!ui.contentRegex) }, label = { Text(stringResource(R.string.search_mode_regex)) })
                FilterChip(ui.caseSensitive, { vm.setCaseSensitive(!ui.caseSensitive) }, label = { Text(stringResource(R.string.search_case)) })
                FilterChip(ui.includeSubdirs, { vm.setIncludeSubdirs(!ui.includeSubdirs) }, label = { Text(stringResource(R.string.search_subdirs)) })
                FilterChip(ui.includeHidden, { vm.setIncludeHidden(!ui.includeHidden) }, label = { Text(stringResource(R.string.search_hidden)) })
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TypeFilter.entries.forEach { t ->
                    FilterChip(
                        selected = ui.typeFilter == t,
                        onClick = { vm.setTypeFilter(t) },
                        label = { Text(stringResource(t.label)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusBar(ui: SearchUiState) {
    if (ui.running || ui.finished) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (ui.running) {
                Text(stringResource(R.string.search_scanned, ui.scanned, ui.results.size), style = MaterialTheme.typography.bodySmall)
            } else {
                val summary = if (ui.stoppedEarly) R.string.search_results_limit else R.string.search_results
                Text(stringResource(summary, ui.results.size), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (ui.running) LinearProgressIndicator(Modifier.fillMaxWidth())
}

@Composable
private fun ResultsList(
    ui: SearchUiState,
    vm: SearchViewModel,
    onOpenItem: (FileItem) -> Unit,
    onShowInFolder: (FileItem) -> Unit,
) {
    val selecting = ui.selected.isNotEmpty()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp)) {
        items(ui.results, key = { it.item.path }) { result ->
            val item = result.item
            ResultRow(
                result = result,
                selected = item.path in ui.selected,
                selecting = selecting,
                onClick = { if (selecting) vm.toggleSelect(item.path) else onOpenItem(item) },
                onLongClick = { vm.toggleSelect(item.path) },
                onShowInFolder = { onShowInFolder(item) },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun ResultRow(
    result: SearchResult,
    selected: Boolean,
    selecting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onShowInFolder: () -> Unit,
) {
    val shape = RoundedCornerShape(14.dp)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        shape = shape,
        colors = if (selected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else CardDefaults.cardColors(),
    ) {
        Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (selecting) Checkbox(checked = selected, onCheckedChange = null, modifier = Modifier.padding(end = 12.dp))
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(isolated(result.item.name), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    result.item.parentPath ?: result.item.path,
                    style = MaterialTheme.typography.bodySmall.asPath(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (result.contentHits.isNotEmpty()) {
                    val first = result.contentHits.first()
                    Text(
                        stringResource(R.string.search_hit, result.contentHits.size, first.line, first.preview),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            if (!selecting) IconButton(onClick = onShowInFolder) {
                Icon(Icons.Rounded.FolderOpen, contentDescription = stringResource(R.string.search_show_in_folder))
            }
        }
    }
}

private val NameMatchMode.label: Int
    get() = when (this) {
        NameMatchMode.GLOB -> R.string.search_mode_glob
        NameMatchMode.REGEX -> R.string.search_mode_regex
        NameMatchMode.PLAIN -> R.string.search_mode_plain
    }

private val TypeFilter.label: Int
    get() = when (this) {
        TypeFilter.ALL -> R.string.search_type_all
        TypeFilter.FILES -> R.string.search_type_files
        TypeFilter.DIRS -> R.string.search_type_dirs
    }
