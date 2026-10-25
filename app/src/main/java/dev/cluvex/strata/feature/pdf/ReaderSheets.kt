package dev.cluvex.strata.feature.pdf

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.cluvex.strata.R
import dev.cluvex.strata.core.model.ReaderTheme
import dev.cluvex.strata.core.pdf.view.OutlineItem
import dev.cluvex.strata.core.pdf.view.PdfMeta
import dev.cluvex.strata.core.pdf.view.SearchHit
import dev.cluvex.strata.core.pdf.view.Snippet
import dev.cluvex.strata.ui.asPath
import dev.cluvex.strata.ui.rememberSheetState
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun PasswordDialog(wrong: Boolean, onOpen: (String) -> Unit, onCancel: () -> Unit) {
    var password by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Icon(Icons.Rounded.Lock, contentDescription = null) },
        title = { Text(stringResource(R.string.pdf_password_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.pdf_password_message))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    singleLine = true,
                    isError = wrong,
                    supportingText = if (wrong) ({ Text(stringResource(R.string.pdf_wrong_password)) }) else null,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (password.isNotEmpty()) onOpen(password) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("pdf_password"),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onOpen(password) }, enabled = password.isNotEmpty(), modifier = Modifier.testTag("pdf_unlock")) { Text(stringResource(R.string.common_open)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) } },
    )
}

object PageInput {
    fun parse(text: String, count: Int, labels: List<String?>): Int? {
        val raw = text.trim()
        if (raw.isEmpty()) return null
        val digits = buildString {
            for (c in raw) {
                append(
                    when (c) {
                        in '۰'..'۹' -> '0' + (c - '۰')
                        in '٠'..'٩' -> '0' + (c - '٠')
                        else -> c
                    },
                )
            }
        }
        val byLabel = labels.indexOfFirst { it != null && it.equals(digits, ignoreCase = true) }
        if (byLabel >= 0 && digits.toIntOrNull() == null) return byLabel
        val n = digits.toIntOrNull() ?: return byLabel.takeIf { it >= 0 }
        return (n - 1).takeIf { it in 0 until count }
    }
}

@Composable
fun GoToPageDialog(count: Int, labels: List<String?>, current: Int, onGo: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val target = PageInput.parse(text, count, labels)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pdf_go_to_page)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(12) },
                singleLine = true,
                label = { Text(stringResource(R.string.pdf_page_range, 1, count)) },
                placeholder = { Text(stringResource(R.string.pdf_number, current + 1)) },
                isError = text.isNotBlank() && target == null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { target?.let(onGo) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("pdf_goto_field"),
            )
        },
        confirmButton = { TextButton(onClick = { target?.let(onGo) }, enabled = target != null, modifier = Modifier.testTag("pdf_goto")) { Text(stringResource(R.string.pdf_go)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
fun LinkDialog(uri: String, onOpen: () -> Unit, onCopy: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pdf_open_link_title)) },
        text = { Text(uri, style = MaterialTheme.typography.bodyMedium.asPath(), maxLines = 6, overflow = TextOverflow.Ellipsis) },
        confirmButton = { TextButton(onClick = onOpen, modifier = Modifier.testTag("pdf_link_open")) { Text(stringResource(R.string.common_open)) } },
        dismissButton = {
            Row {
                TextButton(onClick = onCopy) { Text(stringResource(R.string.common_copy)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
            }
        },
    )
}

private fun pdfDate(raw: String, locale: Locale): String {
    val m = Regex("^D?:?(\\d{4})(\\d{2})?(\\d{2})?(\\d{2})?(\\d{2})?").find(raw.trim()) ?: return raw
    val (y, mo, d, h, mi) = m.destructured
    fun part(value: String, width: Int): String = String.format(locale, "%0${width}d", value.toInt())
    return buildString {
        append(part(y, 4))
        if (mo.isNotEmpty()) append('-').append(part(mo, 2))
        if (d.isNotEmpty()) append('-').append(part(d, 2))
        if (h.isNotEmpty()) append(' ').append(part(h, 2)).append(':').append(part(mi.ifEmpty { "00" }, 2))
    }
}

@Composable
fun InfoDialog(title: String, path: String?, meta: PdfMeta, pageCount: Int, width: Float, height: Float, onDismiss: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val yes = stringResource(R.string.common_yes)
    val no = stringResource(R.string.common_no)
    val rows = buildList {
        add(stringResource(R.string.common_file) to title)
        if (path != null) add(stringResource(R.string.pdf_info_location) to path)
        if (meta.title.isNotBlank()) add(stringResource(R.string.pdf_info_title) to meta.title)
        if (meta.author.isNotBlank()) add(stringResource(R.string.pdf_info_author) to meta.author)
        if (meta.subject.isNotBlank()) add(stringResource(R.string.pdf_info_subject) to meta.subject)
        if (meta.keywords.isNotBlank()) add(stringResource(R.string.pdf_info_keywords) to meta.keywords)
        add(stringResource(R.string.pdf_info_pages) to stringResource(R.string.pdf_number, pageCount))
        add(stringResource(R.string.pdf_info_page_size) to stringResource(R.string.pdf_page_size_mm, width / 72f * 25.4f, height / 72f * 25.4f))
        if (meta.version.isNotBlank()) add(stringResource(R.string.pdf_info_version) to meta.version)
        if (meta.creator.isNotBlank()) add(stringResource(R.string.pdf_info_made_with) to meta.creator)
        if (meta.producer.isNotBlank()) add(stringResource(R.string.pdf_info_producer) to meta.producer)
        if (meta.created.isNotBlank()) add(stringResource(R.string.pdf_info_created) to pdfDate(meta.created, locale))
        if (meta.modified.isNotBlank()) add(stringResource(R.string.pdf_info_modified) to pdfDate(meta.modified, locale))
        add(stringResource(R.string.pdf_info_encrypted) to if (meta.encrypted) stringResource(R.string.pdf_info_encrypted_yes, meta.securityRevision) else no)
        add(stringResource(R.string.pdf_info_tagged) to if (meta.tagged) yes else no)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.pdf_document_info)) },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.testTag("pdf_info")) {
                itemsIndexed(rows) { _, (k, v) ->
                    Column {
                        Text(k, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(v, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } },
    )
}

@Composable
fun PageThumb(images: PageImages, version: Int, page: Int, aspect: Float, widthPx: Int, theme: ReaderTheme, selected: Boolean, modifier: Modifier = Modifier) {
    val outline = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val paper = ReaderViewportState.paper(theme)
    val filter = ReaderViewportState.filter(theme)
    Canvas(
        modifier
            .aspectRatio(aspect.coerceIn(0.2f, 5f))
            .border(if (selected) 2.dp else 1.dp, outline, RoundedCornerShape(6.dp))
            .padding(if (selected) 2.dp else 1.dp),
    ) {
        if (version < 0) return@Canvas
        drawRect(paper)
        val image = images.get(ThumbKey(page, widthPx)) ?: images.bestWhole(page)
        if (image != null) {
            drawImage(image, dstOffset = IntOffset.Zero, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Medium, colorFilter = filter)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContentsSheet(
    doc: ReaderDoc,
    images: PageImages,
    current: Int,
    marks: List<Int>,
    theme: ReaderTheme,
    onGo: (page: Int, y: Float?, item: OutlineItem?) -> Unit,
    onToggleMark: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberSheetState()
    var tab by remember { mutableIntStateOf(if (doc.outline.isEmpty()) 1 else 0) }
    val version by images.version.collectAsState()
    DisposableEffect(Unit) { onDispose { images.wantThumbs(emptyList()) } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f)) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.pdf_tab_contents)) }, modifier = Modifier.testTag("tab_contents"))
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.pdf_tab_pages)) }, modifier = Modifier.testTag("tab_pages"))
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text(stringResource(R.string.common_bookmarks)) }, modifier = Modifier.testTag("tab_bookmarks"))
            }
            when (tab) {
                0 -> OutlineList(doc, current, onGo)
                1 -> PageGrid(doc, images, version, current, theme, pages = (0 until doc.pageCount).toList(), onGo = { onGo(it, null, null) })
                else -> Column {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (marks.isEmpty()) stringResource(R.string.pdf_no_bookmarks) else pluralStringResource(R.plurals.pdf_bookmarked_pages, marks.size, marks.size),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        val marked = current in marks
                        FilledTonalButton(onClick = { onToggleMark(current) }, modifier = Modifier.testTag("pdf_mark_current")) {
                            Icon(if (marked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(if (marked) R.string.pdf_unmark_page else R.string.pdf_mark_page, doc.label(current)))
                        }
                    }
                    PageGrid(doc, images, version, current, theme, pages = marks, onGo = { onGo(it, null, null) })
                }
            }
        }
    }
}

@Composable
private fun OutlineList(doc: ReaderDoc, current: Int, onGo: (Int, Float?, OutlineItem?) -> Unit) {
    if (doc.outline.isEmpty()) {
        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.pdf_no_outline), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val items = doc.outline
    val parents = remember(items) { items.indices.map { i -> items.getOrNull(i + 1)?.depth?.let { it > items[i].depth } == true } }
    val closed = remember(items) { mutableStateMapOf<Int, Boolean>().apply { items.indices.forEach { i -> if (parents[i] && !items[i].open && items[i].depth > 0) put(i, true) } } }
    val visible = remember(items, closed.toMap()) {
        val out = ArrayList<Int>()
        var hideBelow = Int.MAX_VALUE
        for (i in items.indices) {
            if (items[i].depth > hideBelow) continue
            hideBelow = Int.MAX_VALUE
            out += i
            if (closed[i] == true) hideBelow = items[i].depth
        }
        out
    }
    val active = remember(items, current) { items.indices.lastOrNull { (items[it].target?.page ?: -1) in 0..current } }
    val list = rememberLazyListState(initialFirstVisibleItemIndex = (visible.indexOf(active ?: 0) - 3).coerceAtLeast(0))
    val untitled = stringResource(R.string.pdf_untitled)
    val expand = stringResource(R.string.pdf_expand)
    val collapse = stringResource(R.string.pdf_collapse)
    LazyColumn(state = list, modifier = Modifier.fillMaxWidth().testTag("pdf_outline")) {
        itemsIndexed(visible, key = { _, i -> i }) { _, i ->
            val item = items[i]
            val isActive = i == active
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (isActive) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .clickable(enabled = item.target != null) { item.target?.let { onGo(it.page, it.y, item) } }
                    .padding(start = (12 + 18 * item.depth.coerceAtMost(6)).dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (parents[i]) {
                    IconButton(onClick = { closed[i] = !(closed[i] ?: false) }, modifier = Modifier.size(36.dp)) {
                        Icon(if (closed[i] == true) Icons.AutoMirrored.Rounded.KeyboardArrowRight else Icons.Rounded.ExpandMore, contentDescription = if (closed[i] == true) expand else collapse)
                    }
                } else {
                    Spacer(Modifier.width(36.dp))
                }
                Text(
                    item.title.ifBlank { untitled },
                    style = if (item.depth == 0) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isActive) FontWeight.Bold else null,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                )
                item.target?.let { Text(doc.label(it.page), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun PageGrid(doc: ReaderDoc, images: PageImages, version: Int, current: Int, theme: ReaderTheme, pages: List<Int>, onGo: (Int) -> Unit) {
    val grid = rememberLazyGridState(initialFirstVisibleItemIndex = pages.indexOf(current).coerceAtLeast(0))
    val density = LocalDensity.current
    val thumbWidth = with(density) { 112.dp.roundToPx() }
    LaunchedEffect(grid, pages) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.map { it.index } }.collect { visible ->
            val wanted = (visible + listOfNotNull(visible.maxOrNull()?.plus(1), visible.maxOrNull()?.plus(2)))
                .mapNotNull { pages.getOrNull(it) }
                .map { ThumbKey(it, thumbWidth) }
            images.wantThumbs(wanted)
        }
    }
    LazyVerticalGrid(
        state = grid,
        columns = GridCells.Adaptive(104.dp),
        modifier = Modifier.fillMaxWidth().testTag("pdf_pages"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        itemsIndexed(pages, key = { _, p -> p }) { _, p ->
            Column(
                Modifier.clickable { onGo(p) }.testTag("pdf_thumb_$p"),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PageThumb(images, version, p, doc.sizes[p * 2] / doc.sizes[p * 2 + 1], thumbWidth, theme, selected = p == current, modifier = Modifier.fillMaxWidth())
                Text(doc.label(p), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchResultsSheet(
    search: SearchState,
    doc: ReaderDoc,
    snippet: suspend (SearchHit) -> Snippet,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberSheetState(skipPartiallyExpanded = false)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                when {
                    search.hits.isEmpty() && search.running -> stringResource(R.string.pdf_searching)
                    search.hits.isEmpty() -> stringResource(R.string.pdf_no_results)
                    search.running -> pluralStringResource(R.plurals.pdf_results_so_far, search.hits.size, search.hits.size)
                    else -> pluralStringResource(R.plurals.pdf_results, search.hits.size, search.hits.size)
                },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            HorizontalDivider()
            val list = rememberLazyListState(initialFirstVisibleItemIndex = (search.current - 2).coerceAtLeast(0))
            LazyColumn(state = list, modifier = Modifier.fillMaxWidth().height(480.dp).testTag("pdf_results")) {
                itemsIndexed(search.hits) { index, hit ->
                    val s by produceState<Snippet?>(null, hit) { value = snippet(hit) }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(if (index == search.current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                            .clickable { onSelect(index) }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            stringResource(R.string.pdf_result_page, doc.label(hit.page)),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.width(64.dp),
                        )
                        val text = s
                        Text(
                            if (text == null) buildAnnotatedString { append("…") } else buildAnnotatedString {
                                append(text.text.substring(0, text.hitStart))
                                withStyle(SpanStyle(fontWeight = FontWeight.Bold, background = MaterialTheme.colorScheme.tertiaryContainer)) {
                                    append(text.text.substring(text.hitStart, text.hitEnd))
                                }
                                append(text.text.substring(text.hitEnd))
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}
