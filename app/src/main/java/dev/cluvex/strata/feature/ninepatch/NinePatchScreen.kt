package dev.cluvex.strata.feature.ninepatch

import dev.cluvex.strata.core.i18n.separator
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.ninepatch.NinePatch
import dev.cluvex.strata.core.sound.UiSound
import dev.cluvex.strata.ui.expressive.ArtTone
import dev.cluvex.strata.ui.expressive.ExpressiveDialog
import dev.cluvex.strata.ui.expressive.HeroShapes
import dev.cluvex.strata.ui.isolated
import java.io.File
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private enum class EditMode(@StringRes val label: Int, @StringRes val hint: Int) {
    STRETCH(R.string.ninepatch_mode_stretch, R.string.ninepatch_hint_stretch),
    CONTENT(R.string.ninepatch_mode_content, R.string.ninepatch_hint_content),
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NinePatchScreen(path: String, rootEnabled: Boolean, onBack: () -> Unit, vm: NinePatchViewModel = viewModel(key = "ninepatch")) {
    LaunchedEffect(path) { vm.load(path, rootEnabled) }
    val s by vm.state.collectAsStateWithLifecycle()
    var mode by rememberSaveable { mutableStateOf(EditMode.STRETCH) }
    var menu by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    BackHandler { if (s.dirty) leaving = true else onBack() }
    LaunchedEffect(s.message) {
        val m = s.message ?: return@LaunchedEffect
        snackbar.showSnackbar(m)
        vm.consumeMessage()
    }

    Scaffold(
        modifier = Modifier.testTag("ninepatch-screen"),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(isolated(File(path).name), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val p = s.patch
                        Text(
                            if (p != null) stringResource(s.form.label) + " · ${p.width}×${p.height}" else stringResource(R.string.ninepatch_editor),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = { IconButton(onClick = { if (s.dirty) leaving = true else onBack() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back)) } },
                actions = {
                    IconButton(onClick = { vm.save() }, enabled = s.dirty && !s.saving) { Icon(Icons.Rounded.Save, contentDescription = stringResource(R.string.common_save)) }
                    Box {
                        IconButton(onClick = { menu = true }, enabled = s.patch != null) { Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.common_more)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            val otherCopy = if (s.form == NinePatchForm.SOURCE) R.string.ninepatch_save_compiled_copy else R.string.ninepatch_save_source_copy
                            DropdownMenuItem(text = { Text(stringResource(otherCopy)) }, onClick = { menu = false; vm.save(asOtherForm = true) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.ninepatch_clear_stretch)) }, onClick = { menu = false; vm.clearStretch() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.ninepatch_reset_content)) }, onClick = { menu = false; vm.clearContent() })
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val p = s.patch
        when {
            s.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { LoadingIndicator() }
            p == null -> Column(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(s.error ?: stringResource(R.string.ninepatch_cant_open), color = MaterialTheme.colorScheme.error)
                if (s.raw != null) {
                    Button(onClick = { vm.startOver(keepBorder = true) }) { Text(stringResource(R.string.ninepatch_start_over)) }
                    OutlinedButton(onClick = { vm.startOver(keepBorder = false) }) { Text(stringResource(R.string.ninepatch_whole_picture)) }
                }
            }
            else -> Column(
                Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    EditMode.entries.forEachIndexed { i, m ->
                        SegmentedButton(
                            selected = mode == m,
                            onClick = { mode = m },
                            shape = SegmentedButtonDefaults.itemShape(i, EditMode.entries.size),
                        ) { Text(stringResource(m.label)) }
                    }
                }
                Text(stringResource(mode.hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val image = remember(p.pixels, p.width, p.height) {
                    Bitmap.createBitmap(p.pixels, p.width, p.height, Bitmap.Config.ARGB_8888).asImageBitmap()
                }
                Editor(
                    p, image, mode,
                    onStretch = { vertical, from, to, on -> vm.setStretch(vertical, from, to, on) },
                    onContent = { vertical, from, to -> vm.setContent(vertical, from, to) },
                )
                Text(summary(p), style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("np_summary"))
                Text(stringResource(R.string.ninepatch_preview), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Previews(p, image)
            }
        }
    }

    if (leaving) {
        ExpressiveDialog(
            onDismissRequest = { leaving = false },
            title = stringResource(R.string.ninepatch_save_changes),
            icon = Icons.Rounded.EditNote,
            tone = ArtTone.TERTIARY,
            shapes = HeroShapes.alert,
            sound = UiSound.ATTENTION,
            text = { Text(stringResource(R.string.ninepatch_unsaved_text)) },
            confirmButton = { TextButton(onClick = { leaving = false; vm.save(); onBack() }) { Text(stringResource(R.string.common_save)) } },
            dismissButton = {
                Row {
                    TextButton(onClick = { leaving = false; onBack() }) { Text(stringResource(R.string.common_discard)) }
                    TextButton(onClick = { leaving = false }) { Text(stringResource(R.string.common_cancel)) }
                }
            },
        )
    }
}

private fun summary(p: NinePatch): String {
    fun runs(d: IntArray) = d.size / 2
    val pad = p.padding
    val content = tr(if (p.explicitPadding) R.string.ninepatch_summary_content else R.string.ninepatch_summary_content_derived, pad.left, pad.top, pad.right, pad.bottom)
    val bounds = p.layoutBounds?.let { separator() + tr(R.string.ninepatch_summary_bounds, it.left, it.top, it.right, it.bottom) } ?: ""
    return tr(R.string.ninepatch_summary_stretch, runs(p.xDivs), runs(p.yDivs)) + separator() + content + bounds
}

private fun IntArray.covers(i: Int): Boolean = (indices step 2).any { i >= this[it] && i < this[it + 1] }

@Composable
private fun Editor(
    p: NinePatch,
    image: ImageBitmap,
    mode: EditMode,
    onStretch: (vertical: Boolean, from: Int, to: Int, on: Boolean) -> Unit,
    onContent: (vertical: Boolean, from: Int, to: Int) -> Unit,
) {
    val strip = 26.dp
    val gap = 4.dp
    val density = LocalDensity.current
    val stretchColumns = stringResource(R.string.ninepatch_bar_stretch_columns)
    val stretchRows = stringResource(R.string.ninepatch_bar_stretch_rows)
    val contentRows = stringResource(R.string.ninepatch_bar_content_rows)
    val contentColumns = stringResource(R.string.ninepatch_bar_content_columns)
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val fit = with(density) { (maxWidth - (strip + gap) * 2).toPx() } / p.width
            val cellPx = minOf(fit, with(density) { 40.dp.toPx() }).let { if (it >= 1f) floor(it) else it }
            val w = with(density) { (cellPx * p.width).toDp() }
            val h = with(density) { (cellPx * p.height).toDp() }
            val colors = MaterialTheme.colorScheme
            val stretchOn = mode == EditMode.STRETCH
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row {
                    Spacer(Modifier.width(strip + gap))
                    Bar(p.width, w, strip, vertical = false, enabled = stretchOn, tag = "np_top", label = stretchColumns,
                        mark = { i -> if (p.xDivs.covers(i)) colors.onSurface else null },
                        onDown = { i -> !p.xDivs.covers(i) },
                        onPaint = { from, to, on -> onStretch(false, from, to, on) })
                }
                Spacer(Modifier.size(gap))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Bar(p.height, h, strip, vertical = true, enabled = stretchOn, tag = "np_left", label = stretchRows,
                        mark = { i -> if (p.yDivs.covers(i)) colors.onSurface else null },
                        onDown = { i -> !p.yDivs.covers(i) },
                        onPaint = { from, to, on -> onStretch(true, from, to, on) })
                    Spacer(Modifier.width(gap))
                    Picture(p, image, w, h)
                    Spacer(Modifier.width(gap))
                    Bar(p.height, h, strip, vertical = true, enabled = !stretchOn, tag = "np_right", label = contentRows,
                        mark = { i -> contentMark(p, i, vertical = true, colors.tertiary, colors.error) },
                        onDown = { true }, anchored = true,
                        onPaint = { from, to, _ -> onContent(true, from, to) })
                }
                Spacer(Modifier.size(gap))
                Row {
                    Spacer(Modifier.width(strip + gap))
                    Bar(p.width, w, strip, vertical = false, enabled = !stretchOn, tag = "np_bottom", label = contentColumns,
                        mark = { i -> contentMark(p, i, vertical = false, colors.tertiary, colors.error) },
                        onDown = { true }, anchored = true,
                        onPaint = { from, to, _ -> onContent(false, from, to) })
                }
            }
        }
    }
}

private fun contentMark(p: NinePatch, i: Int, vertical: Boolean, content: Color, bounds: Color): Color? {
    val pad = p.padding
    val n = if (vertical) p.height else p.width
    val inside = if (vertical) i >= pad.top && i < n - pad.bottom else i >= pad.left && i < n - pad.right
    if (inside) return if (p.explicitPadding) content else content.copy(alpha = 0.45f)
    val lb = p.layoutBounds ?: return null
    val inBounds = if (vertical) i < lb.top || i >= n - lb.bottom else i < lb.left || i >= n - lb.right
    return if (inBounds) bounds else null
}

@Composable
private fun Bar(
    count: Int,
    length: Dp,
    thickness: Dp,
    vertical: Boolean,
    enabled: Boolean,
    tag: String,
    label: String,
    mark: (Int) -> Color?,
    onDown: (Int) -> Boolean,
    onPaint: (from: Int, to: Int, on: Boolean) -> Unit,
    anchored: Boolean = false,
) {
    val paint by rememberUpdatedState(onPaint)
    val down by rememberUpdatedState(onDown)
    val track = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = if (enabled) 1f else 0.5f)
    Canvas(
        Modifier
            .size(if (vertical) thickness else length, if (vertical) length else thickness)
            .clip(RoundedCornerShape(6.dp))
            .background(track)
            .then(
                if (!enabled) Modifier else Modifier.pointerInput(count, vertical, anchored) {
                    fun index(o: Offset) = ((if (vertical) o.y / size.height else o.x / size.width) * count).toInt().coerceIn(0, count - 1)
                    awaitEachGesture {
                        val first = awaitFirstDown()
                        val start = index(first.position)
                        val on = down(start)
                        var last = start
                        paint(start, start, on)
                        first.consume()
                        drag(first.id) { c ->
                            val i = index(c.position)
                            if (i != last) {
                                paint(if (anchored) start else last, i, on)
                                last = i
                            }
                            c.consume()
                        }
                    }
                },
            )
            .semantics { contentDescription = label }
            .testTag(tag),
    ) {
        val cell = (if (vertical) size.height else size.width) / count
        val inset = size.minDimension * 0.22f
        for (i in 0 until count) {
            val c = mark(i) ?: continue
            if (vertical) drawRect(c, Offset(inset, i * cell), Size(size.width - 2 * inset, cell + 0.5f))
            else drawRect(c, Offset(i * cell, inset), Size(cell + 0.5f, size.height - 2 * inset))
        }
    }
}

@Composable
private fun Picture(p: NinePatch, image: ImageBitmap, w: Dp, h: Dp) {
    val colors = MaterialTheme.colorScheme
    Canvas(Modifier.size(w, h).testTag("np_picture")) {
        checker()
        drawImage(image, dstSize = IntSize(ceil(size.width).toInt(), ceil(size.height).toInt()), filterQuality = FilterQuality.None)
        val cx = size.width / p.width
        val cy = size.height / p.height
        val band = colors.primary.copy(alpha = 0.22f)
        for (i in p.xDivs.indices step 2) drawRect(band, Offset(p.xDivs[i] * cx, 0f), Size((p.xDivs[i + 1] - p.xDivs[i]) * cx, size.height))
        for (i in p.yDivs.indices step 2) drawRect(band, Offset(0f, p.yDivs[i] * cy), Size(size.width, (p.yDivs[i + 1] - p.yDivs[i]) * cy))
        val pad = p.padding
        val box = Offset(pad.left * cx, pad.top * cy)
        val boxSize = Size((p.width - pad.left - pad.right) * cx, (p.height - pad.top - pad.bottom) * cy)
        if (boxSize.width > 0 && boxSize.height > 0) {
            drawRect(colors.tertiary.copy(alpha = 0.12f), box, boxSize)
            drawRect(colors.tertiary, box, boxSize, style = Stroke(2.dp.toPx()))
        }
    }
}

@Composable
private fun Previews(p: NinePatch, image: ImageBitmap) {
    val density = LocalDensity.current
    val scale = with(density) { (88.dp.toPx() / maxOf(p.width, p.height)).coerceIn(0.25f, 8f) }
    val content = MaterialTheme.colorScheme.tertiary
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        for ((sx, sy) in listOf(2.6f to 1f, 1f to 2.2f, 1.8f to 1.8f)) {
            val tw = p.width * scale * sx
            val th = p.height * scale * sy
            Canvas(Modifier.size(with(density) { tw.toDp() }, with(density) { th.toDp() }).testTag("np_preview")) {
                for (piece in p.layout(size.width / scale, size.height / scale)) {
                    val dw = ((piece.right - piece.left) * scale).roundToInt()
                    val dh = ((piece.bottom - piece.top) * scale).roundToInt()
                    if (dw <= 0 || dh <= 0) continue
                    drawImage(
                        image,
                        srcOffset = IntOffset(piece.srcLeft, piece.srcTop),
                        srcSize = IntSize(piece.srcRight - piece.srcLeft, piece.srcBottom - piece.srcTop),
                        dstOffset = IntOffset((piece.left * scale).roundToInt(), (piece.top * scale).roundToInt()),
                        dstSize = IntSize(dw, dh),
                        filterQuality = FilterQuality.Low,
                    )
                }
                val pad = p.padding
                val box = Offset(pad.left * scale, pad.top * scale)
                val boxSize = Size(size.width - (pad.left + pad.right) * scale, size.height - (pad.top + pad.bottom) * scale)
                if (boxSize.width > 0 && boxSize.height > 0) drawRect(content.copy(alpha = 0.18f), box, boxSize)
            }
        }
    }
}

private fun DrawScope.checker() {
    val cell = 8.dp.toPx()
    drawRect(Color(0xFFEEEEEE))
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
