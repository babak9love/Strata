package dev.cluvex.strata.core.model

import androidx.annotation.StringRes
import dev.cluvex.strata.R

enum class UiDensity(
    @StringRes val label: Int,
    val iconDp: Int,
    val rowPadDp: Int,
    val nameSp: Int,
    val metaSp: Int,
    val nameMaxLines: Int,
) {
    COMPACT(R.string.model_density_compact, iconDp = 46, rowPadDp = 3, nameSp = 13, metaSp = 10, nameMaxLines = 1),
    COMFORTABLE(R.string.model_density_comfortable, iconDp = 52, rowPadDp = 6, nameSp = 14, metaSp = 11, nameMaxLines = 1),
    LARGE(R.string.model_density_large, iconDp = 62, rowPadDp = 9, nameSp = 16, metaSp = 12, nameMaxLines = 2),
    ;

    companion object {
        val DEFAULT = COMPACT

        fun fromName(name: String?): UiDensity = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
