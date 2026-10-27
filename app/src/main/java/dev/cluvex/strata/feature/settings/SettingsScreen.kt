package dev.cluvex.strata.feature.settings

import dev.cluvex.strata.core.i18n.separator
import android.content.Context
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DashboardCustomize
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.VolunteerActivism
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.cluvex.strata.BuildConfig
import dev.cluvex.strata.R
import dev.cluvex.strata.core.data.EditorPrefs
import dev.cluvex.strata.core.data.SettingsRepository
import dev.cluvex.strata.core.data.StrataSettings
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.i18n.trPlural
import dev.cluvex.strata.core.model.ActionLayout
import dev.cluvex.strata.core.model.AppearancePrefs
import dev.cluvex.strata.core.model.BehaviorPrefs
import dev.cluvex.strata.core.model.ListPrefs
import dev.cluvex.strata.core.model.OperationPrefs
import dev.cluvex.strata.core.model.ReaderPrefs
import dev.cluvex.strata.core.model.SecurityPrefs
import dev.cluvex.strata.core.model.ThemeSeeds
import dev.cluvex.strata.core.root.RootState
import kotlin.math.roundToInt
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class SettingsActions(private val scope: CoroutineScope, val repo: SettingsRepository) {
    fun appearance(change: (AppearancePrefs) -> AppearancePrefs) { scope.launch { repo.editAppearance(change) } }
    fun list(change: (ListPrefs) -> ListPrefs) { scope.launch { repo.editList(change) } }
    fun behavior(change: (BehaviorPrefs) -> BehaviorPrefs) { scope.launch { repo.editBehavior(change) } }
    fun operations(change: (OperationPrefs) -> OperationPrefs) { scope.launch { repo.editOperations(change) } }
    fun security(change: (SecurityPrefs) -> SecurityPrefs) { scope.launch { repo.editSecurity(change) } }
    fun editor(change: (EditorPrefs) -> EditorPrefs) { scope.launch { repo.editEditor(change) } }
    fun reader(change: (ReaderPrefs) -> ReaderPrefs) { scope.launch { repo.editReader(change) } }
    fun actions(change: (ActionLayout) -> ActionLayout) { scope.launch { repo.editActions(change) } }
    fun run(block: suspend SettingsRepository.() -> Unit) { scope.launch { repo.block() } }
}

enum class SettingsPage(@StringRes val title: Int, val icon: ImageVector, val tint: Tint) {
    LANGUAGE(R.string.language_title, Icons.Rounded.Translate, Tint.SECONDARY),
    APPEARANCE(R.string.settings_page_appearance, Icons.Rounded.Palette, Tint.PRIMARY),
    FILE_LIST(R.string.settings_page_file_list, Icons.AutoMirrored.Rounded.ViewList, Tint.SECONDARY),
    TOOLBAR(R.string.settings_page_toolbar, Icons.Rounded.DashboardCustomize, Tint.TERTIARY),
    BEHAVIOR(R.string.settings_page_behavior, Icons.Rounded.TouchApp, Tint.TERTIARY),
    OPERATIONS(R.string.settings_page_operations, Icons.Rounded.ContentCopy, Tint.PRIMARY),
    EDITOR(R.string.settings_page_editor, Icons.Rounded.Code, Tint.SECONDARY),
    READER(R.string.settings_page_reader, Icons.Rounded.PictureAsPdf, Tint.TERTIARY),
    APPS(R.string.settings_page_apps, Icons.Rounded.Android, Tint.TERTIARY),
    ACCESS(R.string.settings_page_access, Icons.Rounded.Shield, Tint.PRIMARY),
    OPENING(R.string.settings_page_opening, Icons.AutoMirrored.Rounded.OpenInNew, Tint.SECONDARY),
    SECURITY(R.string.security_title, Icons.Rounded.Security, Tint.TERTIARY),
    BACKUP(R.string.settings_page_backup, Icons.Rounded.Restore, Tint.PRIMARY),
    ABOUT(R.string.settings_page_about, Icons.Rounded.Info, Tint.NEUTRAL),
    SUPPORT(R.string.settings_support_title, Icons.Rounded.VolunteerActivism, Tint.TERTIARY),
}

data class SettingEntry(@StringRes val title: Int, val page: SettingsPage, @StringRes val keywords: Int)

object SettingsIndex {
    val featured = SettingsPage.SUPPORT

    val home = listOf(
        R.string.settings_home_look to listOf(SettingsPage.LANGUAGE, SettingsPage.APPEARANCE, SettingsPage.FILE_LIST, SettingsPage.TOOLBAR),
        R.string.settings_home_files to listOf(SettingsPage.BEHAVIOR, SettingsPage.OPERATIONS, SettingsPage.OPENING, SettingsPage.EDITOR, SettingsPage.READER, SettingsPage.APPS),
        R.string.settings_home_system to listOf(SettingsPage.ACCESS, SettingsPage.SECURITY, SettingsPage.BACKUP, SettingsPage.ABOUT),
    )

    val entries = listOf(
        SettingEntry(R.string.settings_support_title, SettingsPage.SUPPORT, R.string.settings_kw_support),
        SettingEntry(R.string.language_title, SettingsPage.LANGUAGE, R.string.settings_kw_language),
        SettingEntry(R.string.settings_entry_solar_hijri, SettingsPage.LANGUAGE, R.string.settings_kw_solar_hijri),
        SettingEntry(R.string.settings_entry_theme_mode, SettingsPage.APPEARANCE, R.string.settings_kw_theme_mode),
        SettingEntry(R.string.settings_pure_black, SettingsPage.APPEARANCE, R.string.settings_kw_pure_black),
        SettingEntry(R.string.settings_wallpaper_colors, SettingsPage.APPEARANCE, R.string.settings_kw_wallpaper),
        SettingEntry(R.string.settings_theme_color, SettingsPage.APPEARANCE, R.string.settings_kw_theme_color),
        SettingEntry(R.string.settings_palette_style, SettingsPage.APPEARANCE, R.string.settings_kw_palette),
        SettingEntry(R.string.settings_contrast, SettingsPage.APPEARANCE, R.string.settings_kw_contrast),
        SettingEntry(R.string.settings_text_size, SettingsPage.APPEARANCE, R.string.settings_kw_text_size),
        SettingEntry(R.string.settings_corners, SettingsPage.APPEARANCE, R.string.settings_kw_corners),
        SettingEntry(R.string.settings_strata_file_icons, SettingsPage.APPEARANCE, R.string.settings_kw_strata_file_icons),
        SettingEntry(R.string.settings_folder_shaped, SettingsPage.APPEARANCE, R.string.settings_kw_folder_shaped),
        SettingEntry(R.string.settings_folder_fixed_color, SettingsPage.APPEARANCE, R.string.settings_kw_folder_fixed_color),
        SettingEntry(R.string.settings_icon_shape, SettingsPage.APPEARANCE, R.string.settings_kw_icon_shape),
        SettingEntry(R.string.settings_colorful_icons, SettingsPage.APPEARANCE, R.string.settings_kw_colorful_icons),
        SettingEntry(R.string.settings_animations, SettingsPage.APPEARANCE, R.string.settings_kw_animations),
        SettingEntry(R.string.settings_haptics, SettingsPage.APPEARANCE, R.string.settings_kw_haptics),
        SettingEntry(R.string.settings_sounds, SettingsPage.APPEARANCE, R.string.settings_kw_sounds),
        SettingEntry(R.string.settings_sounds_silent, SettingsPage.APPEARANCE, R.string.settings_kw_sounds_silent),
        SettingEntry(R.string.settings_entry_view_mode, SettingsPage.FILE_LIST, R.string.settings_kw_view_mode),
        SettingEntry(R.string.settings_entry_density, SettingsPage.FILE_LIST, R.string.settings_kw_density),
        SettingEntry(R.string.common_show_hidden, SettingsPage.FILE_LIST, R.string.settings_kw_hidden),
        SettingEntry(R.string.settings_folders_first, SettingsPage.FILE_LIST, R.string.settings_kw_folders_first),
        SettingEntry(R.string.settings_default_sort, SettingsPage.FILE_LIST, R.string.settings_kw_default_sort),
        SettingEntry(R.string.settings_thumbnails, SettingsPage.FILE_LIST, R.string.settings_kw_thumbnails),
        SettingEntry(R.string.settings_folder_badges, SettingsPage.FILE_LIST, R.string.settings_kw_folder_badges),
        SettingEntry(R.string.settings_show_extensions, SettingsPage.FILE_LIST, R.string.settings_kw_extensions),
        SettingEntry(R.string.settings_wrap_names, SettingsPage.FILE_LIST, R.string.settings_kw_wrap_names),
        SettingEntry(R.string.settings_date_format, SettingsPage.FILE_LIST, R.string.settings_kw_date_format),
        SettingEntry(R.string.settings_size_units, SettingsPage.FILE_LIST, R.string.settings_kw_size_units),
        SettingEntry(R.string.settings_entry_bottom_bar, SettingsPage.TOOLBAR, R.string.settings_kw_bottom_bar),
        SettingEntry(R.string.settings_entry_selection_bar, SettingsPage.TOOLBAR, R.string.settings_kw_selection_bar),
        SettingEntry(R.string.settings_entry_long_press, SettingsPage.TOOLBAR, R.string.settings_kw_long_press),
        SettingEntry(R.string.settings_start_in, SettingsPage.BEHAVIOR, R.string.settings_kw_start_in),
        SettingEntry(R.string.settings_two_panes, SettingsPage.BEHAVIOR, R.string.settings_kw_two_panes),
        SettingEntry(R.string.settings_double_back, SettingsPage.BEHAVIOR, R.string.settings_kw_double_back),
        SettingEntry(R.string.settings_icon_tap, SettingsPage.BEHAVIOR, R.string.settings_kw_icon_tap),
        SettingEntry(R.string.settings_bookmark_bar, SettingsPage.BEHAVIOR, R.string.settings_kw_bookmark_bar),
        SettingEntry(R.string.settings_bookmark_bar_bottom, SettingsPage.BEHAVIOR, R.string.settings_kw_bookmark_bar_bottom),
        SettingEntry(R.string.settings_bookmark_style, SettingsPage.BEHAVIOR, R.string.settings_kw_bookmark_style),
        SettingEntry(R.string.settings_bookmark_swipe, SettingsPage.BEHAVIOR, R.string.settings_kw_bookmark_swipe),
        SettingEntry(R.string.settings_bookmark_wrap, SettingsPage.BEHAVIOR, R.string.settings_kw_bookmark_wrap),
        SettingEntry(R.string.settings_swipe_drawer, SettingsPage.BEHAVIOR, R.string.settings_kw_swipe_drawer),
        SettingEntry(R.string.settings_screen_on_tasks, SettingsPage.BEHAVIOR, R.string.settings_kw_screen_on_tasks),
        SettingEntry(R.string.settings_conflict, SettingsPage.OPERATIONS, R.string.settings_kw_conflict),
        SettingEntry(R.string.settings_keep_mtime, SettingsPage.OPERATIONS, R.string.settings_kw_keep_mtime),
        SettingEntry(R.string.common_recycle_bin, SettingsPage.OPERATIONS, R.string.settings_kw_recycle_bin),
        SettingEntry(R.string.settings_entry_editor_theme, SettingsPage.EDITOR, R.string.settings_kw_editor_theme),
        SettingEntry(R.string.settings_entry_editor_font, SettingsPage.EDITOR, R.string.settings_kw_editor_font),
        SettingEntry(R.string.settings_tab_width, SettingsPage.EDITOR, R.string.settings_kw_tab_width),
        SettingEntry(R.string.settings_word_wrap, SettingsPage.EDITOR, R.string.settings_kw_word_wrap),
        SettingEntry(R.string.settings_line_numbers, SettingsPage.EDITOR, R.string.settings_kw_line_numbers),
        SettingEntry(R.string.settings_entry_auto_close, SettingsPage.EDITOR, R.string.settings_kw_auto_close),
        SettingEntry(R.string.settings_sticky_scroll, SettingsPage.EDITOR, R.string.settings_kw_sticky_scroll),
        SettingEntry(R.string.settings_pinch_zoom, SettingsPage.EDITOR, R.string.settings_kw_pinch_zoom),
        SettingEntry(R.string.settings_page_color, SettingsPage.READER, R.string.settings_kw_page_color),
        SettingEntry(R.string.settings_reader_screen_on, SettingsPage.READER, R.string.settings_kw_reader_screen_on),
        SettingEntry(R.string.settings_forget_spots, SettingsPage.READER, R.string.settings_kw_forget_spots),
        SettingEntry(R.string.settings_verify, SettingsPage.APPS, R.string.settings_kw_verify),
        SettingEntry(R.string.settings_install_root, SettingsPage.APPS, R.string.settings_kw_install_root),
        SettingEntry(R.string.settings_entry_apk_folder, SettingsPage.APPS, R.string.settings_kw_apk_folder),
        SettingEntry(R.string.settings_root_access, SettingsPage.ACCESS, R.string.settings_kw_root_access),
        SettingEntry(R.string.settings_shizuku, SettingsPage.ACCESS, R.string.settings_kw_shizuku),
        SettingEntry(R.string.settings_opening_subtitle, SettingsPage.OPENING, R.string.settings_kw_opening),
        SettingEntry(R.string.security_secure_screen, SettingsPage.SECURITY, R.string.settings_kw_secure_screen),
        SettingEntry(R.string.lock_group, SettingsPage.SECURITY, R.string.settings_kw_app_lock),
        SettingEntry(R.string.vault_group, SettingsPage.SECURITY, R.string.settings_kw_vault),
        SettingEntry(R.string.vault_change_password, SettingsPage.SECURITY, R.string.settings_kw_vault_password),
        SettingEntry(R.string.settings_export, SettingsPage.BACKUP, R.string.settings_kw_export),
        SettingEntry(R.string.settings_entry_import, SettingsPage.BACKUP, R.string.settings_kw_import),
        SettingEntry(R.string.settings_reset_all, SettingsPage.BACKUP, R.string.settings_kw_reset),
        SettingEntry(R.string.settings_entry_about, SettingsPage.ABOUT, R.string.settings_kw_about),
    )

    fun search(query: String, english: ((Int) -> String)? = null): List<SettingEntry> {
        val words = fold(query.trim()).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return entries.filter { e ->
            val ids = listOf(e.title, e.page.title, e.keywords)
            val local = ids.joinToString(" ") { tr(it) }
            val hay = fold(if (english == null) local else local + " " + ids.joinToString(" ") { english(it) })
            words.all { it in hay }
        }
    }

    private fun fold(text: String): String =
        text.lowercase().replace('\u064A', '\u06CC').replace('\u0643', '\u06A9').replace("\u200C", "")
}

@Composable
fun SettingsScreen(
    settings: StrataSettings,
    repo: SettingsRepository,
    rootState: RootState,
    onRootAccess: (Boolean) -> Unit,
    onOpenRecycleBin: () -> Unit,
    activeFolder: String,
    onBack: () -> Unit,
    startPage: SettingsPage? = null,
    startTab: ToolbarTab = ToolbarTab.BROWSE,
    onOpenVault: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val act = remember(repo) { SettingsActions(scope, repo) }
    var page by rememberSaveable { mutableStateOf(startPage) }
    val direct = startPage != null
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    BackHandler(enabled = page != null && !direct) { page = null }
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            val forward = targetState != null
            val sign = if (forward != rtl) 1 else -1
            (slideInHorizontally { w -> sign * w / 4 } + fadeIn())
                .togetherWith(slideOutHorizontally { w -> -sign * w / 4 } + fadeOut())
        },
    ) { current ->
        val back = if (direct) onBack else { { page = null } }
        when (current) {
            null -> SettingsHome(settings, rootState, onOpen = { page = it }, onBack = onBack)
            SettingsPage.LANGUAGE -> LanguagePage(settings, act, back)
            SettingsPage.APPEARANCE -> AppearancePage(settings, act, back)
            SettingsPage.FILE_LIST -> FileListPage(settings, act, back)
            SettingsPage.TOOLBAR -> ToolbarPage(settings, act, back, startTab)
            SettingsPage.BEHAVIOR -> BehaviorPage(settings, act, activeFolder, back)
            SettingsPage.OPERATIONS -> OperationsPage(settings, act, onOpenRecycleBin, back)
            SettingsPage.EDITOR -> EditorSettingsPage(settings, act, back)
            SettingsPage.READER -> ReaderSettingsPage(settings, act, back)
            SettingsPage.APPS -> AppsPage(settings, act, back)
            SettingsPage.ACCESS -> AccessPage(settings, act, rootState, onRootAccess, back)
            SettingsPage.OPENING -> OpeningPage(settings, act, back)
            SettingsPage.SECURITY -> SecurityPage(settings, act, onOpenVault, back)
            SettingsPage.BACKUP -> BackupPage(act, activeFolder, back)
            SettingsPage.ABOUT -> AboutPage(back)
            SettingsPage.SUPPORT -> SupportPage(back)
        }
    }
}

private fun onOff(on: Boolean, @StringRes yes: Int, @StringRes no: Int): String = tr(if (on) yes else no)

private fun summary(page: SettingsPage, s: StrataSettings, rootState: RootState): String = when (page) {
    SettingsPage.LANGUAGE -> Locale.getDefault().let { it.getDisplayName(it).replaceFirstChar { c -> c.titlecase(it) } }
    SettingsPage.APPEARANCE -> listOf(
        tr(s.appearance.themeMode.label),
        if (s.appearance.wallpaperColors) tr(R.string.settings_wallpaper_colors) else ThemeSeeds.named(s.appearance.seed)?.let { tr(it) } ?: tr(R.string.settings_custom_color),
        tr(R.string.settings_summary_text_scale, (s.appearance.textScale * 100).roundToInt()),
    ).joinToString(separator())
    SettingsPage.FILE_LIST -> listOf(
        tr(s.list.viewMode.label),
        tr(s.uiDensity.label),
        onOff(s.list.thumbnails, R.string.settings_thumbnails, R.string.settings_summary_no_thumbnails),
    ).joinToString(separator())
    SettingsPage.TOOLBAR -> {
        val bar = s.actions.browse.zones[0].size
        val quick = s.actions.sheet.zones[0].size
        listOf(
            trPlural(R.plurals.settings_summary_bar_buttons, bar, bar),
            trPlural(R.plurals.settings_summary_quick_buttons, quick, quick),
        ).joinToString(separator())
    }
    SettingsPage.BEHAVIOR -> tr(R.string.settings_summary_start_in, tr(s.behavior.startFolder.label))
    SettingsPage.OPERATIONS -> listOf(
        tr(R.string.settings_summary_conflicts, tr(s.operations.conflict.label)),
        onOff(s.trashByDefault, R.string.settings_summary_trash_on, R.string.settings_summary_trash_off),
    ).joinToString(separator())
    SettingsPage.EDITOR -> listOf(
        tr(R.string.settings_summary_editor_text, s.editor.textSize.roundToInt()),
        tr(R.string.settings_summary_editor_tab, s.editor.tabWidth),
        onOff(s.editor.wordWrap, R.string.settings_summary_wrap_on, R.string.settings_summary_wrap_off),
    ).joinToString(separator())
    SettingsPage.READER -> listOf(
        tr(R.string.settings_summary_pages, tr(s.reader.theme.label)),
        onOff(s.reader.keepScreenOn, R.string.settings_summary_screen_reading, R.string.settings_summary_screen_off),
    ).joinToString(separator())
    SettingsPage.APPS -> onOff(s.verifyBeforeInstall, R.string.settings_summary_signature_on, R.string.settings_summary_signature_off)
    SettingsPage.ACCESS -> listOf(
        onOff(s.rootAccess && rootState != RootState.DENIED, R.string.settings_summary_root_on, R.string.settings_summary_root_off),
        onOff(s.shizukuAccess, R.string.settings_summary_shizuku_on, R.string.settings_summary_shizuku_off),
    ).joinToString(separator())
    SettingsPage.OPENING -> s.openDefaults.size.let { n ->
        if (n == 0) tr(R.string.settings_summary_no_defaults) else trPlural(R.plurals.settings_summary_defaults, n, n)
    }
    SettingsPage.SECURITY -> listOf(
        onOff(s.security.appLock, R.string.settings_summary_app_lock_on, R.string.settings_summary_app_lock_off),
        onOff(s.security.secureScreen, R.string.settings_summary_secure_on, R.string.settings_summary_secure_off),
    ).joinToString(separator())
    SettingsPage.BACKUP -> tr(R.string.settings_summary_backup)
    SettingsPage.ABOUT -> tr(R.string.settings_summary_version, BuildConfig.VERSION_NAME)
    SettingsPage.SUPPORT -> tr(R.string.settings_support_summary)
}

private fun englishStrings(context: Context): (Int) -> String {
    val config = Configuration(context.resources.configuration)
    config.setLocale(Locale.ENGLISH)
    val resources = context.createConfigurationContext(config).resources
    return { id -> resources.getString(id) }
}

@Composable
private fun SettingsHome(settings: StrataSettings, rootState: RootState, onOpen: (SettingsPage) -> Unit, onBack: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current
    val english = remember(context) { englishStrings(context) }
    SettingsPageScaffold(stringResource(R.string.common_settings), onBack) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(stringResource(R.string.settings_search_hint)) },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = if (query.isNotEmpty()) {
                { IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.settings_search_clear)) } }
            } else {
                null
            },
            singleLine = true,
            shape = CircleShape,
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceBright,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceBright,
                unfocusedBorderColor = MaterialTheme.colorScheme.surfaceBright,
            ),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("settings-search"),
        )
        if (query.isNotBlank()) {
            val dualOn = dev.cluvex.strata.ui.LocalSettings.current.dualPanePreferred
            val results = SettingsIndex.search(query, english).filter { !(dualOn && it.title == R.string.settings_entry_view_mode) }
            if (results.isEmpty()) {
                SettingNote(stringResource(R.string.settings_search_none, query))
            } else {
                SettingsGroup(stringResource(R.string.settings_search_results)) {
                    results.forEach { e ->
                        link(tr(e.title), summary = tr(e.page.title), icon = e.page.icon, tint = e.page.tint) { onOpen(e.page) }
                    }
                }
            }
            return@SettingsPageScaffold
        }
        SupportCard { onOpen(SettingsIndex.featured) }
        SettingsIndex.home.forEach { (group, pages) ->
            SettingsGroup(stringResource(group)) {
                pages.forEach { p ->
                    link(tr(p.title), summary(p, settings, rootState), p.icon, p.tint) { onOpen(p) }
                }
            }
        }
    }
}
