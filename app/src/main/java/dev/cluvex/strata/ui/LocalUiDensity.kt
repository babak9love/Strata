package dev.cluvex.strata.ui

import androidx.compose.runtime.staticCompositionLocalOf
import dev.cluvex.strata.core.data.StrataSettings
import dev.cluvex.strata.core.model.UiDensity

val LocalUiDensity = staticCompositionLocalOf { UiDensity.DEFAULT }

val LocalSettings = staticCompositionLocalOf { StrataSettings() }
