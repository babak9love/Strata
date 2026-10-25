package dev.cluvex.strata.feature.settings

import android.os.Build
import android.os.Environment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.WrapText
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.AutoDelete
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.DataObject
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.FolderSpecial
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.ZoomIn
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.Numbers
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.ScreenLockPortrait
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.SettingsBackupRestore
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SpaceBar
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.ViewColumn
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import dev.cluvex.strata.BuildConfig
import dev.cluvex.strata.R
import dev.cluvex.strata.core.bookmarks.BookmarkRepository
import dev.cluvex.strata.core.data.BackupLocations
import dev.cluvex.strata.core.data.EditorPrefs
import dev.cluvex.strata.core.data.SettingsBackup
import dev.cluvex.strata.core.media.FolderIconStore
import dev.cluvex.strata.core.data.StrataSettings
import dev.cluvex.strata.core.remote.RemoteConnectionRepository
import dev.cluvex.strata.core.remote.RemoteConnections
import dev.cluvex.strata.core.fs.LocalFileAccess
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.i18n.trPlural
import dev.cluvex.strata.core.model.ConflictChoice
import dev.cluvex.strata.core.model.DateStyle
import dev.cluvex.strata.core.model.ItemScale
import dev.cluvex.strata.core.model.ReaderTheme
import dev.cluvex.strata.core.model.SizeUnits
import dev.cluvex.strata.core.model.SortBy
import dev.cluvex.strata.core.model.StartFolder
import dev.cluvex.strata.core.model.UiDensity
import dev.cluvex.strata.core.model.ViewMode
import dev.cluvex.strata.core.open.OpenResolver
import dev.cluvex.strata.core.open.OpenTarget
import dev.cluvex.strata.core.root.RootState
import dev.cluvex.strata.core.shizuku.ShizukuManager
import dev.cluvex.strata.core.text.GrammarCatalog
import dev.cluvex.strata.core.util.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

@StringRes
private fun SortBy.label(): Int = when (this) {
    SortBy.NAME -> R.string.common_name
    SortBy.SIZE -> R.string.common_size
    SortBy.DATE -> R.string.common_date
    SortBy.TYPE -> R.string.common_type
}

@Composable
fun FileListPage(settings: StrataSettings, act: SettingsActions, onBack: () -> Unit) {
    val l = settings.list
    val now = remember { System.currentTimeMillis() }
    SettingsPageScaffold(stringResource(R.string.settings_page_file_list), onBack, subtitle = stringResource(R.string.settings_file_list_subtitle)) {
        ThemePreview(settings)
        SettingsGroup(stringResource(R.string.settings_group_layout)) {
            if (!settings.dualPanePreferred) choice(tr(R.string.settings_view_as), ViewMode.entries, l.viewMode, { tr(it.label) }, { v -> act.list { it.copy(viewMode = v) } }, icon = Icons.Rounded.GridView)
            if (!settings.dualPanePreferred && l.viewMode == ViewMode.GRID) {
                choice(
                    tr(R.string.settings_grid_columns), listOf(0, 4), l.gridColumns,
                    { if (it == 0) tr(R.string.settings_grid_columns_auto) else it.toString() },
                    { v -> act.list { it.copy(gridColumns = v) } },
                    icon = Icons.Rounded.ViewColumn,
                )
                switch(tr(R.string.settings_tile_frame), l.tileFrame, { v -> act.list { it.copy(tileFrame = v) } }, summary = tr(R.string.settings_tile_frame_summary), icon = Icons.Rounded.GridView)
            }
            percentSlider(
                tr(R.string.settings_item_size), l.itemScale, ItemScale.MIN, ItemScale.MAX,
                { v -> act.list { it.copy(itemScale = ItemScale.clamp(v)) } },
                icon = Icons.Rounded.ZoomIn,
            )
            percentSlider(
                tr(R.string.settings_folder_size), l.folderScale, ItemScale.MIN, ItemScale.MAX,
                { v -> act.list { it.copy(folderScale = ItemScale.clamp(v)) } },
                icon = Icons.Rounded.ZoomIn,
            )
            percentSlider(
                tr(R.string.settings_name_size), l.nameScale, ItemScale.MIN, ItemScale.MAX,
                { v -> act.list { it.copy(nameScale = ItemScale.clamp(v)) } },
                icon = Icons.Rounded.ZoomIn,
            )
            percentSlider(
                tr(R.string.settings_meta_size), l.metaScale, ItemScale.MIN, ItemScale.MAX,
                { v -> act.list { it.copy(metaScale = ItemScale.clamp(v)) } },
                icon = Icons.Rounded.ZoomIn,
            )
            choice(tr(R.string.settings_density), UiDensity.entries, settings.uiDensity, { tr(it.label) }, { d -> act.run { setUiDensity(d) } }, icon = Icons.Rounded.ViewColumn)
            switch(tr(R.string.settings_show_parent_entry), l.showParentEntry, { v -> act.list { it.copy(showParentEntry = v) } }, summary = tr(R.string.settings_show_parent_entry_summary), icon = Icons.Rounded.ArrowUpward)
            switch(tr(R.string.settings_wrap_names), l.wrapNames, { v -> act.list { it.copy(wrapNames = v) } }, summary = tr(R.string.settings_wrap_names_summary), icon = Icons.AutoMirrored.Rounded.WrapText)
        }
        SettingsGroup(stringResource(R.string.settings_group_files)) {
            val photo = tr(R.string.settings_sample_photo)
            switch(tr(R.string.common_show_hidden), settings.showHidden, { v -> act.run { setShowHidden(v) } }, summary = tr(R.string.settings_show_hidden_summary), icon = if (settings.showHidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff)
            switch(tr(R.string.settings_show_extensions), l.showExtensions, { v -> act.list { it.copy(showExtensions = v) } }, summary = tr(R.string.settings_show_extensions_summary, "$photo.jpg", photo), icon = Icons.Rounded.TextFields)
            switch(
                tr(R.string.settings_folders_first),
                settings.sortOrder.dirsFirst,
                { v -> act.run { setSortOrder(settings.sortOrder.copy(dirsFirst = v)) } },
                summary = tr(R.string.settings_folders_first_summary),
                icon = Icons.Rounded.FolderSpecial,
            )
            pick(
                tr(R.string.settings_default_sort),
                SortBy.entries,
                settings.sortOrder.by,
                { tr(it.label()) },
                { by -> act.run { setSortOrder(settings.sortOrder.copy(by = by)) } },
                icon = Icons.AutoMirrored.Rounded.Sort,
            )
            switch(tr(R.string.settings_descending), settings.sortOrder.descending, { v -> act.run { setSortOrder(settings.sortOrder.copy(descending = v)) } }, summary = tr(R.string.settings_descending_summary))
        }
        SettingsGroup(stringResource(R.string.settings_group_previews)) {
            switch(tr(R.string.settings_thumbnails), l.thumbnails, { v -> act.list { it.copy(thumbnails = v) } }, summary = tr(R.string.settings_thumbnails_summary), icon = Icons.Rounded.Image)
            switch(tr(R.string.settings_apk_icons), l.apkIcons, { v -> act.list { it.copy(apkIcons = v) } }, summary = tr(R.string.settings_apk_icons_summary), icon = Icons.Rounded.Android)
        }
        SettingsGroup(stringResource(R.string.common_details)) {
            val frameless = l.viewMode == ViewMode.GRID && !settings.dualPanePreferred && !l.tileFrame
            if (!frameless) switch(tr(R.string.settings_show_date), l.showDate, { v -> act.list { it.copy(showDate = v) } }, icon = Icons.Rounded.CalendarMonth)
            switch(tr(R.string.settings_show_size), l.showSize, { v -> act.list { it.copy(showSize = v) } }, icon = Icons.Rounded.Straighten)
            if (!frameless) pick(
                tr(R.string.settings_date_format),
                DateStyle.entries,
                l.dateStyle,
                { Formatters.listDate(now - 300_000L, it, l.use24Hour, now) },
                { d -> act.list { it.copy(dateStyle = d) } },
                icon = Icons.Rounded.Schedule,
                detail = { if (it == DateStyle.RELATIVE) tr(R.string.settings_date_relative_detail) else null },
            )
            if (!frameless) switch(tr(R.string.settings_24_hour, 24), l.use24Hour, { v -> act.list { it.copy(use24Hour = v) } })
            choice(
                tr(R.string.settings_size_units),
                SizeUnits.entries,
                l.sizeUnits,
                { if (it == SizeUnits.BINARY) tr(R.string.settings_units_binary, 1, 1024) else tr(R.string.settings_units_decimal, 1, 1000) },
                { u -> act.list { it.copy(sizeUnits = u) } },
                icon = Icons.Rounded.Numbers,
            )
        }
    }
}

@Composable
fun BehaviorPage(settings: StrataSettings, act: SettingsActions, activeFolder: String, onBack: () -> Unit) {
    val b = settings.behavior
    var editPath by remember { mutableStateOf(false) }
    SettingsPageScaffold(stringResource(R.string.settings_page_behavior), onBack, subtitle = stringResource(R.string.settings_behavior_subtitle)) {
        SettingsGroup(stringResource(R.string.settings_group_starting)) {
            pick(
                tr(R.string.settings_start_in),
                StartFolder.entries,
                b.startFolder,
                { tr(it.label) },
                { f -> act.behavior { it.copy(startFolder = f, startPath = if (f == StartFolder.CUSTOM && it.startPath.isBlank()) activeFolder else it.startPath) } },
                icon = Icons.Rounded.FolderOpen,
            )
            if (b.startFolder == StartFolder.CUSTOM) {
                link(tr(R.string.settings_start_folder), summary = b.startPath.ifBlank { tr(R.string.settings_not_set) }, icon = Icons.Rounded.CreateNewFolder) { editPath = true }
            }
            switch(tr(R.string.settings_two_panes), settings.dualPanePreferred, { v -> act.run { setDualPanePreferred(v) }; if (v) act.list { it.copy(viewMode = ViewMode.LIST) } }, summary = tr(R.string.settings_two_panes_summary), icon = Icons.Rounded.ViewColumn)
        }
        SettingsGroup(stringResource(R.string.settings_group_gestures)) {
            switch(tr(R.string.settings_double_back), b.doubleBackToExit, { v -> act.behavior { it.copy(doubleBackToExit = v) } }, summary = tr(R.string.settings_double_back_summary), icon = Icons.AutoMirrored.Rounded.ExitToApp)
            switch(tr(R.string.settings_icon_tap), b.iconTapSelects, { v -> act.behavior { it.copy(iconTapSelects = v) } }, summary = tr(R.string.settings_icon_tap_summary), icon = Icons.Rounded.SelectAll)
        }
        SettingsGroup(stringResource(R.string.settings_group_working)) {
            switch(
                tr(R.string.settings_screen_on_tasks),
                b.keepScreenOnTasks,
                { v -> act.behavior { it.copy(keepScreenOnTasks = v) } },
                summary = tr(R.string.settings_screen_on_tasks_summary),
                icon = Icons.Rounded.LightMode,
            )
        }
    }
    if (editPath) {
        TextSettingDialog(
            title = stringResource(R.string.settings_start_folder),
            initial = b.startPath.ifBlank { activeFolder },
            extra = stringResource(R.string.settings_use_current_folder) to activeFolder,
            onConfirm = { path -> editPath = false; act.behavior { it.copy(startPath = path) } },
            onDismiss = { editPath = false },
        )
    }
}

@Composable
fun OperationsPage(settings: StrataSettings, act: SettingsActions, onOpenRecycleBin: () -> Unit, onBack: () -> Unit) {
    val o = settings.operations
    SettingsPageScaffold(stringResource(R.string.settings_page_operations), onBack, subtitle = stringResource(R.string.settings_operations_subtitle)) {
        SettingsGroup(stringResource(R.string.settings_group_copy_move)) {
            val photo = tr(R.string.settings_sample_photo)
            pick(
                tr(R.string.settings_conflict),
                ConflictChoice.entries,
                o.conflict,
                { tr(it.label) },
                { c -> act.operations { it.copy(conflict = c) } },
                icon = Icons.Rounded.DataObject,
                detail = {
                    when (it) {
                        ConflictChoice.ASK -> tr(R.string.settings_conflict_ask_detail)
                        ConflictChoice.KEEP_BOTH -> tr(R.string.settings_conflict_keep_both_detail, "$photo (1).jpg")
                        ConflictChoice.OVERWRITE -> tr(R.string.settings_conflict_replace_detail)
                        ConflictChoice.SKIP -> tr(R.string.settings_conflict_skip_detail)
                    }
                },
            )
            switch(
                tr(R.string.settings_keep_mtime),
                o.keepModifiedTime,
                { v -> act.operations { it.copy(keepModifiedTime = v) } },
                summary = tr(R.string.settings_keep_mtime_summary),
                icon = Icons.Rounded.Schedule,
            )
            switch(
                tr(R.string.settings_undo),
                o.undoEnabled,
                { v -> act.operations { it.copy(undoEnabled = v) } },
                summary = tr(R.string.settings_undo_summary),
                icon = Icons.AutoMirrored.Rounded.Undo,
            )
        }
        SettingsGroup(stringResource(R.string.common_recycle_bin)) {
            switch(
                tr(R.string.settings_trash_default),
                settings.trashByDefault,
                { v -> act.run { setTrashByDefault(v) } },
                summary = tr(R.string.settings_trash_default_summary),
                icon = Icons.Rounded.Delete,
            )
            choice(
                tr(R.string.settings_trash_clean),
                listOf(0, 7, 30, 90),
                settings.trashAutoCleanDays,
                { if (it == 0) tr(R.string.settings_never) else trPlural(R.plurals.settings_days, it, it) },
                { d -> act.run { setTrashAutoCleanDays(d) } },
                icon = Icons.Rounded.AutoDelete,
            )
            link(tr(R.string.settings_open_recycle_bin), icon = Icons.Rounded.Delete, tint = Tint.NEUTRAL, onClick = onOpenRecycleBin)
        }
    }
}

@Composable
fun ReaderSettingsPage(settings: StrataSettings, act: SettingsActions, onBack: () -> Unit) {
    val r = settings.reader
    var forgotten by remember { mutableStateOf(false) }
    SettingsPageScaffold(stringResource(R.string.settings_page_reader), onBack, subtitle = stringResource(R.string.settings_reader_subtitle)) {
        SettingsGroup(stringResource(R.string.settings_group_pages)) {
            choice(tr(R.string.settings_page_color), ReaderTheme.entries.toList(), r.theme, { tr(it.label) }, { t -> act.reader { it.copy(theme = t) } }, icon = Icons.Rounded.Contrast, summary = tr(R.string.settings_page_color_summary))
            switch(tr(R.string.settings_reader_screen_on), r.keepScreenOn, { v -> act.reader { it.copy(keepScreenOn = v) } }, icon = Icons.Rounded.LightMode)
        }
        SettingsGroup(stringResource(R.string.settings_group_memory), footer = stringResource(R.string.settings_memory_footer)) {
            link(
                tr(R.string.settings_forget_spots),
                summary = tr(if (forgotten) R.string.settings_forget_spots_done else R.string.settings_forget_spots_summary),
                icon = Icons.Rounded.History,
                chevron = false,
            ) {
                act.run { forgetReadingSpots() }
                forgotten = true
            }
        }
    }
}

@Composable
fun EditorSettingsPage(settings: StrataSettings, act: SettingsActions, onBack: () -> Unit) {
    val e = settings.editor
    val context = LocalContext.current
    val themes by produceState(emptyList<Pair<String, String>>()) {
        value = withContext(Dispatchers.IO) {
            runCatching { GrammarCatalog.parseThemes(context.assets.open("textmate/themes.json").bufferedReader().use { it.readText() }) }
                .getOrDefault(emptyList()).map { it.id to it.name }
        }
    }
    SettingsPageScaffold(stringResource(R.string.settings_page_editor), onBack, subtitle = stringResource(R.string.settings_editor_subtitle)) {
        SettingsGroup(stringResource(R.string.settings_group_look)) {
            val options = listOf("auto" to tr(R.string.settings_editor_follow_app)) + themes
            pick(
                tr(R.string.settings_editor_theme),
                options.map { it.first },
                e.theme,
                { id -> options.firstOrNull { it.first == id }?.second ?: id },
                { id -> act.editor { it.copy(theme = id) } },
                icon = Icons.Rounded.Style,
            )
            slider(
                tr(R.string.settings_font_size),
                e.textSize,
                8f..32f,
                steps = 23,
                valueLabel = { tr(R.string.settings_font_size_value, it.roundToInt()) },
                onChange = { v -> act.editor { it.copy(textSize = v.roundToInt().toFloat()) } },
                icon = Icons.Rounded.FormatSize,
            )
            switch(tr(R.string.settings_line_numbers), e.lineNumbers, { v -> act.editor { it.copy(lineNumbers = v) } })
            switch(tr(R.string.settings_pin_line_numbers), e.pinLineNumbers, { v -> act.editor { it.copy(pinLineNumbers = v) } }, summary = tr(R.string.settings_pin_line_numbers_summary))
            switch(tr(R.string.settings_highlight_line), e.highlightLine, { v -> act.editor { it.copy(highlightLine = v) } })
            switch(tr(R.string.settings_highlight_brackets), e.highlightBrackets, { v -> act.editor { it.copy(highlightBrackets = v) } })
            switch(tr(R.string.settings_indent_guides), e.indentGuides, { v -> act.editor { it.copy(indentGuides = v) } }, summary = tr(R.string.settings_indent_guides_summary))
            switch(tr(R.string.settings_show_whitespace), e.showWhitespace, { v -> act.editor { it.copy(showWhitespace = v) } }, icon = Icons.Rounded.SpaceBar)
        }
        SettingsGroup(stringResource(R.string.settings_group_typing)) {
            choice(tr(R.string.settings_tab_width), EditorPrefs.TAB_WIDTHS, e.tabWidth, { Formatters.number(it.toLong()) }, { w -> act.editor { it.copy(tabWidth = w) } })
            switch(tr(R.string.settings_auto_indent), e.autoIndent, { v -> act.editor { it.copy(autoIndent = v) } }, summary = tr(R.string.settings_auto_indent_summary))
            switch(tr(R.string.settings_auto_close), e.autoClosePairs, { v -> act.editor { it.copy(autoClosePairs = v) } })
            switch(tr(R.string.settings_word_wrap), e.wordWrap, { v -> act.editor { it.copy(wordWrap = v) } }, icon = Icons.AutoMirrored.Rounded.WrapText)
            switch(tr(R.string.settings_symbol_bar), e.symbolBar, { v -> act.editor { it.copy(symbolBar = v) } }, summary = tr(R.string.settings_symbol_bar_summary))
        }
        SettingsGroup(stringResource(R.string.settings_group_scrolling)) {
            switch(tr(R.string.settings_sticky_scroll), e.stickyScroll, { v -> act.editor { it.copy(stickyScroll = v) } }, summary = tr(R.string.settings_sticky_scroll_summary))
            switch(tr(R.string.settings_pinch_zoom), e.pinchZoom, { v -> act.editor { it.copy(pinchZoom = v) } })
            switch(tr(R.string.settings_cursor_animation), e.cursorAnimation, { v -> act.editor { it.copy(cursorAnimation = v) } })
            switch(tr(R.string.settings_editor_screen_on), e.keepScreenOn, { v -> act.editor { it.copy(keepScreenOn = v) } }, summary = tr(R.string.settings_editor_screen_on_summary))
        }
    }
}

@Composable
fun AppsPage(settings: StrataSettings, act: SettingsActions, onBack: () -> Unit) {
    var editDir by remember { mutableStateOf(false) }
    var editPattern by remember { mutableStateOf(false) }
    SettingsPageScaffold(stringResource(R.string.settings_page_apps), onBack, subtitle = stringResource(R.string.settings_apps_subtitle)) {
        SettingsGroup(stringResource(R.string.settings_group_installing)) {
            switch(
                tr(R.string.settings_verify),
                settings.verifyBeforeInstall,
                { v -> act.run { setVerifyBeforeInstall(v) } },
                summary = tr(R.string.settings_verify_summary),
                icon = Icons.Rounded.CheckCircle,
            )
            switch(
                tr(R.string.settings_install_root),
                settings.installWithRoot && settings.rootAccess,
                { v -> act.run { setInstallWithRoot(v) } },
                summary = tr(if (settings.rootAccess) R.string.settings_install_root_summary else R.string.settings_install_root_needs),
                icon = Icons.Rounded.Key,
                enabled = settings.rootAccess,
            )
        }
        SettingsGroup(stringResource(R.string.settings_group_extracting)) {
            link(tr(R.string.settings_apk_folder), summary = settings.apkExtractDir.ifBlank { tr(R.string.settings_apk_folder_default) }, icon = Icons.Rounded.Download) { editDir = true }
            link(
                tr(R.string.settings_name_pattern),
                summary = tr(R.string.settings_name_pattern_summary, settings.apkNamePattern, tr(R.string.settings_name_pattern_legend)),
                icon = Icons.Rounded.TextFields,
            ) { editPattern = true }
        }
    }
    if (editDir) {
        TextSettingDialog(
            title = stringResource(R.string.settings_apk_folder),
            initial = settings.apkExtractDir,
            hint = stringResource(R.string.settings_apk_folder_hint),
            onConfirm = { v -> editDir = false; act.run { setApkExtraction(v, settings.apkNamePattern) } },
            onDismiss = { editDir = false },
        )
    }
    if (editPattern) {
        TextSettingDialog(
            title = stringResource(R.string.settings_name_pattern),
            initial = settings.apkNamePattern,
            hint = stringResource(R.string.settings_name_pattern_legend),
            onConfirm = { v -> editPattern = false; act.run { setApkExtraction(settings.apkExtractDir, v.ifBlank { "{A}_{V}" }) } },
            onDismiss = { editPattern = false },
        )
    }
}

@Composable
fun AccessPage(settings: StrataSettings, act: SettingsActions, rootState: RootState, onRootAccess: (Boolean) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val shizuku by ShizukuManager.state.collectAsState()
    LaunchedEffect(Unit) { ShizukuManager.refresh() }
    SettingsPageScaffold(stringResource(R.string.settings_page_access), onBack, subtitle = stringResource(R.string.settings_access_subtitle)) {
        SettingsGroup(stringResource(R.string.settings_group_root)) {
            switch(
                tr(R.string.settings_root_access),
                settings.rootAccess,
                onRootAccess,
                summary = when {
                    rootState == RootState.DENIED -> tr(R.string.settings_root_denied)
                    settings.rootAccess -> tr(R.string.settings_root_on)
                    else -> tr(R.string.settings_root_off)
                },
                icon = Icons.Rounded.Shield,
                tint = Tint.ERROR,
            )
        }
        SettingsGroup(stringResource(R.string.settings_shizuku), footer = stringResource(R.string.settings_shizuku_footer, 11)) {
            switch(
                tr(R.string.settings_shizuku_access),
                settings.shizukuAccess,
                { enabled ->
                    act.run { setShizukuAccess(enabled) }
                    if (enabled) scope.launch { ShizukuManager.ensurePermission() }
                },
                summary = when {
                    !settings.shizukuAccess -> tr(R.string.settings_shizuku_off)
                    !ShizukuManager.isInstalled -> tr(R.string.settings_shizuku_install)
                    shizuku == ShizukuManager.Status.NOT_RUNNING -> tr(R.string.settings_shizuku_start)
                    shizuku == ShizukuManager.Status.NEEDS_PERMISSION -> tr(R.string.settings_shizuku_allow)
                    else -> tr(R.string.settings_shizuku_on)
                },
                icon = Icons.Rounded.SdStorage,
            )
        }
    }
}

@Composable
fun OpeningPage(settings: StrataSettings, act: SettingsActions, onBack: () -> Unit) {
    SettingsPageScaffold(stringResource(R.string.settings_page_opening), onBack, subtitle = stringResource(R.string.settings_opening_subtitle)) {
        if (settings.openDefaults.isEmpty()) {
            SettingNote(stringResource(R.string.settings_opening_none))
        } else {
            SettingsGroup(stringResource(R.string.settings_group_defaults), footer = stringResource(R.string.settings_defaults_footer)) {
                settings.openDefaults.toSortedMap().forEach { (ext, target) ->
                    link(
                        if (ext == OpenResolver.NO_EXTENSION) tr(R.string.settings_no_extension) else ".$ext",
                        summary = when (target) {
                            is OpenTarget.BuiltIn -> tr(target.opener.label)
                            is OpenTarget.App -> target.packageName
                        },
                        chevron = false,
                        trailing = {
                            IconButton(onClick = { act.run { removeOpenDefault(ext) } }) {
                                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.settings_remove_default, ext))
                            }
                        },
                    ) {}
                }
            }
        }
    }
}

@Composable
fun BackupPage(act: SettingsActions, activeFolder: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val bookmarkRepo = remember { BookmarkRepository(context.applicationContext) }
    val remoteRepo = remember { RemoteConnectionRepository(context.applicationContext) }
    val downloads = remember { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) }
    val target = remember(activeFolder) { BackupLocations.exportFolder(activeFolder, downloads) }
    var refresh by remember { mutableIntStateOf(0) }
    val backups by produceState(emptyList<File>(), target, refresh) {
        value = withContext(Dispatchers.IO) { BackupLocations.find(listOf(target, File(downloads, "Strata"))) }
    }
    var importing by remember { mutableStateOf<(suspend () -> String)?>(null) }
    var importName by remember { mutableStateOf<String?>(null) }
    var resetting by remember { mutableStateOf(false) }

    fun importText(read: suspend () -> String) {
        scope.launch {
            val message = runCatching {
                val text = read()
                val applied = act.repo.importBackup(text)
                val extras = SettingsBackup.extras(text)
                extras.bookmarks?.let { saved ->
                    val paths = saved.mapTo(HashSet()) { it.path }
                    bookmarkRepo.edit { current -> current.filter { it.path !in paths } + saved }
                }
                extras.connections?.let { saved ->
                    remoteRepo.edit { current -> saved.fold(current) { acc, c -> RemoteConnections.upsert(acc, c) } }
                }
                // Folder icons: folders missing on this device are skipped, and a failure here never stops the restore.
                extras.folderIcons?.let { saved ->
                    runCatching { withContext(Dispatchers.IO) { FolderIconStore.restore(saved) } }
                }
                applied
            }
                .fold({ n -> trPlural(R.plurals.settings_restored, n, n) }, { e -> e.message ?: tr(R.string.settings_backup_read_failed) })
            snackbar.showSnackbar(message)
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importName = uri.lastPathSegment
            importing = {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = input.readNBytesCompat(BackupLocations.MAX_BYTES.toInt() + 1)
                        require(bytes.size <= BackupLocations.MAX_BYTES) { tr(R.string.settings_backup_too_big) }
                        String(bytes, Charsets.UTF_8)
                    } ?: throw IllegalArgumentException(tr(R.string.settings_backup_open_failed))
                }
            }
        }
    }

    SettingsPageScaffold(stringResource(R.string.settings_page_backup), onBack, subtitle = stringResource(R.string.settings_backup_subtitle), snackbar = snackbar) {
        SettingsGroup(stringResource(R.string.settings_group_export), footer = stringResource(R.string.settings_export_footer)) {
            link(tr(R.string.settings_export), summary = tr(R.string.settings_export_summary, target.path), icon = Icons.Rounded.Upload, tint = Tint.PRIMARY) {
                scope.launch {
                    val result = runCatching {
                        val text = SettingsBackup.attach(act.repo.exportBackup(), bookmarkRepo.bookmarks.first(), remoteRepo.connections.first(), FolderIconStore.exportEntries())
                        withContext(Dispatchers.IO) {
                            target.mkdirs()
                            val file = LocalFileAccess.file(File(target, SettingsBackup.fileName(System.currentTimeMillis())).path)
                            LocalFileAccess.openOutput(file).use { it.write(text.toByteArray()) }
                            file
                        }
                    }
                    refresh++
                    snackbar.showSnackbar(result.fold({ tr(R.string.settings_saved, it.name) }, { e -> tr(R.string.settings_save_failed, e.message ?: e.javaClass.simpleName) }))
                }
            }
        }
        SettingsGroup(stringResource(R.string.settings_group_import)) {
            backups.take(8).forEach { f ->
                link(f.name, summary = "${f.parent} · ${Formatters.date(f.lastModified())}", icon = Icons.Rounded.SettingsBackupRestore) {
                    importName = f.name
                    importing = { withContext(Dispatchers.IO) { LocalFileAccess.openInput(LocalFileAccess.file(f.path)).use { String(it.readBytes(), Charsets.UTF_8) } } }
                }
            }
            link(tr(R.string.settings_choose_file), summary = tr(R.string.settings_choose_file_summary), icon = Icons.Rounded.FolderOpen) {
                picker.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
            }
        }
        SettingsGroup(stringResource(R.string.settings_group_reset)) {
            link(tr(R.string.settings_reset_all), summary = tr(R.string.settings_reset_all_summary), icon = Icons.Rounded.RestartAlt, tint = Tint.ERROR) {
                resetting = true
            }
        }
    }
    importing?.let { read ->
        val name = importName
        AlertDialog(
            onDismissRequest = { importing = null },
            title = { Text(stringResource(R.string.settings_restore_title)) },
            text = { Text(if (name != null) stringResource(R.string.settings_restore_body, name) else stringResource(R.string.settings_restore_body_unnamed)) },
            confirmButton = { TextButton(onClick = { importing = null; importText(read) }) { Text(stringResource(R.string.settings_restore)) } },
            dismissButton = { TextButton(onClick = { importing = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    if (resetting) {
        AlertDialog(
            onDismissRequest = { resetting = false },
            title = { Text(stringResource(R.string.settings_reset_title)) },
            text = { Text(stringResource(R.string.settings_reset_body)) },
            confirmButton = {
                TextButton(onClick = {
                    resetting = false
                    scope.launch {
                        act.repo.resetToDefaults()
                        snackbar.showSnackbar(tr(R.string.settings_reset_done))
                    }
                }) { Text(stringResource(R.string.settings_reset), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { resetting = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    while (out.size() < limit) {
        val n = read(buf, 0, minOf(buf.size, limit - out.size()))
        if (n < 0) break
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}

private const val LICENSE_ID = "GNU AGPL-3.0-or-later"

private fun components(): List<Pair<String, String>> = listOf(
    tr(R.string.settings_credit_compose) to "Apache-2.0",
    tr(R.string.settings_credit_kotlin) to "Apache-2.0",
    "MaterialKolor" to "MIT",
    "Coil" to "Apache-2.0",
    "Telephoto" to "Apache-2.0",
    "Media3 (ExoPlayer)" to "Apache-2.0",
    tr(R.string.settings_credit_sora) to "LGPL-2.1",
    "Oniguruma" to "BSD-2-Clause",
    "jadx" to "Apache-2.0",
    tr(R.string.settings_credit_smali) to "BSD-3-Clause",
    "dex2jar" to "Apache-2.0",
    tr(R.string.settings_credit_d8) to tr(R.string.settings_license_d8),
    "apksig" to "Apache-2.0",
    "ARSCLib" to "Apache-2.0",
    "Bouncy Castle" to "MIT",
    tr(R.string.settings_credit_commons) to "Apache-2.0",
    "XZ for Java" to "0BSD",
    "zstd-jni" to "BSD-2-Clause",
    "zip4j" to "Apache-2.0",
    "UnRAR" to tr(R.string.settings_license_unrar),
    "sshj" to "Apache-2.0",
    "smbj" to "Apache-2.0",
    "smbj-rpc (dcerpc)" to "BSD-3-Clause",
    tr(R.string.settings_credit_okhttp) to "Apache-2.0",
    "dnsjava" to "BSD-3-Clause",
    tr(R.string.settings_credit_ftpserver) to "Apache-2.0",
    "Ktor" to "Apache-2.0",
    "libsu" to "Apache-2.0",
    "Shizuku API" to "MIT",
    "Multiplatform Markdown Renderer" to "Apache-2.0",
    tr(R.string.settings_credit_pdfium) to tr(R.string.settings_license_pdfium),
    tr(R.string.settings_credit_sounds) to tr(R.string.settings_credit_sounds_license),
)

@Composable
fun AboutPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val icon = remember { runCatching { context.packageManager.getApplicationIcon(context.packageName).toBitmap(192, 192).asImageBitmap() }.getOrNull() }
    SettingsPageScaffold(stringResource(R.string.settings_page_about), onBack) {
        Row(Modifier.fillMaxWidth().padding(top = 16.dp, start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(64.dp))
            Column(Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Strata", style = MaterialTheme.typography.headlineSmall)
                Text(
                    stringResource(R.string.settings_about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("about-version"),
                )
                Text(stringResource(R.string.settings_about_tagline), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        SettingsGroup(stringResource(R.string.settings_group_license)) {
            link(
                LICENSE_ID,
                summary = tr(R.string.settings_license_summary),
                icon = Icons.Rounded.Gavel,
                chevron = false,
            ) {}
        }
        SettingsGroup(stringResource(R.string.settings_group_device)) {
            link(tr(R.string.settings_device_android, Build.VERSION.RELEASE, Build.VERSION.SDK_INT), summary = "${Build.MANUFACTURER} ${Build.MODEL}", icon = Icons.Rounded.PhoneAndroid, chevron = false) {}
        }
        SettingsGroup(stringResource(R.string.settings_group_components), footer = stringResource(R.string.settings_components_footer)) {
            components().forEach { (name, license) -> link(name, summary = license, chevron = false) {} }
        }
    }
}

@Composable
fun TextSettingDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    hint: String? = null,
    extra: Pair<String, String>? = null,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    supportingText = hint?.let { { Text(it) } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (extra != null) TextButton(onClick = { text = extra.second }) { Text(extra.first) }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }) { Text(stringResource(R.string.common_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
