package dev.cluvex.strata.feature.pdf

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.cluvex.strata.core.pdf.view.PdfDocument
import dev.cluvex.strata.core.pdf.view.Pdfium
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

sealed interface ImageKey {
    val page: Int
}

data class WholeKey(override val page: Int, val width: Int) : ImageKey

data class TileKey(override val page: Int, val base: Int, val level: Float, val col: Int, val row: Int) : ImageKey

data class ThumbKey(override val page: Int, val width: Int) : ImageKey

class PageImages(private val doc: PdfDocument, scope: CoroutineScope, budget: Long) {

    private inner class Cache(bytes: Long) : LruCache<ImageKey, ImageBitmap>(bytes.coerceIn(4L shl 20, Int.MAX_VALUE.toLong()).toInt()) {
        override fun sizeOf(key: ImageKey, value: ImageBitmap): Int = value.width * value.height * 4

        override fun entryRemoved(evicted: Boolean, key: ImageKey, oldValue: ImageBitmap, newValue: ImageBitmap?) {
            if (key !is TileKey) best.remove(key.page, key)
        }
    }

    private val best = ConcurrentHashMap<Int, ImageKey>()
    private val wholes = Cache(budget / 2)
    private val tiles = Cache(budget * 7 / 20)
    private val thumbs = Cache(budget * 3 / 20)
    private val failed = HashSet<ImageKey>()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    @Volatile
    private var wanted: List<ImageKey> = emptyList()

    @Volatile
    private var viewKeys: List<ImageKey> = emptyList()

    @Volatile
    private var thumbKeys: List<ImageKey> = emptyList()

    @Volatile
    private var running: ImageKey? = null

    private var buffer: ByteBuffer? = null

    init {
        scope.launch(Pdfium.dispatcher) {
            while (isActive) {
                val next = wanted.firstOrNull { get(it) == null && synchronized(failed) { it !in failed } }
                if (next == null) {
                    signal.receive()
                    continue
                }
                running = next
                val outcome = runCatching { render(next) }.getOrElse { Outcome.Failed }
                running = null
                when (outcome) {
                    is Outcome.Done -> {
                        cache(next).put(next, outcome.image)
                        if (next !is TileKey) {
                            val previous = best[next.page]?.let { get(it) }
                            if (previous == null || previous.width <= outcome.image.width) best[next.page] = next
                        }
                        _version.value = _version.value + 1
                    }
                    Outcome.Failed -> synchronized(failed) { failed += next }
                    Outcome.Cancelled -> Unit
                }
            }
        }
    }

    private fun cache(key: ImageKey): Cache = when (key) {
        is WholeKey -> wholes
        is TileKey -> tiles
        is ThumbKey -> thumbs
    }

    fun get(key: ImageKey): ImageBitmap? = cache(key).get(key)

    fun wantView(keys: List<ImageKey>) {
        viewKeys = keys
        update()
    }

    fun wantThumbs(keys: List<ImageKey>) {
        thumbKeys = keys
        update()
    }

    private fun update() {
        val keys = viewKeys + thumbKeys
        wanted = keys
        val r = running
        if (r != null && r !in keys) Pdfium.cancelRender()
        signal.trySend(Unit)
    }

    fun bestWhole(page: Int): ImageBitmap? = best[page]?.let { get(it) }

    fun clear() {
        wanted = emptyList()
        viewKeys = emptyList()
        thumbKeys = emptyList()
        wholes.evictAll()
        tiles.evictAll()
        thumbs.evictAll()
        best.clear()
    }

    private fun size(key: ImageKey): Triple<Int, Int, IntArray> {
        val pw = doc.pageWidth(key.page)
        val ph = doc.pageHeight(key.page)
        return when (key) {
            is WholeKey, is ThumbKey -> {
                val requested = if (key is WholeKey) key.width else (key as ThumbKey).width
                var w = requested.coerceIn(1, MAX_SIDE)
                var h = (w * ph / pw).roundToInt().coerceAtLeast(1)
                if (h > MAX_SIDE) {
                    h = MAX_SIDE
                    w = (h * pw / ph).roundToInt().coerceAtLeast(1)
                }
                Triple(w, h, intArrayOf(0, 0, w, h))
            }
            is TileKey -> {
                val fullW = (key.base * key.level).roundToInt().coerceAtLeast(1)
                val fullH = (key.base * key.level * ph / pw).roundToInt().coerceAtLeast(1)
                val x = key.col * TILE
                val y = key.row * TILE
                val w = min(TILE, fullW - x).coerceAtLeast(1)
                val h = min(TILE, fullH - y).coerceAtLeast(1)
                Triple(w, h, intArrayOf(-x, -y, fullW, fullH))
            }
        }
    }

    private sealed interface Outcome {
        class Done(val image: ImageBitmap) : Outcome
        data object Cancelled : Outcome
        data object Failed : Outcome
    }

    private fun render(key: ImageKey): Outcome {
        val (w, h, area) = size(key)
        val bytes = w * h * 4
        val buf = buffer?.takeIf { it.capacity() >= bytes } ?: ByteBuffer.allocateDirect(max(bytes, 1 shl 20)).also { buffer = it }
        buf.clear()
        when (doc.render(key.page, buf, w, h, area[0], area[1], area[2], area[3])) {
            PdfDocument.RenderResult.CANCELLED -> return Outcome.Cancelled
            PdfDocument.RenderResult.FAILED -> return Outcome.Failed
            PdfDocument.RenderResult.DONE -> Unit
        }
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        buf.position(0)
        buf.limit(bytes)
        bitmap.copyPixelsFromBuffer(buf)
        bitmap.prepareToDraw()
        return Outcome.Done(bitmap.asImageBitmap())
    }

    companion object {
        const val TILE = 512
        const val MAX_SIDE = 4096
    }
}
