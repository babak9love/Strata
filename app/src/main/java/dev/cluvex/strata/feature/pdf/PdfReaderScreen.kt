package dev.cluvex.strata.feature.pdf

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pages
import androidx.compose.material.icons.rounded.Print
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.cluvex.strata.R
import dev.cluvex.strata.core.data.SettingsRepository
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.model.ReaderTheme
import dev.cluvex.strata.core.pdf.view.Box as PdfBox
import dev.cluvex.strata.core.pdf.view.DocLayout
import dev.cluvex.strata.core.pdf.view.PageText
import dev.cluvex.strata.core.pdf.view.PdfLink
import dev.cluvex.strata.core.pdf.view.PdfSearch
import dev.cluvex.strata.core.pdf.view.ReadingSpot
import dev.cluvex.strata.core.pdf.view.SearchOptions
import dev.cluvex.strata.core.pdf.view.Viewport
import dev.cluvex.strata.ui.LocalSettings
import dev.cluvex.strata.ui.rememberTextClipboard
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class TextSelection(val page: Int, val anchor: Int, val focus: Int) {
    val start: Int get() = min(anchor, focus)
    val end: Int get() = max(anchor, focus) + 1
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PdfReaderScreen(source: ReaderSource, rootEnabled: Boolean, onBack: () -> Unit, onOpenWith: (() -> Unit)?) {
    val vm: PdfReaderViewModel = viewModel()
    LaunchedEffect(source) { vm.open(source, rootEnabled) }
    val phase by vm.phase.collectAsStateWithLifecycle()
    val title by vm.title.collectAsStateWithLifecycle()
    BackHandler(onBack = onBack)
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainer).testTag("pdf_reader")) {
        when (val p = phase) {
            ReaderPhase.Loading -> LoadingIndicator(Modifier.align(Alignment.Center))
            is ReaderPhase.Password -> {
                ReaderMessage(title, stringResource(R.string.pdf_locked), onBack, onOpenWith)
                PasswordDialog(p.wrong, onOpen = { vm.retryWithPassword(it, rootEnabled) }, onCancel = onBack)
            }
            is ReaderPhase.Failed -> ReaderMessage(title, p.message, onBack, onOpenWith)
            ReaderPhase.Ready -> {
                val doc by vm.doc.collectAsStateWithLifecycle()
                val images = vm.images
                val d = doc
                if (d != null && images != null) ReaderContent(vm, d, images, title, source, onBack, onOpenWith)
            }
        }
    }
}

@Composable
private fun ReaderMessage(title: String, message: String, onBack: () -> Unit, onOpenWith: (() -> Unit)?) {
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back)) }
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Rounded.Description, contentDescription = null, modifier = Modifier.size(56.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Text(message, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.testTag("pdf_message"))
            if (onOpenWith != null) {
                Spacer(Modifier.height(16.dp))
                Button(onClick = onOpenWith) { Text(stringResource(R.string.pdf_open_with_other_app)) }
            }
        }
    }
}

@OptIn(FlowPreview::class, ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
private fun ReaderContent(
    vm: PdfReaderViewModel,
    doc: ReaderDoc,
    images: PageImages,
    title: String,
    source: ReaderSource,
    onBack: () -> Unit,
    onOpenWith: (() -> Unit)?,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val view = LocalView.current
    val clipboard = rememberTextClipboard()
    val scope = rememberCoroutineScope()
    val settings = LocalSettings.current
    val prefs = settings.reader
    val repo = remember { SettingsRepository(context.applicationContext) }
    val state = remember(doc) { ReaderViewportState(doc.sizes) }
    val decay = remember(density) { splineBasedDecay<Offset>(density) }
    val version by images.version.collectAsState()
    val search by vm.search.collectAsStateWithLifecycle()
    val marks by vm.marks.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var chrome by remember { mutableStateOf(true) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var link by remember { mutableStateOf<String?>(null) }
    var selection by remember { mutableStateOf<TextSelection?>(null) }
    var flash by remember { mutableStateOf<Pair<Int, PdfBox>?>(null) }
    var back by remember { mutableStateOf<Viewport?>(null) }
    var motionJob by remember { mutableStateOf<Job?>(null) }
    val texts = remember { mutableStateMapOf<Int, PageText>() }
    val gap = with(density) { 8.dp.toPx() }
    val topInset = WindowInsets.statusBarsIgnoringVisibility.getTop(density) + with(density) { 64.dp.toPx() }
    val bottomInset = WindowInsets.navigationBarsIgnoringVisibility.getBottom(density) + with(density) { 64.dp.toPx() }
    LaunchedEffect(topInset, bottomInset) { state.resize(state.size.width, state.size.height, gap, gap, topInset, bottomInset) }
    val current by remember { derivedStateOf { state.currentPage() } }

    LaunchedEffect(doc) { doc.spot?.let { state.restore(it.page, it.fraction, it.zoom) } }

    fun move(target: Viewport?) {
        target ?: return
        motionJob?.cancel()
        motionJob = scope.launch { state.animateTo(target) }
    }

    fun goTo(page: Int, y: Float? = null, remember: Boolean = true) {
        if (remember) back = state.viewport
        move(state.target(page, y))
    }

    LaunchedEffect(state, images) {
        snapshotFlow { Triple(state.viewport, state.size, state.settledZoom) }.collect {
            val tiles = state.settledZoom == state.viewport.zoom
            images.wantView(state.wanted(images, includeTiles = tiles))
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.position() }.distinctUntilChanged().debounce(700).collect { p ->
            if (p != null) vm.saveSpot(ReadingSpot(p.first, p.second, p.third))
        }
    }
    LaunchedEffect(search.hits, state) {
        snapshotFlow { state.visiblePages() }.distinctUntilChanged().collect { range ->
            for (p in range) if (texts[p] == null && (search.hits.any { it.page == p } || selection?.page == p)) texts[p] = vm.text(p)
        }
    }
    val hitBoxes by remember(search.hits) {
        derivedStateOf {
            val out = HashMap<Int, List<PdfBox>>()
            for (p in state.visiblePages()) {
                val t = texts[p] ?: continue
                val boxes = search.hits.filter { it.page == p }.flatMap { t.rects(it.start, it.end) }
                if (boxes.isNotEmpty()) out[p] = boxes
            }
            out
        }
    }
    val currentHit = search.hits.getOrNull(search.current)
    LaunchedEffect(currentHit) {
        val hit = currentHit ?: return@LaunchedEffect
        val t = texts[hit.page] ?: vm.text(hit.page).also { texts[hit.page] = it }
        val rects = t.rects(hit.start, hit.end)
        val first = rects.firstOrNull()
        val l = state.layout
        if (first != null && l != null) {
            move(l.reveal(state.viewport, state.size.width.toFloat(), state.size.height.toFloat(), hit.page, first))
        } else {
            goTo(hit.page, remember = false)
        }
    }
    val currentBoxes = currentHit?.let { h -> texts[h.page]?.let { h.page to it.rects(h.start, h.end) } }
    val selectionBoxes = selection?.let { s -> texts[s.page]?.let { s.page to it.rects(s.start, s.end) } }

    DisposableEffect(prefs.keepScreenOn) {
        view.keepScreenOn = prefs.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }
    val immersive = !chrome && !searching
    DisposableEffect(immersive) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (immersive) {
            controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }

    fun wordAt(t: PageText, index: Int): Pair<Int, Int> {
        fun part(c: Char) = Character.isLetterOrDigit(c) || Character.getType(c) == Character.NON_SPACING_MARK.toInt() || c == '‌'
        var s = index
        var e = index
        if (!part(t.text[index])) return index to index
        while (s > 0 && part(t.text[s - 1])) s--
        while (e + 1 < t.length && part(t.text[e + 1])) e++
        return s to e
    }

    suspend fun indexAt(position: Offset, page: Int? = null): Pair<Int, Int>? {
        val pt = state.toPage(position) ?: return null
        if (page != null && pt.page != page) return null
        val t = texts[pt.page] ?: vm.text(pt.page).also { texts[pt.page] = it }
        val slop = 18f / state.viewport.zoom / (state.layout?.scale ?: 1f)
        val i = t.indexAt(pt.x, pt.y, slop) ?: return null
        return pt.page to i
    }

    val handles by remember {
        derivedStateOf {
            val s = selection ?: return@derivedStateOf null
            val t = texts[s.page] ?: return@derivedStateOf null
            if (s.start >= t.length) return@derivedStateOf null
            val a = t.box(s.start)
            val b = t.box((s.end - 1).coerceIn(0, t.length - 1))
            val rtlA = t.lineRtl.getOrElse(t.line(s.start)) { false }
            val rtlB = t.lineRtl.getOrElse(t.line(s.end - 1)) { false }
            state.toScreen(s.page, if (rtlA) a.right else a.left, a.bottom) to state.toScreen(s.page, if (rtlB) b.left else b.right, b.bottom)
        }
    }
    val handleRadius = with(density) { 26.dp.toPx() }

    val callbacks = remember(state) {
        object : ReaderGestures {
            override fun onGestureStart() {
                motionJob?.cancel()
            }

            override fun onTap(position: Offset) {
                if (selection != null) {
                    selection = null
                    return
                }
                scope.launch {
                    val pt = state.toPage(position)
                    val hit: PdfLink? = pt?.let { p -> vm.links(p.page).firstOrNull { it.bounds.contains(p.x, p.y) } }
                    if (pt == null || hit == null) {
                        chrome = !chrome
                        return@launch
                    }
                    flash = pt.page to hit.bounds
                    val target = hit.target
                    if (target != null) {
                        val shown = vm.displayTarget(target)
                        goTo(shown.page, shown.y)
                    } else {
                        hit.uri?.let { link = it }
                    }
                    delay(300)
                    flash = null
                }
            }

            override fun onDoubleTap(position: Offset) {
                val l = state.layout ?: return
                val v = state.viewport
                val target = if (v.zoom < 1.6f) 2.5f else 1f
                move(l.zoomAround(v, target, position.x, position.y))
            }

            override fun onLongPress(position: Offset) {
                scope.launch {
                    val (page, i) = indexAt(position) ?: return@launch
                    val t = texts[page] ?: return@launch
                    val (s, e) = wordAt(t, i)
                    selection = TextSelection(page, s, e)
                    chrome = true
                }
            }

            override fun onSelectionDrag(position: Offset) {
                val s = selection ?: return
                scope.launch {
                    val (_, i) = indexAt(position, s.page) ?: return@launch
                    selection = s.copy(focus = i)
                }
            }

            override fun onSelectionEnd() = Unit

            override fun handleAt(position: Offset): Int {
                val h = handles ?: return 0
                if ((h.first - position).getDistance() < handleRadius) return 1
                if ((h.second - position).getDistance() < handleRadius) return 2
                return 0
            }

            override fun onHandleDrag(handle: Int, position: Offset) {
                val s = selection ?: return
                scope.launch {
                    val (_, i) = indexAt(position - Offset(0f, handleRadius * 0.6f), s.page) ?: return@launch
                    selection = if (handle == 1) TextSelection(s.page, i, s.end - 1) else TextSelection(s.page, s.start, i)
                }
            }

            override fun onGestureEnd(velocity: Offset, flung: Boolean) {
                state.settle()
                if (flung && velocity.getDistance() > 50f) {
                    motionJob = scope.launch { state.fling(velocity, decay) }
                }
                val l = state.layout ?: return
                val v = state.viewport
                if (v.zoom < 1f && v.zoom > DocLayout.MIN_ZOOM) {
                    val snapped = if (v.zoom > 0.85f) 1f else v.zoom
                    if (snapped != v.zoom) move(l.zoomAround(v, snapped, state.size.width / 2f, state.size.height / 2f))
                }
            }
        }
    }

    val background = if (prefs.theme == ReaderTheme.NIGHT) Color(0xFF0E0E0E) else MaterialTheme.colorScheme.surfaceContainerHigh
    val shadow = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    val hitColor = Color(0x66FFD54F)
    val currentColor = Color(0x99FF8F00)
    val selectionColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.32f)
    val handleColor = MaterialTheme.colorScheme.primary
    val pageOf = stringResource(R.string.pdf_page_of, doc.label(current), doc.pageCount)

    Box(Modifier.fillMaxSize().background(background)) {
        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { state.resize(it.width, it.height, gap, gap, topInset, bottomInset) }
                .readerGestures(state, callbacks)
                .semantics {
                    contentDescription = pageOf
                    stateDescription = tr(R.string.pdf_zoom_state, state.viewport.zoom)
                }
                .testTag("pdf_pages_view")
                .drawBehind {
                    if (version < 0) return@drawBehind
                    drawPages(
                        state, images, prefs.theme, shadow,
                        ReaderHighlights(hitBoxes, currentBoxes, selectionBoxes, flash),
                        hitColor, currentColor, selectionColor,
                    )
                    handles?.let { (a, b) ->
                        drawCircle(handleColor, handleRadius * 0.38f, a + Offset(0f, handleRadius * 0.38f))
                        drawCircle(handleColor, handleRadius * 0.38f, b + Offset(0f, handleRadius * 0.38f))
                    }
                },
        )

        val sel = selection
        val toolbarAnchor = selectionBoxes?.second?.firstOrNull()?.let { state.boxOnScreen(sel?.page ?: 0, it).first }
        if (sel != null && toolbarAnchor != null) {
            val selected = texts[sel.page]?.let { t -> t.text.substring(sel.start.coerceIn(0, t.length), sel.end.coerceIn(0, t.length)) }.orEmpty()
            val toolbarY = with(density) { (toolbarAnchor.y - 64.dp.toPx()).coerceAtLeast(72.dp.toPx()) }
            Surface(
                shape = RoundedCornerShape(24.dp),
                tonalElevation = 6.dp,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .align(AbsoluteAlignment.TopLeft)
                    .absoluteOffset { IntOffset((toolbarAnchor.x - with(density) { 100.dp.toPx() }).roundToInt().coerceIn(8, (state.size.width - with(density) { 220.dp.toPx() }).roundToInt().coerceAtLeast(8)), toolbarY.roundToInt()) }
                    .testTag("pdf_selection_bar"),
            ) {
                Row(Modifier.padding(horizontal = 4.dp)) {
                    IconButton(onClick = {
                        clipboard.setText(selected)
                        selection = null
                        scope.launch { snackbar.showSnackbar(tr(R.string.common_copied)) }
                    }, modifier = Modifier.testTag("pdf_copy")) { Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.common_copy)) }
                    IconButton(onClick = { ReaderActions.shareText(context, selected) }) { Icon(Icons.Rounded.Share, contentDescription = stringResource(R.string.common_share)) }
                    IconButton(onClick = {
                        query = selected.trim().take(80)
                        searching = true
                        selection = null
                        vm.search(query, search.options, current)
                    }) { Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.pdf_search_in_document)) }
                    IconButton(onClick = {
                        val t = texts[sel.page] ?: return@IconButton
                        if (t.length > 0) selection = TextSelection(sel.page, 0, t.length - 1)
                    }) { Icon(Icons.Rounded.SelectAll, contentDescription = stringResource(R.string.pdf_select_page)) }
                }
            }
        }

        AnimatedVisibility(
            visible = chrome || searching,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.97f), tonalElevation = 3.dp) {
                Column(Modifier.statusBarsPadding()) {
                    if (searching) {
                        SearchBar(
                            query = query,
                            state = search,
                            onQuery = {
                                query = it
                                vm.search(it, search.options, current)
                            },
                            onClose = {
                                searching = false
                                query = ""
                                vm.clearSearch()
                            },
                            onStep = { vm.step(it) },
                            onResults = { sheet = "results" },
                            onOptions = { o ->
                                vm.search(query, o, current)
                            },
                        )
                    } else {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back)) }
                            Column(Modifier.weight(1f)) {
                                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    pageOf,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.testTag("pdf_page_label"),
                                )
                            }
                            IconButton(onClick = { searching = true }, modifier = Modifier.testTag("pdf_search")) { Icon(Icons.Rounded.Search, contentDescription = stringResource(R.string.common_search)) }
                            IconButton(onClick = { sheet = "contents" }, modifier = Modifier.testTag("pdf_contents")) { Icon(Icons.AutoMirrored.Rounded.List, contentDescription = stringResource(R.string.pdf_contents_and_pages)) }
                            val marked = current in marks
                            IconButton(onClick = { vm.toggleMark(current) }, modifier = Modifier.testTag("pdf_bookmark")) {
                                Icon(
                                    if (marked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                                    contentDescription = stringResource(if (marked) R.string.pdf_remove_bookmark else R.string.pdf_bookmark_this_page),
                                )
                            }
                            Box {
                                IconButton(onClick = { menu = true }, modifier = Modifier.testTag("pdf_menu")) { Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.common_more)) }
                                ReaderMenu(
                                    expanded = menu,
                                    theme = prefs.theme,
                                    keepScreenOn = prefs.keepScreenOn,
                                    onDismiss = { menu = false },
                                    onGoTo = { dialog = "goto" },
                                    onTheme = { t -> scope.launch { repo.editReader { it.copy(theme = t) } } },
                                    onKeepScreen = { v -> scope.launch { repo.editReader { it.copy(keepScreenOn = v) } } },
                                    onInfo = { dialog = "info" },
                                    onShare = { ReaderActions.share(context, source) },
                                    onPrint = { ReaderActions.print(context, source, title) },
                                    onOpenWith = onOpenWith,
                                    onSaveText = {
                                        scope.launch {
                                            snackbar.showSnackbar(tr(R.string.pdf_extracting_text))
                                            val text = vm.allText { }
                                            val out = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ReaderActions.saveText(source, title, text) } }
                                            snackbar.showSnackbar(out.fold({ tr(R.string.pdf_saved, it.name) }, { tr(R.string.pdf_save_failed, it.message) }))
                                        }
                                    },
                                    onSavePage = {
                                        scope.launch {
                                            val bitmap = vm.pageBitmap(current, 1800)
                                            val out = runCatching {
                                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ReaderActions.savePng(source, title, current, bitmap ?: error("render failed")) }
                                            }
                                            snackbar.showSnackbar(out.fold({ tr(R.string.pdf_saved, it.name) }, { tr(R.string.pdf_save_page_failed) }))
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        val pill by remember { derivedStateOf { chrome || state.moving } }
        AnimatedVisibility(
            visible = pill,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 16.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.88f),
                modifier = Modifier.clickable { dialog = "goto" }.testTag("pdf_page_pill"),
            ) {
                Text(
                    stringResource(R.string.pdf_page_pill, doc.label(current), doc.pageCount),
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }

        back?.let { saved ->
            if (chrome) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    tonalElevation = 4.dp,
                    shadowElevation = 4.dp,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .navigationBarsPadding()
                        .padding(start = 16.dp, bottom = 16.dp)
                        .clickable {
                            back = null
                            move(saved)
                        }
                        .testTag("pdf_back_to"),
                ) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.pdf_back_to_page, doc.label(state.layout?.position(saved)?.first ?: 0)), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }

        if (doc.pageCount > 3) {
            FastScroller(
                visible = chrome || state.moving,
                fraction = run {
                    val l = state.layout
                    val total = (l?.contentHeight ?: 1f) * state.viewport.zoom - state.size.height
                    if (total > 0) (state.viewport.y / total).coerceIn(0f, 1f) else 0f
                },
                label = doc.label(current),
                onDrag = { f ->
                    motionJob?.cancel()
                    val l = state.layout ?: return@FastScroller
                    val total = l.contentHeight * state.viewport.zoom - state.size.height
                    state.set(state.viewport.copy(y = f * total))
                },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 64.dp))
    }

    when (sheet) {
        "contents" -> ContentsSheet(
            doc = doc,
            images = images,
            current = current,
            marks = marks,
            theme = prefs.theme,
            onGo = { page, y, _ ->
                sheet = null
                scope.launch {
                    val shown = if (y != null) vm.displayTarget(dev.cluvex.strata.core.pdf.view.PdfTarget(page, null, y)) else null
                    goTo(page, shown?.y)
                }
            },
            onToggleMark = { vm.toggleMark(it) },
            onDismiss = { sheet = null },
        )
        "results" -> SearchResultsSheet(
            search = search,
            doc = doc,
            snippet = { hit -> PdfSearch.snippet(vm.text(hit.page).text, hit) },
            onSelect = {
                sheet = null
                vm.select(it)
            },
            onDismiss = { sheet = null },
        )
    }
    when (dialog) {
        "goto" -> GoToPageDialog(doc.pageCount, doc.labels, current, onGo = {
            dialog = null
            goTo(it)
        }, onDismiss = { dialog = null })
        "info" -> InfoDialog(title, source.path, doc.meta, doc.pageCount, doc.sizes.getOrElse(0) { 0f }, doc.sizes.getOrElse(1) { 0f }, onDismiss = { dialog = null })
    }
    link?.let { uri ->
        LinkDialog(
            uri = uri,
            onOpen = {
                link = null
                if (!ReaderActions.safeLink(uri) || !ReaderActions.openLink(context, uri)) scope.launch { snackbar.showSnackbar(tr(R.string.pdf_no_app_for_link)) }
            },
            onCopy = {
                clipboard.setText(uri)
                link = null
            },
            onDismiss = { link = null },
        )
    }
}

@Composable
private fun SearchBar(
    query: String,
    state: SearchState,
    onQuery: (String) -> Unit,
    onClose: () -> Unit,
    onStep: (Int) -> Unit,
    onResults: () -> Unit,
    onOptions: (SearchOptions) -> Unit,
) {
    val focus = remember { FocusRequester() }
    var options by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose, modifier = Modifier.testTag("pdf_search_close")) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.pdf_close_search)) }
        TextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            placeholder = { Text(stringResource(R.string.pdf_search_in_document)) },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onStep(1) }),
            modifier = Modifier.weight(1f).focusRequester(focus).testTag("pdf_search_field"),
        )
        if (state.active) {
            TextButton(onClick = onResults, modifier = Modifier.widthIn(min = 48.dp).testTag("pdf_search_count")) {
                Text(
                    when {
                        state.hits.isEmpty() && state.running -> "…"
                        state.hits.isEmpty() -> stringResource(R.string.pdf_number, 0)
                        state.running -> stringResource(R.string.pdf_search_position_running, state.current + 1, state.hits.size)
                        else -> stringResource(R.string.pdf_search_position, state.current + 1, state.hits.size)
                    },
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            IconButton(onClick = { onStep(-1) }, enabled = state.hits.isNotEmpty(), modifier = Modifier.testTag("pdf_search_prev")) { Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = stringResource(R.string.pdf_previous_result)) }
            IconButton(onClick = { onStep(1) }, enabled = state.hits.isNotEmpty(), modifier = Modifier.testTag("pdf_search_next")) { Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = stringResource(R.string.pdf_next_result)) }
        }
        Box {
            IconButton(onClick = { options = true }) { Icon(Icons.Rounded.Tune, contentDescription = stringResource(R.string.pdf_search_options)) }
            DropdownMenu(expanded = options, onDismissRequest = { options = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.pdf_match_case)) },
                    leadingIcon = { if (state.options.matchCase) Icon(Icons.Rounded.Check, contentDescription = null) },
                    onClick = { onOptions(state.options.copy(matchCase = !state.options.matchCase)) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.pdf_whole_words)) },
                    leadingIcon = { if (state.options.wholeWords) Icon(Icons.Rounded.Check, contentDescription = null) },
                    onClick = { onOptions(state.options.copy(wholeWords = !state.options.wholeWords)) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.pdf_exact_letters)) },
                    leadingIcon = { if (state.options.exactLetters) Icon(Icons.Rounded.Check, contentDescription = null) },
                    onClick = { onOptions(state.options.copy(exactLetters = !state.options.exactLetters)) },
                )
            }
        }
    }
    if (state.running) {
        androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth().height(2.dp))
    }
}

@Composable
private fun ReaderMenu(
    expanded: Boolean,
    theme: ReaderTheme,
    keepScreenOn: Boolean,
    onDismiss: () -> Unit,
    onGoTo: () -> Unit,
    onTheme: (ReaderTheme) -> Unit,
    onKeepScreen: (Boolean) -> Unit,
    onInfo: () -> Unit,
    onShare: () -> Unit,
    onPrint: () -> Unit,
    onOpenWith: (() -> Unit)?,
    onSaveText: () -> Unit,
    onSavePage: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text(stringResource(R.string.pdf_go_to_page_menu)) }, leadingIcon = { Icon(Icons.Rounded.Pages, contentDescription = null) }, onClick = { onDismiss(); onGoTo() })
        HorizontalDivider()
        for (t in ReaderTheme.entries) {
            DropdownMenuItem(
                text = { Text(stringResource(t.label)) },
                leadingIcon = {
                    Icon(
                        when (t) {
                            ReaderTheme.PAPER -> Icons.Rounded.LightMode
                            ReaderTheme.SEPIA -> Icons.Rounded.WbSunny
                            ReaderTheme.NIGHT -> Icons.Rounded.DarkMode
                        },
                        contentDescription = null,
                    )
                },
                trailingIcon = { if (t == theme) Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.pdf_selected)) },
                onClick = { onTheme(t) },
                modifier = Modifier.testTag("pdf_theme_${t.name.lowercase()}"),
            )
        }
        DropdownMenuItem(
            text = { Text(stringResource(R.string.pdf_keep_screen_on)) },
            trailingIcon = { if (keepScreenOn) Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.common_on)) },
            onClick = { onKeepScreen(!keepScreenOn) },
        )
        HorizontalDivider()
        DropdownMenuItem(text = { Text(stringResource(R.string.pdf_save_text)) }, leadingIcon = { Icon(Icons.Rounded.Description, contentDescription = null) }, onClick = { onDismiss(); onSaveText() })
        DropdownMenuItem(text = { Text(stringResource(R.string.pdf_save_page_image)) }, leadingIcon = { Icon(Icons.Rounded.Image, contentDescription = null) }, onClick = { onDismiss(); onSavePage() })
        DropdownMenuItem(text = { Text(stringResource(R.string.common_share)) }, leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) }, onClick = { onDismiss(); onShare() })
        DropdownMenuItem(text = { Text(stringResource(R.string.pdf_print)) }, leadingIcon = { Icon(Icons.Rounded.Print, contentDescription = null) }, onClick = { onDismiss(); onPrint() })
        if (onOpenWith != null) {
            DropdownMenuItem(text = { Text(stringResource(R.string.common_open_with)) }, leadingIcon = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null) }, onClick = { onDismiss(); onOpenWith() })
        }
        DropdownMenuItem(text = { Text(stringResource(R.string.pdf_document_info)) }, leadingIcon = { Icon(Icons.Rounded.Info, contentDescription = null) }, onClick = { onDismiss(); onInfo() }, modifier = Modifier.testTag("pdf_info_item"))
    }
}

@Composable
private fun FastScroller(visible: Boolean, fraction: Float, label: String, onDrag: (Float) -> Unit, modifier: Modifier = Modifier) {
    var dragging by remember { mutableStateOf(false) }
    AnimatedVisibility(visible = visible || dragging, enter = fadeIn(), exit = fadeOut(), modifier = modifier.fillMaxHeight()) {
        BoxWithConstraints(Modifier.fillMaxHeight().width(48.dp).padding(vertical = 96.dp)) {
            val track = constraints.maxHeight.toFloat()
            val density = LocalDensity.current
            val thumb = with(density) { 44.dp.toPx() }
            val y = (track - thumb) * fraction
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(48.dp)
                    .pointerInput(track) {
                        detectVerticalDragGestures(
                            onDragStart = { dragging = true; onDrag(((it.y - thumb / 2) / (track - thumb)).coerceIn(0f, 1f)) },
                            onDragEnd = { dragging = false },
                            onDragCancel = { dragging = false },
                        ) { change, _ ->
                            change.consume()
                            onDrag(((change.position.y - thumb / 2) / (track - thumb)).coerceIn(0f, 1f))
                        }
                    }
                    .testTag("pdf_scroller"),
            ) {
                Surface(
                    shape = RoundedCornerShape(topStart = 22.dp, bottomStart = 22.dp, topEnd = 6.dp, bottomEnd = 6.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shadowElevation = 3.dp,
                    modifier = Modifier.align(Alignment.TopEnd).offset { IntOffset(0, y.roundToInt()) }.size(width = 28.dp, height = 44.dp),
                ) {}
                if (dragging) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.TopEnd).offset { IntOffset(-with(density) { 44.dp.roundToPx() }, y.roundToInt()) },
                    ) {
                        Text(label, color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                    }
                }
            }
        }
    }
}
