package dev.cluvex.strata.ui.expressive

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import dev.cluvex.strata.R
import dev.cluvex.strata.core.art.Duotone
import dev.cluvex.strata.core.model.MotionStyle
import dev.cluvex.strata.ui.theme.LocalAppearance
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class Art(val resource: String) {
    WELCOME("art_welcome"),
    EMPTY_FOLDER("art_empty_folder"),
    EMPTY_BIN("art_empty_bin"),
}

@DrawableRes
fun Art.drawable(): Int = when (this) {
    Art.WELCOME -> R.drawable.art_welcome
    Art.EMPTY_FOLDER -> R.drawable.art_empty_folder
    Art.EMPTY_BIN -> R.drawable.art_empty_bin
}

enum class ArtTone { PRIMARY, SECONDARY, TERTIARY, ERROR }

@Immutable
data class ToneColors(
    val deep: Color,
    val light: Color,
    val accent: Color,
    val container: Color,
    val onContainer: Color,
    val backdrop: Color,
)

@Composable
fun ArtTone.colors(): ToneColors {
    val c = MaterialTheme.colorScheme
    return remember(this, c) {
        val (main, container, onContainer) = when (this) {
            ArtTone.PRIMARY -> Triple(c.primary, c.primaryContainer, c.onPrimaryContainer)
            ArtTone.SECONDARY -> Triple(c.secondary, c.secondaryContainer, c.onSecondaryContainer)
            ArtTone.TERTIARY -> Triple(c.tertiary, c.tertiaryContainer, c.onTertiaryContainer)
            ArtTone.ERROR -> Triple(c.error, c.errorContainer, c.onErrorContainer)
        }
        val backdrop = when (this) {
            ArtTone.PRIMARY -> c.secondaryContainer
            ArtTone.SECONDARY -> c.primaryContainer.copy(alpha = 0.6f)
            ArtTone.TERTIARY -> c.tertiaryContainer.copy(alpha = 0.7f)
            ArtTone.ERROR -> c.errorContainer.copy(alpha = 0.6f)
        }
        val mainIsDeep = main.luminance() < container.luminance()
        ToneColors(
            deep = if (mainIsDeep) main else container,
            light = if (mainIsDeep) container else main,
            accent = main,
            container = container,
            onContainer = onContainer,
            backdrop = backdrop,
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
object HeroShapes {
    val calm: List<RoundedPolygon> by lazy { listOf(MaterialShapes.Cookie9Sided, MaterialShapes.Cookie12Sided, MaterialShapes.SoftBurst) }
    val happy: List<RoundedPolygon> by lazy { listOf(MaterialShapes.Sunny, MaterialShapes.VerySunny, MaterialShapes.Cookie12Sided) }
    val alert: List<RoundedPolygon> by lazy { listOf(MaterialShapes.Cookie4Sided, MaterialShapes.PuffyDiamond, MaterialShapes.Puffy) }
    val danger: List<RoundedPolygon> by lazy { listOf(MaterialShapes.Cookie7Sided, MaterialShapes.Clover4Leaf, MaterialShapes.Cookie6Sided) }
}

private const val LOOP_MILLIS = 24_000

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ExpressiveHero(
    modifier: Modifier = Modifier,
    art: Art? = null,
    icon: ImageVector? = null,
    picture: ImageBitmap? = null,
    tone: ArtTone = ArtTone.PRIMARY,
    size: Dp = 112.dp,
    shapes: List<RoundedPolygon> = HeroShapes.calm,
) {
    val colors = tone.colors()
    val moving = LocalAppearance.current.motion != MotionStyle.REDUCED
    val entrance = remember { Animatable(if (moving) 0f else 1f) }
    val spec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    LaunchedEffect(Unit) { entrance.animateTo(1f, spec) }
    val loop = rememberInfiniteTransition()
    val time = loop.animateFloat(0f, 1f, infiniteRepeatable(tween(LOOP_MILLIS, easing = LinearEasing)))
    val clock = remember(moving) { { if (moving) time.value else 0f } }
    val tag = art?.resource ?: if (picture != null) "picture" else "icon"
    Box(
        modifier
            .size(size)
            .testTag("hero-$tag")
            .graphicsLayer {
                val p = entrance.value
                val s = 0.6f + 0.4f * p
                scaleX = s
                scaleY = s
                alpha = p.coerceIn(0f, 1f)
            },
        contentAlignment = Alignment.Center,
    ) {
        val filled = art == null
        MorphingShape(shapes, clock, if (filled) colors.container else colors.backdrop, Modifier.fillMaxSize(if (filled) 1f else 0.92f))
        Sparkles(clock, colors.accent, Modifier.fillMaxSize())
        val bob = Modifier.graphicsLayer {
            val wave = sin(clock() * 12f * PI.toFloat())
            translationY = wave * this.size.height * 0.025f
            rotationZ = wave * 1.6f
        }
        when {
            art != null -> {
                val filter = remember(colors.deep, colors.light) {
                    ColorFilter.colorMatrix(ColorMatrix(Duotone.matrix(colors.deep.toArgb(), colors.light.toArgb())))
                }
                Image(painterResource(art.drawable()), contentDescription = null, modifier = bob.fillMaxSize(0.86f), colorFilter = filter)
            }
            picture != null -> Image(picture, contentDescription = null, modifier = bob.fillMaxSize(0.5f).clip(MaterialTheme.shapes.large))
            icon != null -> Icon(icon, contentDescription = null, modifier = bob.size(size * 0.42f), tint = colors.onContainer)
        }
    }
}

@Composable
private fun MorphingShape(shapes: List<RoundedPolygon>, clock: () -> Float, color: Color, modifier: Modifier) {
    val morphs = remember(shapes) { shapes.indices.map { Morph(shapes[it], shapes[(it + 1) % shapes.size]) } }
    val bounds = remember(morphs) {
        val out = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (m in morphs) {
            val b = m.calculateMaxBounds()
            out[0] = min(out[0], b[0])
            out[1] = min(out[1], b[1])
            out[2] = max(out[2], b[2])
            out[3] = max(out[3], b[3])
        }
        out
    }
    val path = remember { android.graphics.Path() }
    val matrix = remember { android.graphics.Matrix() }
    Canvas(modifier) {
        val time = clock()
        val phase = time * morphs.size
        val index = floor(phase).toInt().mod(morphs.size)
        val progress = smoothstep(0.6f, 1f, phase - floor(phase))
        path.rewind()
        morphs[index].toPath(progress, path)
        val w = bounds[2] - bounds[0]
        val h = bounds[3] - bounds[1]
        val scale = min(size.width / w, size.height / h)
        matrix.setTranslate(-(bounds[0] + w / 2f), -(bounds[1] + h / 2f))
        matrix.postScale(scale, scale)
        matrix.postRotate(time * 360f)
        matrix.postTranslate(size.width / 2f, size.height / 2f)
        path.transform(matrix)
        drawPath(path.asComposePath(), color)
    }
}

private class Spark(val x: Float, val y: Float, val radius: Float, val offset: Float)

private val sparks = listOf(
    Spark(0.88f, 0.16f, 0.055f, 0f),
    Spark(0.1f, 0.38f, 0.04f, 0.37f),
    Spark(0.84f, 0.84f, 0.045f, 0.71f),
)

@Composable
private fun Sparkles(clock: () -> Float, color: Color, modifier: Modifier) {
    val star = remember { Path() }
    Canvas(modifier) {
        val time = clock()
        for (s in sparks) {
            val local = (time * 4f + s.offset) % 1f
            val pulse = sin(local * PI.toFloat())
            val r = size.minDimension * s.radius * (0.35f + 0.65f * pulse)
            val c = Offset(size.width * s.x, size.height * s.y)
            star.reset()
            star.moveTo(c.x, c.y - r)
            star.quadraticTo(c.x, c.y, c.x + r, c.y)
            star.quadraticTo(c.x, c.y, c.x, c.y + r)
            star.quadraticTo(c.x, c.y, c.x - r, c.y)
            star.quadraticTo(c.x, c.y, c.x, c.y - r)
            star.close()
            drawPath(star, color.copy(alpha = 0.25f + 0.55f * pulse))
        }
    }
}

private fun smoothstep(from: Float, to: Float, x: Float): Float {
    val t = ((x - from) / (to - from)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

@Composable
fun ExpressiveEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    art: Art? = null,
    icon: ImageVector? = null,
    tone: ArtTone = ArtTone.PRIMARY,
    body: String? = null,
    maxHero: Dp = 168.dp,
    shapes: List<RoundedPolygon> = HeroShapes.calm,
    action: (@Composable () -> Unit)? = null,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val hero = if (maxWidth == Dp.Infinity) maxHero else minOf(maxHero, maxWidth * 0.6f)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ExpressiveHero(art = art, icon = icon, tone = tone, size = hero, shapes = shapes)
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                color = if (tone == ArtTone.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            if (body != null) {
                Text(body, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            action?.invoke()
        }
    }
}
