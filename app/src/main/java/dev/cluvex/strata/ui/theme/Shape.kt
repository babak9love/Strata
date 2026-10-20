package dev.cluvex.strata.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

fun strataShapes(scale: Float): Shapes {
    fun r(dp: Int) = RoundedCornerShape((dp * scale).coerceAtLeast(2f).dp)
    return Shapes(
        extraSmall = r(8),
        small = r(12),
        medium = r(16),
        large = r(24),
        extraLarge = r(32),
        largeIncreased = r(28),
        extraLargeIncreased = r(36),
        extraExtraLarge = r(48),
    )
}

val StrataShapes = strataShapes(1f)

/** Shape of the rounded tile behind file and folder icons (the icon-shape setting was removed). */
val IconTileShape: Shape = RoundedCornerShape(30)
