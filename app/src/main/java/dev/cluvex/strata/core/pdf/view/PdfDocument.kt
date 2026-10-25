package dev.cluvex.strata.core.pdf.view

import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

enum class PdfError { FILE, FORMAT, PASSWORD, SECURITY, PAGE, ENGINE, UNKNOWN }

class PdfOpenException(val error: PdfError) : IOException(
    tr(
        when (error) {
            PdfError.FILE -> R.string.pdf_error_file
            PdfError.FORMAT -> R.string.pdf_error_format
            PdfError.PASSWORD -> R.string.pdf_error_password
            PdfError.SECURITY -> R.string.pdf_error_security
            PdfError.PAGE -> R.string.pdf_error_page
            PdfError.ENGINE -> R.string.pdf_error_engine
            PdfError.UNKNOWN -> R.string.pdf_error_unknown
        },
    ),
)

data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
}

data class PageTransform(val a: Float, val b: Float, val c: Float, val d: Float, val e: Float, val f: Float, val width: Float, val height: Float) {
    fun x(px: Float, py: Float): Float = a * px + c * py + e
    fun y(px: Float, py: Float): Float = b * px + d * py + f
}

data class PdfLink(val bounds: Box, val page: Int, val target: PdfTarget?, val uri: String?, val fromText: Boolean)

data class PdfTarget(val page: Int, val x: Float?, val y: Float?)

data class OutlineItem(val title: String, val depth: Int, val target: PdfTarget?, val open: Boolean)

data class PdfMeta(
    val title: String,
    val author: String,
    val subject: String,
    val keywords: String,
    val creator: String,
    val producer: String,
    val created: String,
    val modified: String,
    val version: String,
    val permissions: Long,
    val securityRevision: Int,
    val tagged: Boolean,
) {
    val encrypted: Boolean get() = securityRevision >= 0
}

class PageChars(val data: FloatArray) {
    val count: Int get() = data.size / STRIDE
    fun codepoint(i: Int): Int = data[i * STRIDE].toInt()
    fun flags(i: Int): Int = data[i * STRIDE + 1].toInt()
    fun left(i: Int): Float = data[i * STRIDE + 2]
    fun top(i: Int): Float = data[i * STRIDE + 3]
    fun right(i: Int): Float = data[i * STRIDE + 4]
    fun bottom(i: Int): Float = data[i * STRIDE + 5]
    fun originX(i: Int): Float = data[i * STRIDE + 6]
    fun originY(i: Int): Float = data[i * STRIDE + 7]
    fun size(i: Int): Float = data[i * STRIDE + 8]
    fun dirX(i: Int): Float = data[i * STRIDE + 9]
    fun dirY(i: Int): Float = data[i * STRIDE + 10]

    companion object {
        const val STRIDE = 12
        const val GENERATED = 1
        const val HYPHEN = 2
        const val MAP_ERROR = 4
    }
}

class PdfDocument private constructor(private val id: Long, private val sizes: FloatArray) : Closeable {

    @Volatile
    private var closed = false

    val pageCount: Int get() = sizes.size / 2

    fun pageWidth(page: Int): Float = sizes[page * 2]

    fun pageHeight(page: Int): Float = sizes[page * 2 + 1]

    fun render(page: Int, buffer: ByteBuffer, width: Int, height: Int, startX: Int, startY: Int, sizeX: Int, sizeY: Int, annotations: Boolean = true): RenderResult {
        check(!closed)
        val code = Pdfium.nRender(id, page, buffer, width, height, startX, startY, sizeX, sizeY, if (annotations) 1 else 0)
        return when {
            code == 0 -> RenderResult.DONE
            code == 1 -> RenderResult.CANCELLED
            else -> RenderResult.FAILED
        }
    }

    fun transform(page: Int): PageTransform? {
        val t = Pdfium.nPageTransform(id, page)?.takeIf { it.size >= 8 } ?: return null
        return PageTransform(t[0], t[1], t[2], t[3], t[4], t[5], t[6], t[7])
    }

    fun chars(page: Int): PageChars = PageChars(Pdfium.nPageText(id, page) ?: FloatArray(0))

    fun links(page: Int): List<PdfLink> {
        val pair = Pdfium.nLinks(id, page) ?: return emptyList()
        val numbers = pair.getOrNull(0) as? FloatArray ?: return emptyList()
        val texts = pair.getOrNull(1) as? Array<*> ?: return emptyList()
        val out = ArrayList<PdfLink>()
        for (i in 0 until numbers.size / LINK_STRIDE) {
            val o = i * LINK_STRIDE
            val bounds = Box(numbers[o], numbers[o + 1], numbers[o + 2], numbers[o + 3])
            when (numbers[o + 4].toInt()) {
                0 -> {
                    val target = numbers[o + 5].toInt()
                    if (target in 0 until pageCount) {
                        out += PdfLink(bounds, page, displayTarget(target, numbers[o + 6] > 0f, numbers[o + 7], numbers[o + 8]), null, false)
                    }
                }
                1, 2 -> {
                    val uri = (texts.getOrNull(i) as? String)?.trim().orEmpty()
                    if (uri.isNotEmpty()) out += PdfLink(bounds, page, null, uri, numbers[o + 4].toInt() == 2)
                }
            }
        }
        return out
    }

    fun outline(): List<OutlineItem> {
        val pair = Pdfium.nOutline(id) ?: return emptyList()
        val numbers = pair.getOrNull(0) as? FloatArray ?: return emptyList()
        val titles = pair.getOrNull(1) as? Array<*> ?: return emptyList()
        return (0 until numbers.size / OUTLINE_STRIDE).map { i ->
            val o = i * OUTLINE_STRIDE
            val target = numbers[o + 1].toInt()
            OutlineItem(
                title = (titles.getOrNull(i) as? String).orEmpty().trim(),
                depth = numbers[o].toInt(),
                target = if (target in 0 until pageCount) PdfTarget(target, null, if (numbers[o + 2] > 0f) numbers[o + 3] else null) else null,
                open = numbers[o + 4] > 0f,
            )
        }
    }

    fun displayTarget(target: PdfTarget): PdfTarget {
        val y = target.y ?: return target
        return displayTarget(target.page, true, target.x ?: 0f, y)
    }

    private fun displayTarget(page: Int, hasY: Boolean, x: Float, y: Float): PdfTarget {
        if (!hasY) return PdfTarget(page, null, null)
        val t = transform(page) ?: return PdfTarget(page, null, null)
        return PdfTarget(page, t.x(x, y), t.y(x, y))
    }

    fun labels(): List<String?> = Pdfium.nPageLabels(id)?.map { it?.takeIf { s -> s.isNotBlank() } } ?: List(pageCount) { null }

    fun meta(): PdfMeta {
        val m = Pdfium.nMeta(id) ?: arrayOfNulls(12)
        fun s(i: Int) = m.getOrNull(i).orEmpty().trim()
        return PdfMeta(
            title = s(0),
            author = s(1),
            subject = s(2),
            keywords = s(3),
            creator = s(4),
            producer = s(5),
            created = s(6),
            modified = s(7),
            version = s(8),
            permissions = s(9).toLongOrNull() ?: -1L,
            securityRevision = s(10).toIntOrNull() ?: -1,
            tagged = s(11) == "1",
        )
    }

    override fun close() {
        if (closed) return
        closed = true
        Pdfium.nClose(id)
    }

    enum class RenderResult { DONE, CANCELLED, FAILED }

    companion object {
        private const val LINK_STRIDE = 9
        private const val OUTLINE_STRIDE = 5

        fun open(file: File, password: String? = null): PdfDocument {
            if (!Pdfium.ensure()) throw PdfOpenException(PdfError.ENGINE)
            return opened(Pdfium.nOpenPath(file.path, password?.takeIf { it.isNotEmpty() }))
        }

        fun open(fd: Int, password: String? = null): PdfDocument {
            if (!Pdfium.ensure()) throw PdfOpenException(PdfError.ENGINE)
            return opened(Pdfium.nOpenFd(fd, password?.takeIf { it.isNotEmpty() }))
        }

        private fun opened(id: Long): PdfDocument {
            if (id <= 0) {
                throw PdfOpenException(
                    when (-id) {
                        2L -> PdfError.FILE
                        3L -> PdfError.FORMAT
                        4L -> PdfError.PASSWORD
                        5L -> PdfError.SECURITY
                        6L -> PdfError.PAGE
                        100L -> PdfError.ENGINE
                        else -> PdfError.UNKNOWN
                    },
                )
            }
            val sizes = Pdfium.nPageSizes(id)
            if (sizes == null || sizes.isEmpty()) {
                Pdfium.nClose(id)
                throw PdfOpenException(PdfError.FORMAT)
            }
            return PdfDocument(id, sizes)
        }
    }
}
