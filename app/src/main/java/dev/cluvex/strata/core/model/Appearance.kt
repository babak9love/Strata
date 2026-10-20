package dev.cluvex.strata.core.model

import androidx.annotation.StringRes
import dev.cluvex.strata.R

enum class ThemeMode(@StringRes val label: Int) {
    SYSTEM(R.string.model_theme_system),
    LIGHT(R.string.model_theme_light),
    DARK(R.string.model_theme_dark),
}

enum class ThemeStyle(@StringRes val label: Int) {
    TONAL_SPOT(R.string.model_style_tonal),
    VIBRANT(R.string.model_style_vibrant),
    EXPRESSIVE(R.string.model_style_expressive),
    RAINBOW(R.string.model_style_rainbow),
    FRUIT_SALAD(R.string.model_style_fruit_salad),
    CONTENT(R.string.model_style_content),
    FIDELITY(R.string.model_style_fidelity),
    NEUTRAL(R.string.model_style_neutral),
    MONOCHROME(R.string.model_style_monochrome),
}

enum class ContrastLevel(@StringRes val label: Int, val level: Double) {
    STANDARD(R.string.model_contrast_standard, 0.0),
    MEDIUM(R.string.model_contrast_medium, 0.5),
    HIGH(R.string.model_contrast_high, 1.0),
}

enum class CornerStyle(@StringRes val label: Int, @StringRes val short: Int, val scale: Float) {
    SQUARE(R.string.model_corners_square, R.string.model_corners_square, 0.3f),
    SOFT(R.string.model_corners_soft, R.string.model_corners_soft, 0.65f),
    ROUND(R.string.model_corners_round, R.string.model_corners_round, 1f),
    EXTRA(R.string.model_corners_extra, R.string.model_corners_extra_short, 1.6f),
}

enum class MotionStyle(@StringRes val label: Int) {
    EXPRESSIVE(R.string.model_motion_expressive),
    STANDARD(R.string.model_motion_standard),
    REDUCED(R.string.model_motion_reduced),
}

data class ThemeSeed(@StringRes val name: Int, val argb: Int)

object ThemeSeeds {
    const val STRATA = 0xFF2D6A4F.toInt()

    val presets = listOf(
        ThemeSeed(R.string.model_seed_strata, STRATA),
        ThemeSeed(R.string.model_seed_ocean, 0xFF1565C0.toInt()),
        ThemeSeed(R.string.model_seed_indigo, 0xFF3F51B5.toInt()),
        ThemeSeed(R.string.model_seed_violet, 0xFF6750A4.toInt()),
        ThemeSeed(R.string.model_seed_rose, 0xFFC2185B.toInt()),
        ThemeSeed(R.string.model_seed_coral, 0xFFE64A19.toInt()),
        ThemeSeed(R.string.model_seed_amber, 0xFFF9A825.toInt()),
        ThemeSeed(R.string.model_seed_lime, 0xFF7CB342.toInt()),
        ThemeSeed(R.string.model_seed_teal, 0xFF00897B.toInt()),
        ThemeSeed(R.string.model_seed_earth, 0xFF8D6E63.toInt()),
        ThemeSeed(R.string.model_seed_graphite, 0xFF607D8B.toInt()),
    )

    @StringRes
    fun named(argb: Int): Int? = presets.firstOrNull { it.argb == argb }?.name
}

data class AppearancePrefs(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val wallpaperColors: Boolean = true,
    val seed: Int = ThemeSeeds.STRATA,
    val style: ThemeStyle = ThemeStyle.TONAL_SPOT,
    val contrast: ContrastLevel = ContrastLevel.STANDARD,
    val pureBlack: Boolean = false,
    val textScale: Float = 1f,
    val corners: CornerStyle = CornerStyle.ROUND,
    val colorfulIcons: Boolean = true,
    val motion: MotionStyle = MotionStyle.EXPRESSIVE,
    val haptics: Boolean = true,
    val sounds: Boolean = false,
    val soundsInSilent: Boolean = true,
) {
    companion object {
        const val MIN_TEXT_SCALE = 0.85f
        const val MAX_TEXT_SCALE = 1.3f

        fun textScale(value: Float): Float = ((value.coerceIn(MIN_TEXT_SCALE, MAX_TEXT_SCALE) * 20).let { kotlin.math.round(it) }) / 20f
    }
}
