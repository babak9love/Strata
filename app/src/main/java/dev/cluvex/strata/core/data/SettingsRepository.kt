package dev.cluvex.strata.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.cluvex.strata.core.color.RecentColors
import dev.cluvex.strata.core.model.ActionLayout
import dev.cluvex.strata.core.model.AppearancePrefs
import dev.cluvex.strata.core.model.BehaviorPrefs
import dev.cluvex.strata.core.model.ConflictChoice
import dev.cluvex.strata.core.model.ContrastLevel
import dev.cluvex.strata.core.model.CornerStyle
import dev.cluvex.strata.core.model.DateStyle
import dev.cluvex.strata.core.model.FolderSorts
import dev.cluvex.strata.core.model.DEFAULT_ITEM_SCALE
import dev.cluvex.strata.core.model.IconShape
import dev.cluvex.strata.core.model.ItemScale
import dev.cluvex.strata.core.model.ListPrefs
import dev.cluvex.strata.core.model.LockDelay
import dev.cluvex.strata.core.model.MotionStyle
import dev.cluvex.strata.core.model.OperationPrefs
import dev.cluvex.strata.core.model.ReaderPrefs
import dev.cluvex.strata.core.model.ReaderTheme
import dev.cluvex.strata.core.model.SecurityPrefs
import dev.cluvex.strata.core.model.SizeUnits
import dev.cluvex.strata.core.model.SortBy
import dev.cluvex.strata.core.model.SortOrder
import dev.cluvex.strata.core.model.StartFolder
import dev.cluvex.strata.core.model.ThemeMode
import dev.cluvex.strata.core.model.ThemeSeeds
import dev.cluvex.strata.core.model.ThemeStyle
import dev.cluvex.strata.core.model.UiDensity
import dev.cluvex.strata.core.model.ViewMode
import dev.cluvex.strata.core.open.OpenDefaults
import dev.cluvex.strata.core.open.OpenTarget
import dev.cluvex.strata.core.pdf.view.ReaderMemory
import dev.cluvex.strata.core.pdf.view.ReadingSpot
import dev.cluvex.strata.core.plugin.PluginPins
import dev.cluvex.strata.core.rename.RenameRule
import dev.cluvex.strata.core.termux.RunCommands
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "strata_settings")

data class EditorPrefs(
    val theme: String = "auto",
    val wordWrap: Boolean = false,
    val lineNumbers: Boolean = true,
    val showWhitespace: Boolean = false,
    val symbolBar: Boolean = true,
    val textSize: Float = 14f,
    val tabWidth: Int = 4,
    val autoIndent: Boolean = true,
    val autoClosePairs: Boolean = true,
    val highlightLine: Boolean = true,
    val highlightBrackets: Boolean = true,
    val pinchZoom: Boolean = true,
    val stickyScroll: Boolean = false,
    val indentGuides: Boolean = true,
    val pinLineNumbers: Boolean = false,
    val cursorAnimation: Boolean = true,
    val keepScreenOn: Boolean = false,
) {
    companion object {
        val TAB_WIDTHS = listOf(2, 4, 8)
    }
}

data class StrataSettings(
    val sortOrder: SortOrder = SortOrder(),
    val folderSorts: Map<String, SortOrder> = emptyMap(),
    val showHidden: Boolean = false,
    val appearance: AppearancePrefs = AppearancePrefs(),
    val list: ListPrefs = ListPrefs(),
    val behavior: BehaviorPrefs = BehaviorPrefs(),
    val operations: OperationPrefs = OperationPrefs(),
    val security: SecurityPrefs = SecurityPrefs(),
    val dualPanePreferred: Boolean = true,
    val trashByDefault: Boolean = true,
    val trashAutoCleanDays: Int = 0,
    val rootAccess: Boolean = false,
    val shizukuAccess: Boolean = false,
    val uiDensity: UiDensity = UiDensity.DEFAULT,
    val openDefaults: Map<String, OpenTarget> = emptyMap(),
    val verifyBeforeInstall: Boolean = true,
    val installWithRoot: Boolean = true,
    val editor: EditorPrefs = EditorPrefs(),
    val apkExtractDir: String = "",
    val apkNamePattern: String = "{A}_{V}",
    val runCommands: Map<String, String> = emptyMap(),
    val reader: ReaderPrefs = ReaderPrefs(),
    val actions: ActionLayout = ActionLayout(),
)

fun StrataSettings.sortFor(path: String): SortOrder = FolderSorts.effective(path, sortOrder, folderSorts)

data class LastFolders(val left: String?, val right: String?)

class SettingsRepository(private val context: Context) {

    internal object Keys {
        val SORT_BY = stringPreferencesKey("sort_by")
        val SORT_DESC = booleanPreferencesKey("sort_desc")
        val DIRS_FIRST = booleanPreferencesKey("dirs_first")
        val FOLDER_SORTS = stringPreferencesKey("folder_sorts")
        val SHOW_HIDDEN = booleanPreferencesKey("show_hidden")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val DYNAMIC = booleanPreferencesKey("dynamic_color")
        val SEED = intPreferencesKey("seed_color")
        val THEME_STYLE = stringPreferencesKey("theme_style")
        val CONTRAST = stringPreferencesKey("contrast")
        val PURE_BLACK = booleanPreferencesKey("pure_black")
        val TEXT_SCALE = floatPreferencesKey("text_scale")
        val CORNERS = stringPreferencesKey("corner_style")
        val ICON_SHAPE = stringPreferencesKey("icon_shape")
        val STRATA_ICONS = booleanPreferencesKey("strata_icons")
        val COLORFUL_ICONS = booleanPreferencesKey("colorful_icons")
        val MOTION = stringPreferencesKey("motion")
        val HAPTICS = booleanPreferencesKey("haptics")
        val SOUNDS = booleanPreferencesKey("ui_sounds")
        val SOUNDS_IN_SILENT = booleanPreferencesKey("ui_sounds_in_silent")
        val VIEW_MODE = stringPreferencesKey("view_mode")
        val THUMBNAILS = booleanPreferencesKey("thumbnails")
        val APK_ICONS = booleanPreferencesKey("apk_icons")
        val SHOW_DATE = booleanPreferencesKey("show_date")
        val SHOW_SIZE = booleanPreferencesKey("show_size")
        val DATE_STYLE = stringPreferencesKey("date_style")
        val USE_24H = booleanPreferencesKey("use_24h")
        val SIZE_UNITS = stringPreferencesKey("size_units")
        val WRAP_NAMES = booleanPreferencesKey("wrap_names")
        val TILE_FRAME = booleanPreferencesKey("tile_frame")
        val GRID_COLUMNS = intPreferencesKey("grid_columns")
        val ITEM_SCALE = intPreferencesKey("item_scale")
        val NAME_SCALE = intPreferencesKey("name_scale")
        val META_SCALE = intPreferencesKey("meta_scale")
        val SHOW_PARENT_ENTRY = booleanPreferencesKey("show_parent_entry")
        val VIDEO_AUTO_HIDE = booleanPreferencesKey("video_auto_hide")
        val SHOW_EXTENSIONS = booleanPreferencesKey("show_extensions")
        val START_FOLDER = stringPreferencesKey("start_folder")
        val START_PATH = stringPreferencesKey("start_path")
        val DOUBLE_BACK = booleanPreferencesKey("double_back_exit")
        val ICON_SELECTS = booleanPreferencesKey("icon_tap_selects")
        val KEEP_SCREEN_TASKS = booleanPreferencesKey("keep_screen_on_tasks")
        val CONFLICT = stringPreferencesKey("conflict_choice")
        val KEEP_MTIME = booleanPreferencesKey("keep_modified_time")
        val SECURE_SCREEN = booleanPreferencesKey("secure_screen")
        val APP_LOCK = booleanPreferencesKey("app_lock")
        val LOCK_DELAY = stringPreferencesKey("lock_delay")
        val VAULT_LOCK_DELAY = stringPreferencesKey("vault_lock_delay")
        val PERSIAN_CALENDAR = booleanPreferencesKey("persian_calendar")
        val DUAL_PANE = booleanPreferencesKey("dual_pane")
        val TRASH_DEFAULT = booleanPreferencesKey("trash_by_default")
        val TRASH_CLEAN_DAYS = intPreferencesKey("trash_auto_clean_days")
        val ROOT_ACCESS = booleanPreferencesKey("root_access")
        val SHIZUKU_ACCESS = booleanPreferencesKey("shizuku_access")
        val UI_DENSITY = stringPreferencesKey("ui_density")
        val OPEN_DEFAULTS = stringPreferencesKey("open_defaults")
        val VERIFY_BEFORE_INSTALL = booleanPreferencesKey("verify_before_install")
        val INSTALL_WITH_ROOT = booleanPreferencesKey("install_with_root")
        val EDITOR_THEME = stringPreferencesKey("editor_theme")
        val EDITOR_WRAP = booleanPreferencesKey("editor_wrap")
        val EDITOR_LINE_NUMBERS = booleanPreferencesKey("editor_line_numbers")
        val EDITOR_WHITESPACE = booleanPreferencesKey("editor_whitespace")
        val EDITOR_SYMBOL_BAR = booleanPreferencesKey("editor_symbol_bar")
        val EDITOR_TEXT_SIZE = floatPreferencesKey("editor_text_size")
        val EDITOR_TAB_WIDTH = intPreferencesKey("editor_tab_width")
        val EDITOR_AUTO_INDENT = booleanPreferencesKey("editor_auto_indent")
        val EDITOR_AUTO_PAIRS = booleanPreferencesKey("editor_auto_pairs")
        val EDITOR_HIGHLIGHT_LINE = booleanPreferencesKey("editor_highlight_line")
        val EDITOR_HIGHLIGHT_BRACKETS = booleanPreferencesKey("editor_highlight_brackets")
        val EDITOR_PINCH_ZOOM = booleanPreferencesKey("editor_pinch_zoom")
        val EDITOR_STICKY_SCROLL = booleanPreferencesKey("editor_sticky_scroll")
        val EDITOR_INDENT_GUIDES = booleanPreferencesKey("editor_indent_guides")
        val EDITOR_PIN_LINE_NUMBERS = booleanPreferencesKey("editor_pin_line_numbers")
        val EDITOR_CURSOR_ANIMATION = booleanPreferencesKey("editor_cursor_animation")
        val EDITOR_KEEP_SCREEN_ON = booleanPreferencesKey("editor_keep_screen_on")
        val APK_EXTRACT_DIR = stringPreferencesKey("apk_extract_dir")
        val APK_NAME_PATTERN = stringPreferencesKey("apk_name_pattern")
        val RENAME_HISTORY = stringPreferencesKey("rename_history")
        val RECENT_COLORS = stringPreferencesKey("recent_colors")
        val PLUGIN_PINS = stringPreferencesKey("plugin_pins")
        val RUN_COMMANDS = stringPreferencesKey("run_commands")
        val LAST_LEFT = stringPreferencesKey("last_left")
        val LAST_RIGHT = stringPreferencesKey("last_right")
        val READER_THEME = stringPreferencesKey("reader_theme")
        val READER_KEEP_SCREEN = booleanPreferencesKey("reader_keep_screen_on")
        val READER_SPOTS = stringPreferencesKey("reader_spots")
        val READER_MARKS = stringPreferencesKey("reader_marks")
        val ACTION_LAYOUT = stringPreferencesKey("action_layout")

        val BOOLEANS = listOf(
            SORT_DESC, DIRS_FIRST, SHOW_HIDDEN, DYNAMIC, PURE_BLACK, STRATA_ICONS, COLORFUL_ICONS, HAPTICS, SOUNDS, SOUNDS_IN_SILENT, THUMBNAILS, APK_ICONS, SHOW_DATE,
            SHOW_SIZE, USE_24H, WRAP_NAMES, TILE_FRAME, SHOW_EXTENSIONS, DOUBLE_BACK, ICON_SELECTS, KEEP_SCREEN_TASKS, KEEP_MTIME, SECURE_SCREEN,
            DUAL_PANE, TRASH_DEFAULT, ROOT_ACCESS, SHIZUKU_ACCESS, VERIFY_BEFORE_INSTALL, INSTALL_WITH_ROOT, EDITOR_WRAP,
            EDITOR_LINE_NUMBERS, EDITOR_WHITESPACE, EDITOR_SYMBOL_BAR, EDITOR_AUTO_INDENT, EDITOR_AUTO_PAIRS, EDITOR_HIGHLIGHT_LINE,
            EDITOR_HIGHLIGHT_BRACKETS, EDITOR_PINCH_ZOOM, EDITOR_STICKY_SCROLL, EDITOR_INDENT_GUIDES, EDITOR_PIN_LINE_NUMBERS,
            EDITOR_CURSOR_ANIMATION, EDITOR_KEEP_SCREEN_ON, READER_KEEP_SCREEN, APP_LOCK, PERSIAN_CALENDAR,
            SHOW_PARENT_ENTRY, VIDEO_AUTO_HIDE,
        )
        val INTS = listOf(SEED, TRASH_CLEAN_DAYS, EDITOR_TAB_WIDTH, GRID_COLUMNS, ITEM_SCALE, NAME_SCALE, META_SCALE)
        val FLOATS = listOf(TEXT_SCALE, EDITOR_TEXT_SIZE)
        val STRINGS = listOf(
            SORT_BY, FOLDER_SORTS, THEME_MODE, THEME_STYLE, CONTRAST, CORNERS, ICON_SHAPE, MOTION, VIEW_MODE, DATE_STYLE, SIZE_UNITS,
            START_FOLDER, START_PATH, CONFLICT, UI_DENSITY, OPEN_DEFAULTS, EDITOR_THEME, APK_EXTRACT_DIR, APK_NAME_PATTERN,
            RENAME_HISTORY, RECENT_COLORS, PLUGIN_PINS, LAST_LEFT, LAST_RIGHT, RUN_COMMANDS, READER_THEME, READER_SPOTS, READER_MARKS,
            ACTION_LAYOUT, LOCK_DELAY, VAULT_LOCK_DELAY,
        )

        val PRIVATE: Set<String> = setOf(
            PLUGIN_PINS.name, LAST_LEFT.name, LAST_RIGHT.name, READER_SPOTS.name, APP_LOCK.name, LOCK_DELAY.name, VAULT_LOCK_DELAY.name,
        )
        val HISTORY: Set<String> = setOf(
            RENAME_HISTORY.name, RECENT_COLORS.name, PLUGIN_PINS.name, LAST_LEFT.name, LAST_RIGHT.name, READER_SPOTS.name, READER_MARKS.name,
            APP_LOCK.name, LOCK_DELAY.name, VAULT_LOCK_DELAY.name,
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    val settings: Flow<StrataSettings> = context.dataStore.data.map(::read)

    val lastFolders: Flow<LastFolders> = context.dataStore.data.map { p -> LastFolders(p[Keys.LAST_LEFT], p[Keys.LAST_RIGHT]) }

    suspend fun editAppearance(change: (AppearancePrefs) -> AppearancePrefs) = context.dataStore.edit { writeAppearance(it, change(readAppearance(it))) }
    suspend fun editList(change: (ListPrefs) -> ListPrefs) = context.dataStore.edit { writeList(it, change(readList(it))) }
    suspend fun editBehavior(change: (BehaviorPrefs) -> BehaviorPrefs) = context.dataStore.edit { writeBehavior(it, change(readBehavior(it))) }
    suspend fun editOperations(change: (OperationPrefs) -> OperationPrefs) = context.dataStore.edit { writeOperations(it, change(readOperations(it))) }
    suspend fun editSecurity(change: (SecurityPrefs) -> SecurityPrefs) = context.dataStore.edit { writeSecurity(it, change(readSecurity(it))) }
    suspend fun editEditor(change: (EditorPrefs) -> EditorPrefs) = context.dataStore.edit { writeEditor(it, change(readEditor(it))) }
    suspend fun editReader(change: (ReaderPrefs) -> ReaderPrefs) = context.dataStore.edit { writeReader(it, change(readReader(it))) }
    suspend fun editActions(change: (ActionLayout) -> ActionLayout) = context.dataStore.edit {
        it[Keys.ACTION_LAYOUT] = change(ActionLayout.decode(it[Keys.ACTION_LAYOUT])).encode()
    }

    val readerSpots: Flow<Map<String, ReadingSpot>> = context.dataStore.data.map { p -> ReaderMemory.decodeSpots(p[Keys.READER_SPOTS]) }

    val readerMarks: Flow<Map<String, List<Int>>> = context.dataStore.data.map { p -> ReaderMemory.decodeMarks(p[Keys.READER_MARKS]) }

    suspend fun saveReadingSpot(key: String, spot: ReadingSpot) = context.dataStore.edit { p ->
        val spots = ReaderMemory.decodeSpots(p[Keys.READER_SPOTS])
        if (spots[key] != spot) p[Keys.READER_SPOTS] = ReaderMemory.encodeSpots(ReaderMemory.remember(spots, key, spot, ReaderMemory.MAX_SPOTS))
    }

    suspend fun forgetReadingSpots() = context.dataStore.edit { p -> p.remove(Keys.READER_SPOTS) }

    suspend fun forgetReader(key: String) = context.dataStore.edit { p ->
        p[Keys.READER_SPOTS] = ReaderMemory.encodeSpots(ReaderMemory.decodeSpots(p[Keys.READER_SPOTS]) - key)
        p[Keys.READER_MARKS] = ReaderMemory.encodeMarks(ReaderMemory.decodeMarks(p[Keys.READER_MARKS]) - key)
    }

    suspend fun toggleReaderMark(key: String, page: Int) = context.dataStore.edit { p ->
        p[Keys.READER_MARKS] = ReaderMemory.encodeMarks(ReaderMemory.toggle(ReaderMemory.decodeMarks(p[Keys.READER_MARKS]), key, page))
    }

    suspend fun setFolderSort(path: String, order: SortOrder?) = context.dataStore.edit {
        it[Keys.FOLDER_SORTS] = FolderSorts.encode(FolderSorts.with(FolderSorts.decode(it[Keys.FOLDER_SORTS]), path, order))
    }

    suspend fun setSortOrder(order: SortOrder) = context.dataStore.edit {
        it[Keys.SORT_BY] = order.by.name
        it[Keys.SORT_DESC] = order.descending
        it[Keys.DIRS_FIRST] = order.dirsFirst
    }

    suspend fun setShowHidden(value: Boolean) = context.dataStore.edit { it[Keys.SHOW_HIDDEN] = value }
    suspend fun setDualPanePreferred(value: Boolean) = context.dataStore.edit { it[Keys.DUAL_PANE] = value }
    suspend fun setTrashByDefault(value: Boolean) = context.dataStore.edit { it[Keys.TRASH_DEFAULT] = value }
    suspend fun setTrashAutoCleanDays(days: Int) = context.dataStore.edit { it[Keys.TRASH_CLEAN_DAYS] = days }
    suspend fun setRootAccess(value: Boolean) = context.dataStore.edit { it[Keys.ROOT_ACCESS] = value }
    suspend fun setShizukuAccess(value: Boolean) = context.dataStore.edit { it[Keys.SHIZUKU_ACCESS] = value }
    suspend fun setUiDensity(value: UiDensity) = context.dataStore.edit { it[Keys.UI_DENSITY] = value.name }
    suspend fun setEditorPrefs(prefs: EditorPrefs) = context.dataStore.edit { writeEditor(it, prefs) }

    suspend fun setApkExtraction(dir: String, pattern: String) = context.dataStore.edit {
        it[Keys.APK_EXTRACT_DIR] = dir.trim()
        it[Keys.APK_NAME_PATTERN] = pattern.trim()
    }

    suspend fun setVerifyBeforeInstall(value: Boolean) = context.dataStore.edit { it[Keys.VERIFY_BEFORE_INSTALL] = value }
    suspend fun setInstallWithRoot(value: Boolean) = context.dataStore.edit { it[Keys.INSTALL_WITH_ROOT] = value }

    suspend fun setOpenDefault(extension: String, target: OpenTarget) = context.dataStore.edit { p ->
        p[Keys.OPEN_DEFAULTS] = OpenDefaults.encode(OpenDefaults.set(OpenDefaults.decode(p[Keys.OPEN_DEFAULTS]), extension, target))
    }

    suspend fun removeOpenDefault(extension: String) = context.dataStore.edit { p ->
        p[Keys.OPEN_DEFAULTS] = OpenDefaults.encode(OpenDefaults.remove(OpenDefaults.decode(p[Keys.OPEN_DEFAULTS]), extension))
    }

    suspend fun editRunCommands(transform: (Map<String, String>) -> Map<String, String>) = context.dataStore.edit { p ->
        p[Keys.RUN_COMMANDS] = RunCommands.encode(transform(RunCommands.decode(p[Keys.RUN_COMMANDS])))
    }

    suspend fun setRunCommand(key: String, command: String?) = editRunCommands { RunCommands.with(it, key, command) }

    suspend fun setLastFolders(left: String, right: String) = context.dataStore.edit {
        if (it[Keys.LAST_LEFT] != left) it[Keys.LAST_LEFT] = left
        if (it[Keys.LAST_RIGHT] != right) it[Keys.LAST_RIGHT] = right
    }

    val renameHistory: Flow<List<RenameRule>> = context.dataStore.data.map { p -> decodeHistory(p[Keys.RENAME_HISTORY]) }

    suspend fun pushRenameHistory(rule: RenameRule) = context.dataStore.edit { p ->
        p[Keys.RENAME_HISTORY] = json.encodeToString(RenameHistory.push(decodeHistory(p[Keys.RENAME_HISTORY]), rule))
    }

    val recentColors: Flow<List<Int>> = context.dataStore.data.map { p -> RecentColors.decode(p[Keys.RECENT_COLORS]) }

    suspend fun pushRecentColor(argb: Int) = context.dataStore.edit { p ->
        p[Keys.RECENT_COLORS] = RecentColors.encode(RecentColors.push(RecentColors.decode(p[Keys.RECENT_COLORS]), argb))
    }

    suspend fun setRecentColors(colors: List<Int>) = context.dataStore.edit { p -> p[Keys.RECENT_COLORS] = RecentColors.encode(colors) }

    val pluginPins: Flow<PluginPins> = context.dataStore.data.map { p -> PluginPins.decode(p[Keys.PLUGIN_PINS]) }

    suspend fun pinPlugin(packageName: String, signer: String) = context.dataStore.edit { p ->
        p[Keys.PLUGIN_PINS] = PluginPins.decode(p[Keys.PLUGIN_PINS]).with(packageName, signer).encode()
    }

    suspend fun forgetPlugin(packageName: String) = context.dataStore.edit { p ->
        p[Keys.PLUGIN_PINS] = PluginPins.decode(p[Keys.PLUGIN_PINS]).without(packageName).encode()
    }

    suspend fun setPluginPins(pins: PluginPins) = context.dataStore.edit { p -> p[Keys.PLUGIN_PINS] = pins.encode() }

    suspend fun exportBackup(): String {
        val values = context.dataStore.data.first().asMap()
            .filterKeys { it.name !in Keys.PRIVATE }
            .mapKeys { it.key.name }
        return SettingsBackup.encode(values)
    }

    suspend fun importBackup(text: String): Int {
        val values = SettingsBackup.decode(text).filterKeys { it !in Keys.PRIVATE }
        var applied = 0
        context.dataStore.edit { p ->
            for ((name, value) in values) {
                if (SettingsBackup.put(p, name, value)) applied++
            }
        }
        return applied
    }

    suspend fun resetToDefaults() = context.dataStore.edit { p ->
        p.asMap().keys.filter { it.name !in Keys.HISTORY }.forEach { p.remove(it) }
    }

    private fun decodeHistory(text: String?): List<RenameRule> =
        text?.let { runCatching { json.decodeFromString<List<RenameRule>>(it) }.getOrNull() } ?: emptyList()

    companion object {
        private inline fun <reified T : Enum<T>> enumOf(name: String?, default: T): T =
            name?.let { n -> enumValues<T>().firstOrNull { it.name == n } } ?: default

        internal fun read(p: Preferences) = StrataSettings(
            sortOrder = SortOrder(
                by = enumOf(p[Keys.SORT_BY], SortBy.NAME),
                descending = p[Keys.SORT_DESC] ?: false,
                dirsFirst = p[Keys.DIRS_FIRST] ?: true,
            ),
            folderSorts = FolderSorts.decode(p[Keys.FOLDER_SORTS]),
            showHidden = p[Keys.SHOW_HIDDEN] ?: false,
            appearance = readAppearance(p),
            list = readList(p),
            behavior = readBehavior(p),
            operations = readOperations(p),
            security = readSecurity(p),
            dualPanePreferred = p[Keys.DUAL_PANE] ?: true,
            trashByDefault = p[Keys.TRASH_DEFAULT] ?: true,
            trashAutoCleanDays = p[Keys.TRASH_CLEAN_DAYS] ?: 0,
            rootAccess = p[Keys.ROOT_ACCESS] ?: false,
            shizukuAccess = p[Keys.SHIZUKU_ACCESS] ?: false,
            uiDensity = UiDensity.fromName(p[Keys.UI_DENSITY]),
            openDefaults = OpenDefaults.decode(p[Keys.OPEN_DEFAULTS]),
            verifyBeforeInstall = p[Keys.VERIFY_BEFORE_INSTALL] ?: true,
            installWithRoot = p[Keys.INSTALL_WITH_ROOT] ?: true,
            editor = readEditor(p),
            apkExtractDir = p[Keys.APK_EXTRACT_DIR] ?: "",
            apkNamePattern = p[Keys.APK_NAME_PATTERN] ?: "{A}_{V}",
            runCommands = RunCommands.decode(p[Keys.RUN_COMMANDS]),
            reader = readReader(p),
            actions = ActionLayout.decode(p[Keys.ACTION_LAYOUT]),
        )

        internal fun readReader(p: Preferences) = ReaderPrefs(
            theme = enumOf(p[Keys.READER_THEME], ReaderTheme.PAPER),
            keepScreenOn = p[Keys.READER_KEEP_SCREEN] ?: true,
        )

        private fun writeReader(p: MutablePreferences, r: ReaderPrefs) {
            p[Keys.READER_THEME] = r.theme.name
            p[Keys.READER_KEEP_SCREEN] = r.keepScreenOn
        }

        internal fun readAppearance(p: Preferences) = AppearancePrefs(
            themeMode = enumOf(p[Keys.THEME_MODE], ThemeMode.SYSTEM),
            wallpaperColors = p[Keys.DYNAMIC] ?: true,
            seed = p[Keys.SEED] ?: ThemeSeeds.STRATA,
            style = enumOf(p[Keys.THEME_STYLE], ThemeStyle.TONAL_SPOT),
            contrast = enumOf(p[Keys.CONTRAST], ContrastLevel.STANDARD),
            pureBlack = p[Keys.PURE_BLACK] ?: false,
            textScale = AppearancePrefs.textScale(p[Keys.TEXT_SCALE] ?: 1f),
            corners = enumOf(p[Keys.CORNERS], CornerStyle.ROUND),
            iconShape = enumOf(p[Keys.ICON_SHAPE], IconShape.ROUNDED),
            strataIcons = p[Keys.STRATA_ICONS] ?: false,
            colorfulIcons = p[Keys.COLORFUL_ICONS] ?: true,
            motion = enumOf(p[Keys.MOTION], MotionStyle.EXPRESSIVE),
            haptics = p[Keys.HAPTICS] ?: true,
            sounds = p[Keys.SOUNDS] ?: false,
            soundsInSilent = p[Keys.SOUNDS_IN_SILENT] ?: true,
        )

        private fun writeAppearance(p: MutablePreferences, a: AppearancePrefs) {
            p[Keys.THEME_MODE] = a.themeMode.name
            p[Keys.DYNAMIC] = a.wallpaperColors
            p[Keys.SEED] = a.seed
            p[Keys.THEME_STYLE] = a.style.name
            p[Keys.CONTRAST] = a.contrast.name
            p[Keys.PURE_BLACK] = a.pureBlack
            p[Keys.TEXT_SCALE] = AppearancePrefs.textScale(a.textScale)
            p[Keys.CORNERS] = a.corners.name
            p[Keys.ICON_SHAPE] = a.iconShape.name
            p[Keys.STRATA_ICONS] = a.strataIcons
            p[Keys.COLORFUL_ICONS] = a.colorfulIcons
            p[Keys.MOTION] = a.motion.name
            p[Keys.HAPTICS] = a.haptics
            p[Keys.SOUNDS] = a.sounds
            p[Keys.SOUNDS_IN_SILENT] = a.soundsInSilent
        }

        internal fun readList(p: Preferences) = ListPrefs(
            viewMode = enumOf(p[Keys.VIEW_MODE], ViewMode.LIST),
            thumbnails = p[Keys.THUMBNAILS] ?: true,
            apkIcons = p[Keys.APK_ICONS] ?: true,
            showDate = p[Keys.SHOW_DATE] ?: true,
            showSize = p[Keys.SHOW_SIZE] ?: true,
            dateStyle = enumOf(p[Keys.DATE_STYLE], DateStyle.COMPACT),
            use24Hour = p[Keys.USE_24H] ?: true,
            sizeUnits = enumOf(p[Keys.SIZE_UNITS], SizeUnits.BINARY),
            wrapNames = p[Keys.WRAP_NAMES] ?: false,
            showExtensions = p[Keys.SHOW_EXTENSIONS] ?: true,
            persianCalendar = p[Keys.PERSIAN_CALENDAR] ?: true,
            tileFrame = p[Keys.TILE_FRAME] ?: true,
            gridColumns = (p[Keys.GRID_COLUMNS] ?: 0).let { if (it == 4) 4 else 0 },
            itemScale = ItemScale.clamp(p[Keys.ITEM_SCALE] ?: DEFAULT_ITEM_SCALE),
            nameScale = ItemScale.clamp(p[Keys.NAME_SCALE] ?: 100),
            metaScale = ItemScale.clamp(p[Keys.META_SCALE] ?: 100),
            showParentEntry = p[Keys.SHOW_PARENT_ENTRY] ?: true,
        )

        private fun writeList(p: MutablePreferences, l: ListPrefs) {
            p[Keys.VIEW_MODE] = l.viewMode.name
            p[Keys.THUMBNAILS] = l.thumbnails
            p[Keys.APK_ICONS] = l.apkIcons
            p[Keys.SHOW_DATE] = l.showDate
            p[Keys.SHOW_SIZE] = l.showSize
            p[Keys.DATE_STYLE] = l.dateStyle.name
            p[Keys.USE_24H] = l.use24Hour
            p[Keys.SIZE_UNITS] = l.sizeUnits.name
            p[Keys.PERSIAN_CALENDAR] = l.persianCalendar
            p[Keys.WRAP_NAMES] = l.wrapNames
            p[Keys.SHOW_EXTENSIONS] = l.showExtensions
            p[Keys.TILE_FRAME] = l.tileFrame
            p[Keys.GRID_COLUMNS] = l.gridColumns
            p[Keys.ITEM_SCALE] = ItemScale.clamp(l.itemScale)
            p[Keys.NAME_SCALE] = ItemScale.clamp(l.nameScale)
            p[Keys.META_SCALE] = ItemScale.clamp(l.metaScale)
            p[Keys.SHOW_PARENT_ENTRY] = l.showParentEntry
        }

        internal fun readBehavior(p: Preferences) = BehaviorPrefs(
            startFolder = enumOf(p[Keys.START_FOLDER], StartFolder.HOME),
            startPath = p[Keys.START_PATH] ?: "",
            doubleBackToExit = p[Keys.DOUBLE_BACK] ?: true,
            iconTapSelects = p[Keys.ICON_SELECTS] ?: true,
            keepScreenOnTasks = p[Keys.KEEP_SCREEN_TASKS] ?: false,
            videoAutoHide = p[Keys.VIDEO_AUTO_HIDE] ?: true,
        )

        private fun writeBehavior(p: MutablePreferences, b: BehaviorPrefs) {
            p[Keys.START_FOLDER] = b.startFolder.name
            p[Keys.START_PATH] = b.startPath.trim()
            p[Keys.DOUBLE_BACK] = b.doubleBackToExit
            p[Keys.ICON_SELECTS] = b.iconTapSelects
            p[Keys.KEEP_SCREEN_TASKS] = b.keepScreenOnTasks
            p[Keys.VIDEO_AUTO_HIDE] = b.videoAutoHide
        }

        internal fun readOperations(p: Preferences) = OperationPrefs(
            conflict = enumOf(p[Keys.CONFLICT], ConflictChoice.ASK),
            keepModifiedTime = p[Keys.KEEP_MTIME] ?: true,
        )

        private fun writeOperations(p: MutablePreferences, o: OperationPrefs) {
            p[Keys.CONFLICT] = o.conflict.name
            p[Keys.KEEP_MTIME] = o.keepModifiedTime
        }

        internal fun readSecurity(p: Preferences) = SecurityPrefs(
            secureScreen = p[Keys.SECURE_SCREEN] ?: false,
            appLock = p[Keys.APP_LOCK] ?: false,
            lockDelay = enumOf(p[Keys.LOCK_DELAY], LockDelay.IMMEDIATELY),
            vaultLockDelay = enumOf(p[Keys.VAULT_LOCK_DELAY], LockDelay.MINUTE_1),
        )

        private fun writeSecurity(p: MutablePreferences, s: SecurityPrefs) {
            p[Keys.SECURE_SCREEN] = s.secureScreen
            p[Keys.APP_LOCK] = s.appLock
            p[Keys.LOCK_DELAY] = s.lockDelay.name
            p[Keys.VAULT_LOCK_DELAY] = s.vaultLockDelay.name
        }

        internal fun readEditor(p: Preferences) = EditorPrefs(
            theme = p[Keys.EDITOR_THEME] ?: "auto",
            wordWrap = p[Keys.EDITOR_WRAP] ?: false,
            lineNumbers = p[Keys.EDITOR_LINE_NUMBERS] ?: true,
            showWhitespace = p[Keys.EDITOR_WHITESPACE] ?: false,
            symbolBar = p[Keys.EDITOR_SYMBOL_BAR] ?: true,
            textSize = p[Keys.EDITOR_TEXT_SIZE] ?: 14f,
            tabWidth = (p[Keys.EDITOR_TAB_WIDTH] ?: 4).takeIf { it in EditorPrefs.TAB_WIDTHS } ?: 4,
            autoIndent = p[Keys.EDITOR_AUTO_INDENT] ?: true,
            autoClosePairs = p[Keys.EDITOR_AUTO_PAIRS] ?: true,
            highlightLine = p[Keys.EDITOR_HIGHLIGHT_LINE] ?: true,
            highlightBrackets = p[Keys.EDITOR_HIGHLIGHT_BRACKETS] ?: true,
            pinchZoom = p[Keys.EDITOR_PINCH_ZOOM] ?: true,
            stickyScroll = p[Keys.EDITOR_STICKY_SCROLL] ?: false,
            indentGuides = p[Keys.EDITOR_INDENT_GUIDES] ?: true,
            pinLineNumbers = p[Keys.EDITOR_PIN_LINE_NUMBERS] ?: false,
            cursorAnimation = p[Keys.EDITOR_CURSOR_ANIMATION] ?: true,
            keepScreenOn = p[Keys.EDITOR_KEEP_SCREEN_ON] ?: false,
        )

        private fun writeEditor(p: MutablePreferences, e: EditorPrefs) {
            p[Keys.EDITOR_THEME] = e.theme
            p[Keys.EDITOR_WRAP] = e.wordWrap
            p[Keys.EDITOR_LINE_NUMBERS] = e.lineNumbers
            p[Keys.EDITOR_WHITESPACE] = e.showWhitespace
            p[Keys.EDITOR_SYMBOL_BAR] = e.symbolBar
            p[Keys.EDITOR_TEXT_SIZE] = e.textSize.coerceIn(8f, 32f)
            p[Keys.EDITOR_TAB_WIDTH] = e.tabWidth.takeIf { it in EditorPrefs.TAB_WIDTHS } ?: 4
            p[Keys.EDITOR_AUTO_INDENT] = e.autoIndent
            p[Keys.EDITOR_AUTO_PAIRS] = e.autoClosePairs
            p[Keys.EDITOR_HIGHLIGHT_LINE] = e.highlightLine
            p[Keys.EDITOR_HIGHLIGHT_BRACKETS] = e.highlightBrackets
            p[Keys.EDITOR_PINCH_ZOOM] = e.pinchZoom
            p[Keys.EDITOR_STICKY_SCROLL] = e.stickyScroll
            p[Keys.EDITOR_INDENT_GUIDES] = e.indentGuides
            p[Keys.EDITOR_PIN_LINE_NUMBERS] = e.pinLineNumbers
            p[Keys.EDITOR_CURSOR_ANIMATION] = e.cursorAnimation
            p[Keys.EDITOR_KEEP_SCREEN_ON] = e.keepScreenOn
        }
    }
}

object RenameHistory {
    const val MAX = 10

    fun push(history: List<RenameRule>, rule: RenameRule): List<RenameRule> =
        (listOf(rule) + history.filterNot { it == rule }).take(MAX)
}
