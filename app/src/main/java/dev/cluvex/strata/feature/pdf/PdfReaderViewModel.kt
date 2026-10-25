package dev.cluvex.strata.feature.pdf

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.util.LruCache
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.cluvex.strata.R
import dev.cluvex.strata.core.data.SettingsRepository
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.pdf.view.OutlineItem
import dev.cluvex.strata.core.pdf.view.PageText
import dev.cluvex.strata.core.pdf.view.PdfDocument
import dev.cluvex.strata.core.pdf.view.PdfError
import dev.cluvex.strata.core.pdf.view.PdfLink
import dev.cluvex.strata.core.pdf.view.PdfMeta
import dev.cluvex.strata.core.pdf.view.PdfOpenException
import dev.cluvex.strata.core.pdf.view.PdfSearch
import dev.cluvex.strata.core.pdf.view.PdfTarget
import dev.cluvex.strata.core.pdf.view.PdfTextLayout
import dev.cluvex.strata.core.pdf.view.Pdfium
import dev.cluvex.strata.core.pdf.view.ReadingSpot
import dev.cluvex.strata.core.pdf.view.SearchHit
import dev.cluvex.strata.core.pdf.view.SearchOptions
import dev.cluvex.strata.core.root.RootManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

data class ReaderSource(val path: String?, val uri: String?) {
    val key: String get() = path ?: uri.orEmpty()
}

sealed interface ReaderPhase {
    data object Loading : ReaderPhase
    data class Password(val wrong: Boolean) : ReaderPhase
    data class Failed(val message: String) : ReaderPhase
    data object Ready : ReaderPhase
}

data class ReaderDoc(
    val sizes: FloatArray,
    val labels: List<String?>,
    val outline: List<OutlineItem>,
    val meta: PdfMeta,
    val spot: ReadingSpot?,
) {
    val pageCount: Int get() = sizes.size / 2
    fun label(page: Int): String {
        val own = labels.getOrNull(page) ?: return tr(R.string.pdf_number, page + 1)
        val number = own.takeIf { it.length in 1..9 && it.all { c -> c in '0'..'9' } && (it.length == 1 || it[0] != '0') }?.toInt()
        return if (number != null) tr(R.string.pdf_number, number) else own
    }
}

data class SearchState(
    val query: String = "",
    val options: SearchOptions = SearchOptions(),
    val running: Boolean = false,
    val scanned: Int = 0,
    val hits: List<SearchHit> = emptyList(),
    val current: Int = -1,
) {
    val active: Boolean get() = query.isNotBlank()
}

class PdfReaderViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SettingsRepository(app)
    private val _phase = MutableStateFlow<ReaderPhase>(ReaderPhase.Loading)
    val phase: StateFlow<ReaderPhase> = _phase
    private val _doc = MutableStateFlow<ReaderDoc?>(null)
    val doc: StateFlow<ReaderDoc?> = _doc
    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search
    private val _title = MutableStateFlow("")
    val title: StateFlow<String> = _title

    var source: ReaderSource? = null
        private set
    var document: PdfDocument? = null
        private set
    var images: PageImages? = null
        private set
    private var copy: File? = null
    private val texts = LruCache<Int, PageText>(40)
    private val links = LruCache<Int, List<PdfLink>>(40)
    private var plain: Array<String?> = emptyArray()
    private var searchJob: Job? = null
    private var spotJob: Job? = null

    val marks = MutableStateFlow<List<Int>>(emptyList())

    fun open(source: ReaderSource, rootEnabled: Boolean, password: String? = null) {
        if (document != null) return
        this.source = source
        _phase.value = ReaderPhase.Loading
        viewModelScope.launch {
            _title.value = displayName(source)
            val result = runCatching { withContext(Pdfium.dispatcher) { load(source, rootEnabled, password) } }
            result.onSuccess { (d, info) ->
                document = d
                plain = arrayOfNulls(d.pageCount)
                val budget = memoryBudget()
                images = PageImages(d, viewModelScope, budget)
                _doc.value = info
                _phase.value = ReaderPhase.Ready
                launch { repo.readerMarks.map { it[source.key].orEmpty() }.collect { marks.value = it } }
            }.onFailure { e ->
                _phase.value = when {
                    e is PdfOpenException && e.error == PdfError.PASSWORD -> ReaderPhase.Password(wrong = password != null)
                    else -> ReaderPhase.Failed(e.message ?: tr(R.string.pdf_error_unknown))
                }
            }
        }
    }

    fun retryWithPassword(password: String, rootEnabled: Boolean) {
        val s = source ?: return
        open(s, rootEnabled, password)
    }

    private suspend fun load(source: ReaderSource, rootEnabled: Boolean, password: String?): Pair<PdfDocument, ReaderDoc> {
        val app = getApplication<Application>()
        val d = when {
            source.uri != null -> {
                val pfd = app.contentResolver.openFileDescriptor(Uri.parse(source.uri), "r") ?: throw IOException(tr(R.string.pdf_error_file))
                pfd.use { PdfDocument.open(it.fd, password) }
            }
            source.path != null -> {
                val file = File(source.path)
                if (file.canRead()) {
                    PdfDocument.open(file, password)
                } else {
                    val local = copy ?: rootCopy(app, file, rootEnabled).also { copy = it }
                    PdfDocument.open(local, password)
                }
            }
            else -> throw IOException(tr(R.string.pdf_nothing_to_open))
        }
        val sizes = FloatArray(d.pageCount * 2) { i -> if (i % 2 == 0) d.pageWidth(i / 2) else d.pageHeight(i / 2) }
        val spot = repo.readerSpots.first()[source.key]
        return d to ReaderDoc(sizes, d.labels(), d.outline(), d.meta(), spot)
    }

    private suspend fun rootCopy(app: Application, file: File, rootEnabled: Boolean): File {
        if (!rootEnabled) throw IOException(tr(R.string.pdf_needs_root))
        val access = RootManager.access() ?: throw IOException(tr(R.string.pdf_root_unavailable))
        val dir = File(app.cacheDir, "reader").apply { mkdirs() }
        val out = File(dir, "${System.nanoTime()}-${file.name}")
        withContext(Dispatchers.IO) {
            access.openInput(file).use { input -> out.outputStream().use { input.copyTo(it) } }
        }
        return out
    }

    private fun displayName(source: ReaderSource): String {
        source.path?.let { return File(it).name }
        val uri = source.uri?.let(Uri::parse) ?: return "PDF"
        val app = getApplication<Application>()
        return runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "PDF"
    }

    private fun memoryBudget(): Long {
        val am = getApplication<Application>().getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mb = am.memoryClass.toLong()
        return (mb / 3).coerceIn(48, 192) shl 20
    }

    suspend fun text(page: Int): PageText {
        texts.get(page)?.let { return it }
        val d = document ?: return PageText.EMPTY
        val chars = withContext(Pdfium.dispatcher) { d.chars(page) }
        val built = withContext(Dispatchers.Default) { PdfTextLayout.build(chars) }
        texts.put(page, built)
        if (page in plain.indices) plain[page] = built.text
        return built
    }

    fun cachedText(page: Int): PageText? = texts.get(page)

    suspend fun links(page: Int): List<PdfLink> {
        links.get(page)?.let { return it }
        val d = document ?: return emptyList()
        val found = withContext(Pdfium.dispatcher) { d.links(page) }
        links.put(page, found)
        return found
    }

    suspend fun displayTarget(target: PdfTarget): PdfTarget {
        val d = document ?: return target
        return withContext(Pdfium.dispatcher) { d.displayTarget(target) }
    }

    fun search(query: String, options: SearchOptions, fromPage: Int) {
        searchJob?.cancel()
        val trimmed = query
        if (trimmed.isBlank()) {
            _search.value = SearchState(query = trimmed, options = options)
            return
        }
        val count = document?.pageCount ?: return
        _search.value = SearchState(query = trimmed, options = options, running = true)
        searchJob = viewModelScope.launch {
            delay(220)
            val q = PdfSearch.fold(trimmed, options)
            if (q.text.isBlank()) {
                _search.update { it.copy(running = false) }
                return@launch
            }
            val start = fromPage.coerceIn(0, count - 1)
            val order = (start until count) + (0 until start)
            val found = ArrayList<SearchHit>()
            var scanned = 0
            for (p in order) {
                ensureActive()
                val pageText = plain.getOrNull(p) ?: text(p).text
                val soft = texts.get(p)?.softHyphens ?: IntArray(0)
                val hits = withContext(Dispatchers.Default) { PdfSearch.find(p, pageText, PdfSearch.fold(pageText, options, soft), q, options) }
                scanned++
                if (hits.isNotEmpty()) found += hits
                if (hits.isNotEmpty() || scanned % 8 == 0 || scanned == count) {
                    val sorted = found.sortedWith(compareBy<SearchHit> { it.page }.thenBy { it.start })
                    _search.update { s ->
                        val current = if (s.current >= 0 && s.hits.isNotEmpty()) {
                            val keep = s.hits[s.current]
                            sorted.indexOf(keep).takeIf { it >= 0 } ?: 0
                        } else if (sorted.isNotEmpty()) {
                            sorted.indexOfFirst { it.page >= start }.takeIf { it >= 0 } ?: 0
                        } else {
                            -1
                        }
                        s.copy(scanned = scanned, hits = sorted, current = current)
                    }
                }
            }
            _search.update { it.copy(running = false) }
        }
    }

    fun step(delta: Int) {
        _search.update { s ->
            if (s.hits.isEmpty()) s else s.copy(current = ((s.current + delta) % s.hits.size + s.hits.size) % s.hits.size)
        }
    }

    fun select(hit: Int) {
        _search.update { s -> if (hit in s.hits.indices) s.copy(current = hit) else s }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _search.value = SearchState(options = _search.value.options)
    }

    fun saveSpot(spot: ReadingSpot) {
        val key = source?.key ?: return
        spotJob?.cancel()
        spotJob = viewModelScope.launch {
            delay(600)
            withContext(NonCancellable) { repo.saveReadingSpot(key, spot) }
        }
    }

    fun toggleMark(page: Int) {
        val key = source?.key ?: return
        viewModelScope.launch { repo.toggleReaderMark(key, page) }
    }

    suspend fun allText(onProgress: (Int) -> Unit): String {
        val count = document?.pageCount ?: return ""
        val sb = StringBuilder()
        for (p in 0 until count) {
            if (p > 0) sb.append("\n\n")
            sb.append(text(p).text)
            onProgress(p + 1)
        }
        return sb.toString()
    }

    suspend fun pageBitmap(page: Int, width: Int): Bitmap? {
        val d = document ?: return null
        return withContext(Pdfium.dispatcher) {
            val pw = d.pageWidth(page)
            val ph = d.pageHeight(page)
            val w = width.coerceIn(16, 4096)
            val h = (w * ph / pw).toInt().coerceIn(16, 8192)
            val buffer = java.nio.ByteBuffer.allocateDirect(w * h * 4)
            if (d.render(page, buffer, w, h, 0, 0, w, h) != PdfDocument.RenderResult.DONE) return@withContext null
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { copyPixelsFromBuffer(buffer) }
        }
    }

    override fun onCleared() {
        searchJob?.cancel()
        images?.clear()
        val d = document
        document = null
        val c = copy
        Pdfium.cancelRender()
        CoroutineScope(SupervisorJob() + Pdfium.dispatcher).launch {
            d?.close()
            c?.delete()
        }
    }
}
