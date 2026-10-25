package dev.cluvex.strata.feature.color

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.cluvex.strata.R
import dev.cluvex.strata.core.color.ColorCodec
import dev.cluvex.strata.core.data.SettingsRepository
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.ui.rememberTextClipboard
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlinx.coroutines.withContext

@Stable
class ColorPickerState(initial: Int) {
    var hue by mutableFloatStateOf(0f)
        internal set
    var saturation by mutableFloatStateOf(0f)
        internal set
    var value by mutableFloatStateOf(0f)
        internal set
    var alpha by mutableFloatStateOf(1f)
        internal set

    init {
        set(initial)
    }

    val argb: Int get() = ColorCodec.fromHsv(ColorCodec.Hsv(hue, saturation, value), (alpha * 255).roundToInt())

    fun set(argb: Int) {
        val hsv = ColorCodec.toHsv(argb)
        if (hsv.s > 0f && hsv.v > 0f) hue = hsv.h
        saturation = hsv.s
        value = hsv.v
        alpha = ColorCodec.alpha(argb) / 255f
    }

    companion object {
        val Saver = listSaver<ColorPickerState, Float>(
            save = { listOf(it.hue, it.saturation, it.value, it.alpha) },
            restore = { ColorPickerState(0).apply { hue = it[0]; saturation = it[1]; value = it[2]; alpha = it[3] } },
        )
    }
}

@Composable
fun rememberColorPickerState(initial: Int): ColorPickerState = rememberSaveable(saver = ColorPickerState.Saver) { ColorPickerState(initial) }

private val PALETTE = listOf(
    0xFFF44336, 0xFFE91E63, 0xFF9C27B0, 0xFF673AB7, 0xFF3F51B5, 0xFF2196F3, 0xFF03A9F4, 0xFF00BCD4,
    0xFF009688, 0xFF4CAF50, 0xFF8BC34A, 0xFFCDDC39, 0xFFFFEB3B, 0xFFFFC107, 0xFFFF9800, 0xFFFF5722,
    0xFF795548, 0xFF9E9E9E, 0xFF607D8B, 0xFF000000, 0xFFFFFFFF, 0x00000000,
).map { it.toInt() }

private val HUES = (0..6).map { Color(ColorCodec.fromHsv(ColorCodec.Hsv(it * 60f, 1f, 1f))) }

@Composable
fun ColorPickerPanel(
    state: ColorPickerState,
    initial: Int?,
    recent: List<Int>,
    modifier: Modifier = Modifier,
    onClearRecent: (() -> Unit)? = null,
) {
    val current = state.argb
    var hex by remember { mutableStateOf(ColorCodec.format(current, ColorCodec.Format.HEX_ARGB)) }
    var hexError by remember { mutableStateOf(false) }
    LaunchedEffect(current) {
        if (parseTyped(hex) != current) {
            hex = ColorCodec.format(current, ColorCodec.Format.HEX_ARGB)
            hexError = false
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SaturationValueField(state, Modifier.fillMaxWidth().aspectRatio(1.6f))
        TrackSlider(
            fraction = state.hue / 360f,
            onChange = { state.hue = it * 360f },
            label = stringResource(R.string.color_hue),
            state = stringResource(R.string.color_degrees, state.hue.roundToInt()),
            tag = "color_hue",
            thumb = Color(ColorCodec.fromHsv(ColorCodec.Hsv(state.hue, 1f, 1f))),
        ) { drawRect(Brush.horizontalGradient(HUES)) }
        val opaque = Color(current).copy(alpha = 1f)
        TrackSlider(
            fraction = state.alpha,
            onChange = { state.alpha = it },
            label = stringResource(R.string.color_opacity),
            state = stringResource(R.string.color_percent, (state.alpha * 100).roundToInt()),
            tag = "color_alpha",
            thumb = Color(current),
        ) {
            checkerboard()
            drawRect(Brush.horizontalGradient(listOf(opaque.copy(alpha = 0f), opaque)))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BeforeAfter(initial, current)
            OutlinedTextField(
                value = hex,
                onValueChange = { t ->
                    hex = t
                    val c = parseTyped(t)
                    hexError = c == null
                    if (c != null) state.set(c)
                },
                label = { Text(stringResource(R.string.color_hex)) },
                singleLine = true,
                isError = hexError,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false),
                modifier = Modifier.weight(1f).testTag("color_hex"),
            )
        }
        if (recent.isNotEmpty()) Swatches(stringResource(R.string.color_recent), recent, current, onClear = onClearRecent) { state.set(it) }
        Swatches(stringResource(R.string.color_palette), PALETTE, current) { state.set(it) }
    }
}

private fun parseTyped(text: String): Int? {
    val t = text.trim()
    return ColorCodec.parse(t) ?: if (!t.startsWith("#")) ColorCodec.parse("#$t") else null
}

@Composable
private fun SaturationValueField(state: ColorPickerState, modifier: Modifier) {
    val hueColor = Color(ColorCodec.fromHsv(ColorCodec.Hsv(state.hue, 1f, 1f)))
    val description = stringResource(R.string.color_sv)
    Canvas(
        modifier
            .pointerInput(state) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    fun update(p: Offset) {
                        state.saturation = (p.x / size.width).coerceIn(0f, 1f)
                        state.value = (1f - p.y / size.height).coerceIn(0f, 1f)
                    }
                    update(down.position)
                    down.consume()
                    drag(down.id) { change ->
                        update(change.position)
                        change.consume()
                    }
                }
            }
            .clip(RoundedCornerShape(20.dp))
            .semantics {
                contentDescription = description
                stateDescription = tr(R.string.color_sv_state, (state.saturation * 100).roundToInt(), (state.value * 100).roundToInt())
            }
            .testTag("color_sv"),
    ) {
        drawRect(Brush.horizontalGradient(listOf(Color.White, hueColor)))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))
        val c = Offset(state.saturation * size.width, (1f - state.value) * size.height)
        drawCircle(Color.Black.copy(alpha = 0.35f), radius = 12.dp.toPx(), center = c, style = Stroke(1.dp.toPx()))
        drawCircle(Color.White, radius = 10.dp.toPx(), center = c, style = Stroke(3.dp.toPx()))
    }
}

@Composable
private fun TrackSlider(
    fraction: Float,
    onChange: (Float) -> Unit,
    label: String,
    state: String,
    tag: String,
    thumb: Color,
    track: DrawScope.() -> Unit,
) {
    val change by rememberUpdatedState(onChange)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(30.dp)
            .pointerInput(Unit) {
                val r = size.height / 2f
                fun at(x: Float) = ((x - r) / (size.width - 2 * r)).coerceIn(0f, 1f)
                awaitEachGesture {
                    val down = awaitFirstDown()
                    change(at(down.position.x))
                    down.consume()
                    drag(down.id) { c ->
                        change(at(c.position.x))
                        c.consume()
                    }
                }
            }
            .semantics {
                contentDescription = label
                stateDescription = state
                progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
                setProgress { change(it.coerceIn(0f, 1f)); true }
            }
            .testTag(tag),
    ) {
        val r = size.height / 2f
        clipPath(Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(r))) }) { track() }
        val center = Offset(r + fraction.coerceIn(0f, 1f) * (size.width - 2 * r), r)
        drawCircle(Color.Black.copy(alpha = 0.35f), radius = r - 1.dp.toPx(), center = center, style = Stroke(1.dp.toPx()))
        drawCircle(Color.White, radius = r - 3.dp.toPx(), center = center, style = Stroke(3.dp.toPx()))
        drawCircle(thumb, radius = r - 5.dp.toPx(), center = center)
    }
}

private fun DrawScope.checkerboard() {
    drawRect(Color.White)
    val cell = 6.dp.toPx()
    var y = 0
    while (y * cell < size.height) {
        var x = 0
        while (x * cell < size.width) {
            if ((x + y) % 2 == 0) drawRect(Color(0xFFCCCCCC), Offset(x * cell, y * cell), Size(cell, cell))
            x++
        }
        y++
    }
}

@Composable
private fun BeforeAfter(initial: Int?, current: Int) {
    Box(Modifier.size(width = if (initial != null) 72.dp else 48.dp, height = 48.dp).clip(RoundedCornerShape(14.dp))) {
        Canvas(Modifier.fillMaxSize()) {
            checkerboard()
            if (initial != null) {
                val half = Size(size.width / 2, size.height)
                val rtl = layoutDirection == LayoutDirection.Rtl
                drawRect(Color(initial), topLeft = Offset(if (rtl) size.width / 2 else 0f, 0f), size = half)
                drawRect(Color(current), topLeft = Offset(if (rtl) 0f else size.width / 2, 0f), size = half)
            } else {
                drawRect(Color(current))
            }
        }
    }
}

@Composable
private fun Swatches(title: String, colors: List<Int>, current: Int, onClear: (() -> Unit)? = null, onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            if (onClear != null) TextButton(onClick = onClear) { Text(stringResource(R.string.common_clear)) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (c in colors) {
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                        .clickable { onPick(c) }
                        .semantics { contentDescription = ColorCodec.format(c, ColorCodec.Format.HEX_ARGB) },
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.fillMaxSize()) { checkerboard(); drawRect(Color(c)) }
                    if (c == current) Icon(Icons.Rounded.Check, contentDescription = null, tint = Color(ColorCodec.onColor(c)), modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColorPickerDialog(
    initial: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
    title: String = stringResource(R.string.color_pick_title),
) {
    val context = LocalContext.current
    val repo = remember { SettingsRepository(context.applicationContext) }
    val recent by repo.recentColors.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val state = rememberColorPickerState(initial)
    BasicAlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("color_picker")) {
        Surface(shape = AlertDialogDefaults.shape, color = AlertDialogDefaults.containerColor, tonalElevation = AlertDialogDefaults.TonalElevation) {
            Column(Modifier.padding(top = 24.dp, bottom = 12.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 24.dp))
                ColorPickerPanel(
                    state,
                    initial,
                    recent,
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
                    onClearRecent = { scope.launch { repo.setRecentColors(emptyList()) } },
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                    TextButton(onClick = {
                        val c = state.argb
                        scope.launch(start = CoroutineStart.UNDISPATCHED) { withContext(NonCancellable) { repo.pushRecentColor(c) } }
                        onPick(c)
                    }) { Text(stringResource(R.string.common_apply)) }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColorPickerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { SettingsRepository(context.applicationContext) }
    val recent by repo.recentColors.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    val clipboard = rememberTextClipboard()
    val state = rememberColorPickerState(0xFF6750A4.toInt())
    var copied by remember { mutableStateOf<ColorCodec.Format?>(null) }
    LaunchedEffect(copied) {
        if (copied != null) {
            delay(1500)
            copied = null
        }
    }
    Scaffold(
        modifier = Modifier.testTag("color_screen"),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.color_picker_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back)) }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
            ColorPickerPanel(state, initial = null, recent = recent, onClearRecent = { scope.launch { repo.setRecentColors(emptyList()) } })
            Text(
                stringResource(R.string.color_copy_as),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
            )
            for (format in ColorCodec.Format.entries) {
                val text = ColorCodec.format(state.argb, format)
                ListItem(
                    content = { Text(text, fontFamily = FontFamily.Monospace) },
                    supportingContent = { Text(format.label) },
                    trailingContent = {
                        if (copied == format) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    stringResource(R.string.common_copied),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(end = 4.dp).semantics { liveRegion = LiveRegionMode.Polite },
                                )
                                Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            }
                        } else {
                            Icon(Icons.Rounded.ContentCopy, contentDescription = null)
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .clickable {
                            clipboard.setText(text)
                            val c = state.argb
                            scope.launch { repo.pushRecentColor(c) }
                            copied = format
                        }
                        .testTag("copy_${format.name}"),
                )
                Box(Modifier.height(6.dp))
            }
        }
    }
}
