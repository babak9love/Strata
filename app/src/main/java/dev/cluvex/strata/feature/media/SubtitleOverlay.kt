package dev.cluvex.strata.feature.media

import android.content.Context
import android.graphics.Typeface
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup

/**
 * How subtitles look. [scale] multiplies the text size, [textColor] and [backgroundColor] are ARGB values (the
 * background one keeps its own opacity and only shows while [background] is on). Once the user has dragged the
 * subtitles on the picture ([moved]), they sit [lift] dp above their usual place at the bottom and [shiftX] dp to
 * the right of the centre (negative: left), whether the controls show or not. Kept between runs.
 */
data class SubtitleStyle(
    val scale: Float = 1f,
    val background: Boolean = false,
    val textColor: Int = DEFAULT_SUBTITLE_TEXT,
    val backgroundColor: Int = DEFAULT_SUBTITLE_BACKGROUND,
    val lift: Float = 0f,
    val shiftX: Float = 0f,
    val moved: Boolean = false,
)

const val DEFAULT_SUBTITLE_TEXT = 0xFFFFFFFF.toInt()
const val DEFAULT_SUBTITLE_BACKGROUND = 0x99000000.toInt()
const val SUBTITLE_SCALE_MIN = 0.5f
const val SUBTITLE_SCALE_MAX = 3f
const val SUBTITLE_LIFT_MIN = -24f
const val SUBTITLE_LIFT_MAX = 1500f
const val SUBTITLE_SHIFT_MAX = 1500f

/** The four size presets offered in the subtitle dialog (the slider reaches everything in between). */
val SUBTITLE_SCALES = listOf(0.8f, 1f, 1.3f, 1.7f)

object SubtitleStylePrefs {
    private const val FILE = "subtitle_style"

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun load(ctx: Context): SubtitleStyle {
        val p = prefs(ctx)
        return SubtitleStyle(
            scale = p.getFloat("scale", 1f).coerceIn(SUBTITLE_SCALE_MIN, SUBTITLE_SCALE_MAX),
            background = p.getBoolean("background", false),
            textColor = p.getInt("textColor", DEFAULT_SUBTITLE_TEXT),
            backgroundColor = p.getInt("backgroundColor", DEFAULT_SUBTITLE_BACKGROUND),
            lift = p.getFloat("lift", 0f).coerceIn(SUBTITLE_LIFT_MIN, SUBTITLE_LIFT_MAX),
            shiftX = p.getFloat("shiftX", 0f).coerceIn(-SUBTITLE_SHIFT_MAX, SUBTITLE_SHIFT_MAX),
            moved = p.getBoolean("moved", false),
        )
    }

    fun save(ctx: Context, style: SubtitleStyle) {
        prefs(ctx).edit()
            .putFloat("scale", style.scale)
            .putBoolean("background", style.background)
            .putInt("textColor", style.textColor)
            .putInt("backgroundColor", style.backgroundColor)
            .putFloat("lift", style.lift)
            .putFloat("shiftX", style.shiftX)
            .putBoolean("moved", style.moved)
            .apply()
    }
}

/**
 * Draws the subtitle text of the playing video over the picture. Text subtitles only; picture based ones
 * (PGS, DVB, VobSub) are not drawn. Cues placed near the top (some ASS subtitles) are drawn at the top.
 * Touches pass through to the player underneath, except on the lower subtitle itself: dragging it moves it
 * ([onStyleChange] while the finger moves, [onStyleChangeFinished] when it lifts) and a tap on it is handed to
 * [onTap], so it still shows or hides the controls.
 */
@Composable
fun SubtitleOverlay(
    player: Player?,
    style: SubtitleStyle,
    controlsVisible: Boolean,
    modifier: Modifier = Modifier,
    onStyleChange: (SubtitleStyle) -> Unit = {},
    onStyleChangeFinished: (SubtitleStyle) -> Unit = {},
    onTap: () -> Unit = {},
) {
    var cues by remember(player) { mutableStateOf(player?.currentCues?.cues.orEmpty()) }
    DisposableEffect(player) {
        if (player == null) {
            onDispose { }
        } else {
            val listener = object : Player.Listener {
                override fun onCues(cueGroup: CueGroup) {
                    cues = cueGroup.cues
                }
            }
            cues = player.currentCues.cues
            player.addListener(listener)
            onDispose { player.removeListener(listener) }
        }
    }
    val config = LocalConfiguration.current
    val density = LocalDensity.current.density
    // Where the user dragged them stays within the screen, also after a turn to the other orientation.
    val maxLift = (config.screenHeightDp - 120f).coerceAtLeast(0f)
    val maxShift = config.screenWidthDp / 3f
    val ownPad = (24f + style.lift).coerceIn(0f, maxLift).dp
    // Not moved by the user: lifted above the seek bar and buttons while the controls are showing.
    val targetPad = if (style.moved) ownPad else if (controlsVisible) 190.dp else 24.dp
    val animatedPad by animateDpAsState(targetPad, label = "subtitle-bottom")
    var dragging by remember { mutableStateOf(false) }
    // Follows the finger right away while dragging; eases otherwise.
    val bottomPad = if (dragging) targetPad else animatedPad
    val latestStyle by rememberUpdatedState(style)
    val latestChange by rememberUpdatedState(onStyleChange)
    val latestFinished by rememberUpdatedState(onStyleChangeFinished)
    val latestTap by rememberUpdatedState(onTap)
    val latestControls by rememberUpdatedState(controlsVisible)
    val textCues = cues.filter { it.text != null && it.text.toString().isNotBlank() }
    if (textCues.isEmpty()) return
    val top = textCues.filter { it.lineType == Cue.LINE_TYPE_FRACTION && it.line != Cue.DIMEN_UNSET && it.line < 0.35f }
    val bottom = textCues - top.toSet()

    Box(modifier.padding(horizontal = 24.dp)) {
        if (top.isNotEmpty()) {
            CueColumn(top, style, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = if (controlsVisible) 64.dp else 16.dp))
        }
        if (bottom.isNotEmpty()) {
            CueColumn(
                bottom,
                style,
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = bottomPad)
                    .offset(x = if (style.moved) style.shiftX.coerceIn(-maxShift, maxShift).dp else 0.dp)
                    .pointerInput(Unit) { detectTapGestures(onTap = { latestTap() }) }
                    .pointerInput(maxLift, maxShift) {
                        detectDragGestures(
                            onDragStart = { dragging = true },
                            onDragEnd = {
                                dragging = false
                                latestFinished(latestStyle)
                            },
                            onDragCancel = {
                                dragging = false
                                latestFinished(latestStyle)
                            },
                            onDrag = { change, drag ->
                                change.consume()
                                val cur = latestStyle
                                // Up raises the subtitles (more lift); left and right move them sideways.
                                val startLift = if (cur.moved) cur.lift else if (latestControls) 190f - 24f else 0f
                                latestChange(
                                    cur.copy(
                                        moved = true,
                                        lift = (startLift - drag.y / density).coerceIn(SUBTITLE_LIFT_MIN, maxLift - 24f),
                                        shiftX = (cur.shiftX + drag.x / density).coerceIn(-maxShift, maxShift),
                                    ),
                                )
                            },
                        )
                    },
            )
        }
    }
}

@Composable
private fun CueColumn(cues: List<Cue>, style: SubtitleStyle, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        cues.forEach { cue ->
            val text = cue.text ?: return@forEach
            Text(
                text = text.toAnnotated(),
                color = Color(style.textColor),
                fontSize = (18f * style.scale).sp,
                lineHeight = (24f * style.scale).sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                // The direction follows the text itself, so a Persian line keeps its punctuation on the correct side.
                style = TextStyle(
                    textDirection = TextDirection.Content,
                    shadow = if (style.background && Color(style.backgroundColor).alpha > 0.3f) null else Shadow(Color.Black, Offset.Zero, 8f),
                ),
                modifier = Modifier
                    .background(if (style.background) Color(style.backgroundColor) else Color.Transparent, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

/** Keeps bold, italic, underline, strike-through and colour from the subtitle's own styling. */
private fun CharSequence.toAnnotated(): AnnotatedString {
    val source = this
    if (source !is Spanned) return AnnotatedString(source.toString())
    return buildAnnotatedString {
        append(source.toString())
        source.getSpans(0, source.length, Any::class.java).forEach { span ->
            val start = source.getSpanStart(span)
            val end = source.getSpanEnd(span)
            if (start < 0 || end <= start) return@forEach
            when (span) {
                is StyleSpan -> addStyle(
                    SpanStyle(
                        fontWeight = if (span.style and Typeface.BOLD != 0) FontWeight.Bold else null,
                        fontStyle = if (span.style and Typeface.ITALIC != 0) FontStyle.Italic else null,
                    ),
                    start, end,
                )
                is UnderlineSpan -> addStyle(SpanStyle(textDecoration = TextDecoration.Underline), start, end)
                is StrikethroughSpan -> addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), start, end)
                is ForegroundColorSpan -> {
                    val c = Color(span.foregroundColor)
                    // A very dark colour would vanish on the dark background and on dark scenes.
                    if (c.luminance() >= 0.15f) addStyle(SpanStyle(color = c), start, end)
                }
            }
        }
    }
}
