package dev.cluvex.strata.feature.explorer.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.luminance

/**
 * Re-colours the golden folder art with the theme colour while keeping its shading (the darker back tab, the lighter front).
 * Every pixel becomes its brightness times the theme colour, so the folder follows the colour chosen in the settings.
 */
@Composable
fun rememberFolderTint(): ColorFilter {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    val color = if (dark) colors.primary else colors.primaryContainer
    return remember(color) {
        // Brightness of the original folder body (#FDCF1A) is about 0.78; normalise so the tinted folder is as bright as the theme colour.
        val ref = 0.78f
        val wr = 0.299f / ref
        val wg = 0.587f / ref
        val wb = 0.114f / ref
        ColorFilter.colorMatrix(
            ColorMatrix(
                floatArrayOf(
                    wr * color.red, wg * color.red, wb * color.red, 0f, 0f,
                    wr * color.green, wg * color.green, wb * color.green, 0f, 0f,
                    wr * color.blue, wg * color.blue, wb * color.blue, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
    }
}
