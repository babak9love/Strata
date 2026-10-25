package dev.cluvex.strata.feature.explorer.dnd

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.cluvex.strata.core.model.MotionStyle
import dev.cluvex.strata.ui.expressive.MorphPainter
import dev.cluvex.strata.ui.expressive.rememberPolygonMorph
import dev.cluvex.strata.ui.theme.LocalAppearance

class DropLook(
    val hovered: Boolean,
    val ghosted: Boolean,
    val gulp: Int,
    val onSpringOpen: (() -> Unit)?,
    val spot: Modifier,
    val anchor: Modifier,
) {
    companion object {
        val NONE = DropLook(hovered = false, ghosted = false, gulp = 0, onSpringOpen = null, spot = Modifier, anchor = Modifier)
    }
}

class DropAnimation(val hover: State<Float>, val ring: State<Float>, val gulp: State<Float>, val spin: State<Float>)

@Composable
fun rememberDropAnimation(look: DropLook): DropAnimation {
    val reduced = LocalAppearance.current.motion == MotionStyle.REDUCED
    val hover = animateFloatAsState(
        if (look.hovered) 1f else 0f,
        if (reduced) snap() else spring(dampingRatio = 0.48f, stiffness = 420f),
        label = "dropHover",
    )
    val ring = remember { Animatable(0f) }
    val open by rememberUpdatedState(look.onSpringOpen)
    LaunchedEffect(look.hovered) {
        ring.snapTo(0f)
        if (look.hovered && open != null) {
            ring.animateTo(1f, tween(SPRING_OPEN_MS, easing = LinearEasing))
            open?.invoke()
            ring.snapTo(0f)
        }
    }
    val gulp = remember { Animatable(1f) }
    LaunchedEffect(look.gulp) {
        if (look.gulp != 0 && !reduced) {
            gulp.snapTo(1.34f)
            gulp.animateTo(1f, spring(dampingRatio = 0.28f, stiffness = 520f))
        }
    }
    val spin = if (look.hovered && !reduced) {
        rememberInfiniteTransition(label = "dropSpin").animateFloat(0f, 360f, infiniteRepeatable(tween(4200, easing = LinearEasing)), label = "dropSpinAngle")
    } else {
        remember { mutableFloatStateOf(0f) }
    }
    return DropAnimation(hover, ring.asState(), gulp.asState(), spin)
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun Modifier.dropHaloBehind(anim: DropAnimation, reach: Float = 1f): Modifier {
    val color = MaterialTheme.colorScheme.primary
    val morph = rememberPolygonMorph(MaterialShapes.Circle, MaterialShapes.Cookie12Sided)
    val painter = remember { MorphPainter() }
    return this
        .drawBehind {
            val h = anim.hover.value
            if (h > 0.01f) {
                with(painter) { draw(morph, h, color, rotation = anim.spin.value, scale = reach * (1f + 0.3f * h), alpha = 0.28f * h) }
                val r = anim.ring.value
                if (r > 0f) {
                    val out = size.minDimension * (reach * (1f + 0.3f * h) - 1f) / 2f
                    drawArc(
                        color = color,
                        startAngle = -90f,
                        sweepAngle = 360f * r,
                        useCenter = false,
                        topLeft = Offset(-out, -out),
                        size = Size(size.width + 2 * out, size.height + 2 * out),
                        style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
                    )
                }
            }
        }
        .graphicsLayer {
            val s = (1f + 0.12f * anim.hover.value) * anim.gulp.value
            scaleX = s
            scaleY = s
        }
}

@Composable
fun DropHalo(anim: DropAnimation, anchor: Modifier, content: @Composable () -> Unit) {
    Box(anchor.dropHaloBehind(anim).then(if (anim.hover.value > 0.5f) Modifier.testTag("drop-target") else Modifier), contentAlignment = Alignment.Center) {
        content()
    }
}
