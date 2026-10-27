package dev.cluvex.strata.core.model

import androidx.annotation.StringRes
import dev.cluvex.strata.R

enum class ViewMode(@StringRes val label: Int) {
    LIST(R.string.model_view_list),
    GRID(R.string.model_view_grid),
}

enum class DateStyle {
    COMPACT,
    ISO,
    LONG,
    RELATIVE,
}

enum class SizeUnits {
    BINARY,
    DECIMAL,
}

data class ListPrefs(
    val viewMode: ViewMode = ViewMode.GRID,
    val thumbnails: Boolean = true,
    val folderBadges: Boolean = true,
    val showDate: Boolean = true,
    val showSize: Boolean = true,
    val dateStyle: DateStyle = DateStyle.COMPACT,
    val use24Hour: Boolean = true,
    val sizeUnits: SizeUnits = SizeUnits.BINARY,
    val wrapNames: Boolean = false,
    val showExtensions: Boolean = true,
    val persianCalendar: Boolean = true,
    val tileFrame: Boolean = false,
    val gridColumns: Int = 4,
    /** Size of file icons (not folders) in the list and grid, in percent (100 = default). */
    val itemScale: Int = DEFAULT_ITEM_SCALE,
    /** Size of folder icons only, in percent. */
    val folderScale: Int = DEFAULT_FOLDER_SCALE,
    /** Size of the file name text, in percent. */
    val nameScale: Int = 100,
    /** Size of the date / size text under each name, in percent. */
    val metaScale: Int = 100,
    val showParentEntry: Boolean = false,
)

/** Default icon size, in percent. */
const val DEFAULT_ITEM_SCALE = 100
const val DEFAULT_FOLDER_SCALE = 111

object ItemScale {
    const val MIN = 70
    const val MAX = 160
    const val STEP = 10
    fun clamp(v: Int) = v.coerceIn(MIN, MAX)
}

/** How the bookmark chips on the main screen look. */
enum class BookmarkStyle(@StringRes val label: Int) {
    /** Filled pill with a tile holding the bookmark's first letter. */
    CLASSIC(R.string.model_bookmark_classic),

    /** Pill with an outline and a folder icon. */
    OUTLINED(R.string.model_bookmark_outlined),

    /** Plain text with a line under the open bookmark. */
    UNDERLINE(R.string.model_bookmark_underline),

    /** Rounded box shaped and coloured like the path box above the list, holding just the name. */
    PATH_CARD(R.string.model_bookmark_path_card),
}

enum class StartFolder(@StringRes val label: Int) {
    HOME(R.string.model_start_home),
    LAST(R.string.model_start_last),
    CUSTOM(R.string.model_start_custom),
}

data class BehaviorPrefs(
    val startFolder: StartFolder = StartFolder.HOME,
    val startPath: String = "",
    val doubleBackToExit: Boolean = true,
    val iconTapSelects: Boolean = true,
    /** Swiping right from the left edge opens the side menu (drawer). Off by default so it does not clash with the bookmark swipe. */
    val swipeOpensDrawer: Boolean = false,
    /** Shows the bookmark chips under the title on the main screen. Has nothing to do with the side menu. */
    val showBookmarkBar: Boolean = true,
    /** Puts the bookmark chips under the bottom toolbar instead of under the title. */
    val bookmarkBarAtBottom: Boolean = false,
    /** Look of the bookmark chips. Null follows the folder icon style: outlined with folder-shaped icons, classic without. */
    val bookmarkStyle: BookmarkStyle? = null,
    /** Swiping left or right on the file list switches between bookmarks. */
    val bookmarkSwipe: Boolean = true,
    /** The bookmark swipe wraps around: the last bookmark is followed by the first, and the first is preceded by the last. */
    val bookmarkSwipeWrap: Boolean = true,
    val keepScreenOnTasks: Boolean = false,
    val videoAutoHide: Boolean = true,
    val resumeAudio: Boolean = true,
    val resumeVideo: Boolean = true,
    val backToListAudio: Boolean = true,
    val backToListVideo: Boolean = true,
    val backgroundAudio: Boolean = true,
    val backgroundVideo: Boolean = true,
    val seekBackSeconds: Int = 5,
    val seekForwardSeconds: Int = 15,
    /** "Save" tick of the speed dialog: when on, the chosen playback speed is kept for next time. */
    val saveSpeedVideo: Boolean = false,
    val speedVideo: Float = 1f,
    val saveSpeedAudio: Boolean = false,
    val speedAudio: Float = 1f,
    /** The video player's own volume level (0.05 to 1), independent of the phone's volume. */
    val videoVolume: Float = 1f,
    /** The music player's own volume level (0.05 to 1), independent of the phone's volume. */
    val audioVolume: Float = 1f,
    /** Repeat mode of the video player, remembered between sessions (0 = off, 1 = one, 2 = all; same values as Player.REPEAT_MODE_*). */
    val repeatVideo: Int = 0,
    /** Shuffle state of the video player, remembered between sessions. */
    val shuffleVideo: Boolean = false,
    /** Repeat mode of the music player, remembered between sessions (0 = off, 1 = one, 2 = all). */
    val repeatAudio: Int = 0,
    /** Shuffle state of the music player, remembered between sessions. */
    val shuffleAudio: Boolean = false,
    /** "Play alone" of the video player: stop other players while it plays, and pause when another app plays or a call comes in. */
    val playAloneVideo: Boolean = true,
    /** "Play alone" of the music player (same meaning). */
    val playAloneAudio: Boolean = true,
    /** Ids of the side menu items and sections the user hid with the pencil. Empty shows everything. */
    val drawerHidden: Set<String> = emptySet(),
)

enum class ConflictChoice(@StringRes val label: Int) {
    ASK(R.string.model_conflict_ask),
    KEEP_BOTH(R.string.common_keep_both),
    OVERWRITE(R.string.common_replace),
    SKIP(R.string.common_skip),
}

data class OperationPrefs(
    val conflict: ConflictChoice = ConflictChoice.ASK,
    val keepModifiedTime: Boolean = true,
    val undoEnabled: Boolean = true,
)

enum class LockDelay(val seconds: Int, @StringRes val label: Int) {
    IMMEDIATELY(0, R.string.model_lock_immediately),
    SECONDS_30(30, R.string.model_lock_30_seconds),
    MINUTE_1(60, R.string.model_lock_1_minute),
    MINUTES_5(300, R.string.model_lock_5_minutes),
    MINUTES_15(900, R.string.model_lock_15_minutes),
}

data class SecurityPrefs(
    val secureScreen: Boolean = false,
    val appLock: Boolean = false,
    val lockDelay: LockDelay = LockDelay.IMMEDIATELY,
    val vaultLockDelay: LockDelay = LockDelay.MINUTE_1,
)

enum class ReaderTheme(@StringRes val label: Int) {
    PAPER(R.string.model_reader_paper),
    SEPIA(R.string.model_reader_sepia),
    NIGHT(R.string.model_reader_night),
}

data class ReaderPrefs(
    val theme: ReaderTheme = ReaderTheme.PAPER,
    val keepScreenOn: Boolean = true,
)
