package dev.cluvex.strata.core.model

import androidx.annotation.StringRes
import dev.cluvex.strata.R

enum class BrowseAction(@StringRes val label: Int) {
    BACK(R.string.model_browse_back),
    FORWARD(R.string.model_browse_forward),
    NEW(R.string.model_browse_new),
    PASTE(R.string.model_browse_paste),
    SWAP(R.string.model_browse_swap),
    UP(R.string.model_browse_up),
    UNDO(R.string.model_browse_undo),
    HOME(R.string.model_browse_home),
    REFRESH(R.string.model_browse_refresh),
    BOOKMARKS(R.string.model_browse_bookmarks),
    BOOKMARK_FOLDER(R.string.model_browse_bookmark_folder),
    PROTECTED_FOLDERS(R.string.settings_group_protected),
    SELECT_ALL(R.string.model_browse_select_all),
    RUN_PROJECT(R.string.model_browse_run_project),
    TERMINAL(R.string.model_browse_terminal),
    FILTER(R.string.model_browse_filter),
    SEARCH(R.string.model_browse_search),
    SORT(R.string.model_browse_sort),
    JUMP(R.string.model_browse_jump),
    HIDDEN_FILES(R.string.model_browse_hidden_files),
}

enum class SelectionAction(@StringRes val label: Int, val asks: Boolean = false) {
    OPEN_WITH(R.string.model_sheet_open_with),
    EXTRACT(R.string.model_sheet_extract),
    COPY(R.string.model_selection_copy),
    MOVE(R.string.model_selection_move),
    DELETE(R.string.model_selection_delete),
    RENAME(R.string.model_selection_rename),
    BATCH_RENAME(R.string.model_selection_batch_rename, asks = true),
    COPY_CLIPBOARD(R.string.model_selection_copy_clipboard),
    CUT_CLIPBOARD(R.string.model_selection_cut_clipboard),
    COMPRESS(R.string.model_selection_compress, asks = true),
    MERGE(R.string.model_selection_merge, asks = true),
    COMPARE(R.string.model_selection_compare, asks = true),
    SMALI_TO_DEX(R.string.model_smali_to_dex),
    JAR_TO_DEX(R.string.model_jar_to_dex),
    BOOKMARKS(R.string.model_browse_bookmarks),
    BOOKMARK(R.string.model_add_bookmark),
    FOLDER_BADGE(R.string.model_folder_badge),
    PROTECT(R.string.model_protect_folder),
    PROTECTED_FOLDERS(R.string.settings_group_protected),
    LINKS(R.string.model_selection_links, asks = true),
    VAULT(R.string.model_move_to_vault),
    RESTORE(R.string.model_restore),
    SHARE(R.string.model_selection_share),
    COPY_PATH(R.string.model_selection_copy_path),
    PROPERTIES(R.string.model_selection_properties),
    SELECT_ALL(R.string.model_browse_select_all),
    INVERT(R.string.model_selection_invert),
}

enum class SheetGroup(@StringRes val title: Int) {
    OPEN(R.string.model_group_open),
    TOOLS(R.string.model_group_tools),
    ORGANIZE(R.string.model_group_organize),
}

enum class SheetAction(@StringRes val label: Int, val group: SheetGroup) {
    OPEN(R.string.model_sheet_open, SheetGroup.OPEN),
    OPEN_WITH(R.string.model_sheet_open_with, SheetGroup.OPEN),
    SHARE(R.string.model_selection_share, SheetGroup.ORGANIZE),
    RENAME(R.string.model_selection_rename, SheetGroup.ORGANIZE),
    OPEN_AS_TEXT(R.string.model_sheet_open_as_text, SheetGroup.OPEN),
    HEX(R.string.model_sheet_hex, SheetGroup.OPEN),
    VIEW(R.string.model_sheet_view, SheetGroup.OPEN),
    APK_INFO(R.string.model_sheet_apk_info, SheetGroup.OPEN),
    EXTRACT(R.string.model_sheet_extract, SheetGroup.TOOLS),
    COMPRESS(R.string.model_sheet_compress, SheetGroup.TOOLS),
    SIGN_APK(R.string.model_sheet_sign_apk, SheetGroup.TOOLS),
    SMALI_TO_DEX(R.string.model_smali_to_dex, SheetGroup.TOOLS),
    JAR_TO_DEX(R.string.model_jar_to_dex, SheetGroup.TOOLS),
    CHECKSUM(R.string.model_sheet_checksum, SheetGroup.TOOLS),
    SPLIT(R.string.model_sheet_split, SheetGroup.TOOLS),
    CIPHER(R.string.model_sheet_cipher, SheetGroup.TOOLS),
    SELECT(R.string.model_sheet_select, SheetGroup.ORGANIZE),
    BOOKMARK(R.string.model_add_bookmark, SheetGroup.ORGANIZE),
    PROTECT(R.string.model_protect_folder, SheetGroup.ORGANIZE),
    LINK(R.string.model_sheet_link, SheetGroup.ORGANIZE),
    VAULT(R.string.model_move_to_vault, SheetGroup.ORGANIZE),
    RESTORE(R.string.model_restore, SheetGroup.ORGANIZE),
    PROPERTIES(R.string.model_selection_properties, SheetGroup.ORGANIZE),
    AUDIO_INFO(R.string.model_sheet_audio_info, SheetGroup.OPEN),
    VIDEO_INFO(R.string.model_sheet_video_info, SheetGroup.OPEN),
}

data class BrowseContext(val clipboard: Boolean, val termux: Boolean, val project: Boolean, val undo: Boolean = true, val dual: Boolean = true)

data class SelectionContext(
    val count: Int,
    val files: Int,
    val dual: Boolean,
    val remote: Boolean,
    val smaliFolder: Boolean,
    val jarFile: Boolean,
    val vault: Boolean = false,
    val archive: Boolean = false,
    val protectedFolder: Boolean = false,
)

data class SheetContext(
    val file: Boolean,
    val local: Boolean,
    val textEditable: Boolean,
    val archiveViewable: Boolean,
    val extractable: Boolean,
    val apk: Boolean,
    val jar: Boolean,
    val smaliFolder: Boolean,
    val symlink: Boolean,
    val vault: Boolean = false,
    val audio: Boolean = false,
    val video: Boolean = false,
)

@get:StringRes
val BrowseAction.hint: Int?
    get() = when (this) {
        BrowseAction.PASTE -> R.string.model_hint_after_copy
        BrowseAction.RUN_PROJECT -> R.string.model_hint_project
        BrowseAction.TERMINAL -> R.string.model_hint_terminal
        else -> null
    }

@get:StringRes
val SelectionAction.hint: Int?
    get() = when (this) {
        SelectionAction.RENAME -> R.string.model_hint_one_item
        SelectionAction.OPEN_WITH -> R.string.model_hint_files
        SelectionAction.EXTRACT -> R.string.model_hint_local_archives
        SelectionAction.COPY_CLIPBOARD, SelectionAction.CUT_CLIPBOARD -> R.string.model_hint_two_panes
        SelectionAction.MERGE -> R.string.model_hint_two_files
        SelectionAction.COMPARE -> R.string.model_hint_exactly_two
        SelectionAction.SMALI_TO_DEX -> R.string.model_hint_smali_folder
        SelectionAction.JAR_TO_DEX -> R.string.model_hint_jar_file
        SelectionAction.BATCH_RENAME, SelectionAction.COMPRESS, SelectionAction.LINKS, SelectionAction.SHARE,
        SelectionAction.PROPERTIES, SelectionAction.COPY_PATH, SelectionAction.VAULT -> R.string.model_hint_local_folders
        SelectionAction.RESTORE -> R.string.model_hint_in_vault
        else -> null
    }

@get:StringRes
val SheetAction.hint: Int?
    get() = when (this) {
        SheetAction.OPEN_WITH, SheetAction.SHARE, SheetAction.HEX, SheetAction.CHECKSUM -> R.string.model_hint_files
        SheetAction.OPEN_AS_TEXT -> R.string.model_hint_not_text
        SheetAction.VIEW -> R.string.model_hint_archives
        SheetAction.APK_INFO -> R.string.model_hint_apks
        SheetAction.EXTRACT -> R.string.model_hint_local_archives
        SheetAction.COMPRESS, SheetAction.BOOKMARK, SheetAction.LINK, SheetAction.PROPERTIES -> R.string.model_hint_local_items
        SheetAction.SIGN_APK -> R.string.model_hint_local_apks
        SheetAction.SMALI_TO_DEX -> R.string.model_hint_local_smali
        SheetAction.JAR_TO_DEX -> R.string.model_hint_local_jars
        SheetAction.SPLIT, SheetAction.CIPHER -> R.string.model_hint_local_files
        SheetAction.VAULT -> R.string.model_hint_local_not_links
        SheetAction.PROTECT -> R.string.model_hint_local_folders
        SheetAction.RESTORE -> R.string.model_hint_in_vault
        else -> null
    }

fun <T : Enum<T>> Zoned<T>.spilledBy(item: T, after: Zoned<T>): List<T> =
    zones.firstOrNull().orEmpty().filter { it != item && after.zoneOf(it) > 0 }

fun BrowseAction.available(c: BrowseContext): Boolean = when (this) {
    BrowseAction.PASTE -> c.clipboard
    BrowseAction.TERMINAL -> c.termux
    BrowseAction.RUN_PROJECT -> c.project
    BrowseAction.UNDO -> c.undo
    BrowseAction.SWAP -> c.dual
    else -> true
}

fun SelectionAction.available(c: SelectionContext): Boolean = when (this) {
    SelectionAction.COPY, SelectionAction.MOVE, SelectionAction.DELETE,
    SelectionAction.SELECT_ALL, SelectionAction.INVERT, SelectionAction.PROTECTED_FOLDERS, SelectionAction.BOOKMARKS -> true
    SelectionAction.RENAME -> c.count == 1
    SelectionAction.BOOKMARK -> c.files == 0
    SelectionAction.FOLDER_BADGE, SelectionAction.PROTECT -> !c.remote && c.count == 1 && c.files == 0
    SelectionAction.OPEN_WITH -> c.count == 1 && c.files == 1
    SelectionAction.EXTRACT -> !c.remote && c.count == 1 && c.archive
    SelectionAction.BATCH_RENAME, SelectionAction.COMPRESS, SelectionAction.LINKS, SelectionAction.SHARE,
    SelectionAction.PROPERTIES, SelectionAction.COPY_PATH -> !c.remote
    SelectionAction.COPY_CLIPBOARD, SelectionAction.CUT_CLIPBOARD -> c.dual
    SelectionAction.MERGE -> !c.remote && c.files >= 2
    SelectionAction.COMPARE -> !c.remote && c.count == 2 && c.files == 2
    SelectionAction.SMALI_TO_DEX -> !c.remote && c.count == 1 && c.smaliFolder
    SelectionAction.JAR_TO_DEX -> !c.remote && c.count == 1 && c.jarFile
    SelectionAction.VAULT -> !c.remote
    SelectionAction.RESTORE -> c.vault
}

fun SheetAction.available(c: SheetContext): Boolean = when (this) {
    SheetAction.OPEN, SheetAction.RENAME, SheetAction.SELECT -> true
    SheetAction.OPEN_WITH, SheetAction.SHARE, SheetAction.HEX, SheetAction.CHECKSUM -> c.file
    SheetAction.OPEN_AS_TEXT -> c.file && !c.textEditable
    SheetAction.VIEW -> c.file && c.archiveViewable
    SheetAction.APK_INFO -> c.apk
    SheetAction.EXTRACT -> c.local && c.file && c.extractable
    SheetAction.COMPRESS, SheetAction.LINK, SheetAction.PROPERTIES -> c.local
    SheetAction.BOOKMARK -> c.local && !c.file
    SheetAction.SIGN_APK -> c.local && c.apk
    SheetAction.SMALI_TO_DEX -> c.local && !c.file && c.smaliFolder
    SheetAction.JAR_TO_DEX -> c.local && c.file && c.jar
    SheetAction.SPLIT, SheetAction.CIPHER -> c.local && c.file && !c.symlink
    SheetAction.VAULT -> c.local && !c.symlink
    SheetAction.PROTECT -> c.local && !c.file
    SheetAction.RESTORE -> c.vault
    SheetAction.AUDIO_INFO -> c.local && c.file && c.audio
    SheetAction.VIDEO_INFO -> c.local && c.file && c.video
}

sealed interface ZoneSlot<out T> {
    data class Header(val zone: Int) : ZoneSlot<Nothing>
    data class Item<T>(val value: T, val zone: Int) : ZoneSlot<T>
}

data class Zoned<T : Enum<T>>(val zones: List<List<T>>) {

    fun zoneOf(item: T): Int = zones.indexOfFirst { item in it }

    fun encode(): String = zones.joinToString(";") { zone -> zone.joinToString(",") { it.name } }

    fun flat(): List<ZoneSlot<T>> = buildList {
        zones.forEachIndexed { i, zone ->
            add(ZoneSlot.Header(i))
            zone.forEach { add(ZoneSlot.Item(it, i)) }
        }
    }

    fun moveTo(item: T, zone: Int, index: Int, caps: List<Int>): Zoned<T> {
        val lists = zones.map { it.toMutableList() }
        lists.forEach { it.remove(item) }
        val z = zone.coerceIn(0, lists.lastIndex)
        lists[z].add(index.coerceIn(0, lists[z].size), item)
        return Zoned(spill(lists, caps, item))
    }

    fun dropAt(item: T, flatIndex: Int, caps: List<Int>): Zoned<T> {
        val rest = flat().filterNot { it is ZoneSlot.Item && it.value == item }.toMutableList()
        rest.add(flatIndex.coerceIn(1, rest.size), ZoneSlot.Item(item, -1))
        val lists = List(zones.size) { mutableListOf<T>() }
        var current = 0
        for (slot in rest) {
            when (slot) {
                is ZoneSlot.Header -> current = slot.zone
                is ZoneSlot.Item -> lists[current] += slot.value
            }
        }
        return Zoned(spill(lists, caps, item))
    }

    companion object {

        fun <T : Enum<T>> decode(text: String?, all: List<T>, defaults: Zoned<T>, caps: List<Int>): Zoned<T> {
            if (text.isNullOrBlank()) return defaults
            val byName = all.associateBy { it.name }
            val seen = HashSet<T>()
            val parts = text.split(';')
            val lists = List(defaults.zones.size) { i ->
                val part = parts.getOrNull(i).orEmpty()
                part.split(',').mapNotNull { byName[it.trim()] }.filter { seen.add(it) }.toMutableList()
            }
            for (item in all) {
                if (item !in seen) {
                    val home = defaults.zoneOf(item).takeIf { it >= 0 }?.coerceAtLeast(1)?.coerceAtMost(lists.lastIndex) ?: lists.lastIndex
                    lists[home] += item
                }
            }
            return Zoned(spill(lists, caps, null))
        }

        private fun <T> spill(lists: List<MutableList<T>>, caps: List<Int>, keep: T?): List<List<T>> {
            for (z in 0 until lists.lastIndex) {
                val cap = caps.getOrElse(z) { Int.MAX_VALUE }
                while (lists[z].size > cap) {
                    val zone = lists[z]
                    val victim = if (zone.last() == keep && zone.size >= 2) zone.removeAt(zone.lastIndex - 1) else zone.removeAt(zone.lastIndex)
                    lists[z + 1].add(0, victim)
                }
            }
            return lists.map { it.toList() }
        }
    }
}

data class ActionLayout(
    val browse: Zoned<BrowseAction> = DEFAULT_BROWSE,
    val selection: Zoned<SelectionAction> = DEFAULT_SELECTION,
    val sheet: Zoned<SheetAction> = DEFAULT_SHEET,
) {

    fun encode(): String = "b=${browse.encode()}|s=${selection.encode()}|q=${sheet.encode()}|v=$LAYOUT_VERSION"

    companion object {
        const val LAYOUT_VERSION = 2
        const val BROWSE_SLOTS = 6
        const val SELECTION_SLOTS = 5
        const val QUICK_SLOTS = 4

        val BROWSE_CAPS = listOf(BROWSE_SLOTS, Int.MAX_VALUE, Int.MAX_VALUE)
        val SELECTION_CAPS = listOf(SELECTION_SLOTS, Int.MAX_VALUE, Int.MAX_VALUE)
        val SHEET_CAPS = listOf(QUICK_SLOTS, Int.MAX_VALUE, Int.MAX_VALUE)

        val DEFAULT_BROWSE = Zoned(
            listOf(
                listOf(BrowseAction.BACK, BrowseAction.FORWARD, BrowseAction.NEW, BrowseAction.PASTE, BrowseAction.REFRESH, BrowseAction.SELECT_ALL),
                listOf(BrowseAction.HOME, BrowseAction.UNDO, BrowseAction.BOOKMARKS, BrowseAction.BOOKMARK_FOLDER, BrowseAction.PROTECTED_FOLDERS, BrowseAction.TERMINAL, BrowseAction.SWAP),
                listOf(BrowseAction.SEARCH, BrowseAction.FILTER, BrowseAction.SORT, BrowseAction.JUMP, BrowseAction.HIDDEN_FILES, BrowseAction.UP, BrowseAction.RUN_PROJECT),
            ),
        )

        val DEFAULT_SELECTION = Zoned(
            listOf(
                listOf(SelectionAction.SHARE, SelectionAction.MOVE, SelectionAction.COPY, SelectionAction.RENAME, SelectionAction.DELETE),
                listOf(SelectionAction.OPEN_WITH, SelectionAction.COPY_PATH, SelectionAction.COMPRESS, SelectionAction.EXTRACT, SelectionAction.BOOKMARKS, SelectionAction.BOOKMARK, SelectionAction.PROTECTED_FOLDERS, SelectionAction.PROTECT, SelectionAction.BATCH_RENAME, SelectionAction.SELECT_ALL, SelectionAction.INVERT, SelectionAction.PROPERTIES, SelectionAction.FOLDER_BADGE),
                listOf(SelectionAction.RESTORE, SelectionAction.COPY_CLIPBOARD, SelectionAction.CUT_CLIPBOARD, SelectionAction.MERGE, SelectionAction.COMPARE, SelectionAction.SMALI_TO_DEX, SelectionAction.JAR_TO_DEX, SelectionAction.LINKS, SelectionAction.VAULT),
            ),
        )

        val DEFAULT_SHEET = Zoned(
            listOf(
                listOf(SheetAction.OPEN_WITH, SheetAction.OPEN_AS_TEXT, SheetAction.RENAME, SheetAction.SHARE),
                listOf(SheetAction.AUDIO_INFO, SheetAction.VIDEO_INFO, SheetAction.APK_INFO, SheetAction.COMPRESS, SheetAction.EXTRACT, SheetAction.HEX, SheetAction.VIEW, SheetAction.SIGN_APK, SheetAction.SMALI_TO_DEX, SheetAction.JAR_TO_DEX, SheetAction.CHECKSUM, SheetAction.BOOKMARK, SheetAction.PROTECT, SheetAction.PROPERTIES),
                listOf(SheetAction.OPEN, SheetAction.RESTORE, SheetAction.LINK, SheetAction.CIPHER, SheetAction.VAULT, SheetAction.SPLIT, SheetAction.SELECT),
            ),
        )

        fun decode(text: String?): ActionLayout {
            if (text.isNullOrBlank()) return ActionLayout()
            val parts = text.split('|').mapNotNull { part ->
                val eq = part.indexOf('=')
                if (eq <= 0) null else part.substring(0, eq) to part.substring(eq + 1)
            }.toMap()
            // A saved layout that predates a new action gets it right next to its anchor instead of at the end of the menu.
            var browse = Zoned.decode(parts["b"], BrowseAction.entries, DEFAULT_BROWSE, BROWSE_CAPS)
            if (parts["b"]?.contains("PROTECTED_FOLDERS") != true) {
                browse = browse.placedAfter(BrowseAction.PROTECTED_FOLDERS, BrowseAction.BOOKMARKS, BROWSE_CAPS)
            }
            // Layouts saved before version 2: Bookmarks and Protected folders move to right after Open terminal (once).
            if ((parts["v"]?.toIntOrNull() ?: 1) < 2) {
                browse = browse.placedAfter(BrowseAction.BOOKMARKS, BrowseAction.TERMINAL, BROWSE_CAPS)
                browse = browse.placedAfter(BrowseAction.PROTECTED_FOLDERS, BrowseAction.BOOKMARKS, BROWSE_CAPS)
            }
            // Undo is new: a saved layout gets it right after Home.
            if (parts["b"]?.contains("UNDO") != true) {
                browse = browse.placedAfter(BrowseAction.UNDO, BrowseAction.HOME, BROWSE_CAPS)
            }
            var selection = Zoned.decode(parts["s"], SelectionAction.entries, DEFAULT_SELECTION, SELECTION_CAPS)
            if (parts["s"]?.contains("PROTECTED_FOLDERS") != true) {
                selection = selection.placedBefore(SelectionAction.PROTECTED_FOLDERS, SelectionAction.PROTECT, SELECTION_CAPS)
            }
            if (parts["s"]?.contains("BOOKMARKS") != true) {
                selection = selection.placedBefore(SelectionAction.BOOKMARKS, SelectionAction.BOOKMARK, SELECTION_CAPS)
            }
            return ActionLayout(
                browse = browse,
                selection = selection,
                sheet = Zoned.decode(parts["q"], SheetAction.entries, DEFAULT_SHEET, SHEET_CAPS),
            )
        }

        private fun <T : Enum<T>> Zoned<T>.placedBefore(item: T, anchor: T, caps: List<Int>): Zoned<T> {
            val zone = zoneOf(anchor)
            if (zone < 0 || zoneOf(item) < 0) return this
            return moveTo(item, zone, zones[zone].indexOf(anchor), caps)
        }

        private fun <T : Enum<T>> Zoned<T>.placedAfter(item: T, anchor: T, caps: List<Int>): Zoned<T> {
            val zone = zoneOf(anchor)
            if (zone < 0 || zoneOf(item) < 0) return this
            return moveTo(item, zone, zones[zone].indexOf(anchor) + 1, caps)
        }
    }
}
