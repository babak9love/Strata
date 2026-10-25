package dev.cluvex.strata.ui.theme

import android.app.Activity
import android.app.WallpaperManager
import android.content.Context
import android.os.Build
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import dev.cluvex.strata.core.model.AppearancePrefs
import dev.cluvex.strata.core.model.ContrastLevel
import dev.cluvex.strata.core.model.MotionStyle
import dev.cluvex.strata.core.model.ThemeMode
import dev.cluvex.strata.core.model.ThemeStyle

val LocalAppearance = staticCompositionLocalOf { AppearancePrefs() }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun StrataTheme(
    appearance: AppearancePrefs = AppearancePrefs(),
    content: @Composable () -> Unit,
) {
    val dark = when (appearance.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colorScheme = remember(appearance.wallpaperColors, appearance.seed, appearance.style, appearance.contrast, appearance.pureBlack, dark) {
        StrataColors.scheme(context, appearance, dark)
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    val density = LocalDensity.current
    val scaled = remember(density.density, density.fontScale, appearance.textScale) {
        Density(density.density, density.fontScale * appearance.textScale)
    }
    val haptics = LocalHapticFeedback.current
    val shapes = remember(appearance.corners) { strataShapes(appearance.corners.scale) }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        motionScheme = motionScheme(appearance.motion),
        shapes = shapes,
        typography = StrataTypography,
    ) {
        CompositionLocalProvider(
            LocalDensity provides scaled,
            LocalHapticFeedback provides if (appearance.haptics) haptics else NoHaptics,
            LocalAppearance provides appearance,
            content = content,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun motionScheme(style: MotionStyle): MotionScheme = when (style) {
    MotionStyle.EXPRESSIVE -> MotionScheme.expressive()
    MotionStyle.STANDARD -> MotionScheme.standard()
    MotionStyle.REDUCED -> ReducedMotion
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private object ReducedMotion : MotionScheme {
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> = snap()
    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> = snap()
}

private object NoHaptics : HapticFeedback {
    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) = Unit
}

object StrataColors {

    val wallpaperAvailable: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    fun scheme(context: Context, a: AppearancePrefs, dark: Boolean): ColorScheme {
        val wallpaper = if (a.wallpaperColors && wallpaperAvailable) {
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            null
        }
        val base = when {
            wallpaper != null && a.style == ThemeStyle.TONAL_SPOT && a.contrast == ContrastLevel.STANDARD -> wallpaper
            wallpaper != null -> generate(wallpaperSeed(context) ?: wallpaper.primary.toArgb(), a.style, a.contrast, dark)
            else -> generate(a.seed, a.style, a.contrast, dark)
        }
        return if (dark && a.pureBlack) base.pureBlack() else base
    }

    fun generate(seed: Int, style: ThemeStyle, contrast: ContrastLevel, dark: Boolean): ColorScheme =
        dynamicColorScheme(
            seedColor = Color(seed),
            isDark = dark,
            isAmoled = false,
            style = style.palette,
            contrastLevel = contrast.level,
        )

    private fun wallpaperSeed(context: Context): Int? = runCatching {
        WallpaperManager.getInstance(context).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)?.primaryColor?.toArgb()
    }.getOrNull()

    private val ThemeStyle.palette: PaletteStyle
        get() = when (this) {
            ThemeStyle.TONAL_SPOT -> PaletteStyle.TonalSpot
            ThemeStyle.VIBRANT -> PaletteStyle.Vibrant
            ThemeStyle.EXPRESSIVE -> PaletteStyle.Expressive
            ThemeStyle.RAINBOW -> PaletteStyle.Rainbow
            ThemeStyle.FRUIT_SALAD -> PaletteStyle.FruitSalad
            ThemeStyle.CONTENT -> PaletteStyle.Content
            ThemeStyle.FIDELITY -> PaletteStyle.Fidelity
            ThemeStyle.NEUTRAL -> PaletteStyle.Neutral
            ThemeStyle.MONOCHROME -> PaletteStyle.Monochrome
        }
}

fun ColorScheme.pureBlack(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = lerp(surfaceContainerLow, Color.Black, 0.6f),
    surfaceContainer = lerp(surfaceContainer, Color.Black, 0.5f),
    surfaceContainerHigh = lerp(surfaceContainerHigh, Color.Black, 0.4f),
    surfaceContainerHighest = lerp(surfaceContainerHighest, Color.Black, 0.3f),
    surfaceBright = lerp(surfaceBright, Color.Black, 0.3f),
    surfaceVariant = lerp(surfaceVariant, Color.Black, 0.3f),
)
