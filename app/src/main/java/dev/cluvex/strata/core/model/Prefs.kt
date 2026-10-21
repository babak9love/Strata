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
    val viewMode: ViewMode = ViewMode.LIST,
    val thumbnails: Boolean = true,
    val apkIcons: Boolean = true,
    val showDate: Boolean = true,
    val showSize: Boolean = true,
    val dateStyle: DateStyle = DateStyle.COMPACT,
    val use24Hour: Boolean = true,
    val sizeUnits: SizeUnits = SizeUnits.BINARY,
    val wrapNames: Boolean = false,
    val showExtensions: Boolean = true,
    val persianCalendar: Boolean = true,
    val tileFrame: Boolean = true,
    val gridColumns: Int = 0,
    /** Size of file icons (not folders) in the list and grid, in percent (140 = default). */
    val itemScale: Int = DEFAULT_ITEM_SCALE,
    /** Size of folder icons only, in percent (140 = default). */
    val folderScale: Int = DEFAULT_ITEM_SCALE,
    /** Size of the file name text, in percent. */
    val nameScale: Int = 100,
    /** Size of the date / size text under each name, in percent. */
    val metaScale: Int = 100,
    val showParentEntry: Boolean = true,
)

/** Default icon size, in percent. */
const val DEFAULT_ITEM_SCALE = 140

object ItemScale {
    const val MIN = 70
    const val MAX = 160
    const val STEP = 10
    fun clamp(v: Int) = v.coerceIn(MIN, MAX)
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
    val keepScreenOnTasks: Boolean = false,
    val videoAutoHide: Boolean = true,
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
