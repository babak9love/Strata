package dev.cluvex.strata.core.pdf

import dev.cluvex.strata.R
import dev.cluvex.strata.core.fs.FileAccess
import dev.cluvex.strata.core.fs.FsUtil
import dev.cluvex.strata.core.fs.LocalFileAccess
import dev.cluvex.strata.core.fs.OpProgress
import dev.cluvex.strata.core.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.IOException
import java.security.MessageDigest

object PdfMerger {

    fun isPdf(file: File, access: FileAccess = LocalFileAccess): Boolean = runCatching {
        access.openInput(file).use { input ->
            val head = ByteArray(1024)
            var n = 0
            while (n < head.size) {
                val r = input.read(head, n, head.size - n)
                if (r < 0) break
                n += r
            }
            String(head, 0, n, Charsets.ISO_8859_1).contains("%PDF-")
        }
    }.getOrDefault(false)

    fun outputName(firstName: String): String = firstName.substringBeforeLast('.', firstName).ifBlank { "document" } + "-merged.pdf"

    fun merge(
        inputs: List<String>,
        outputPath: String,
        from: FileAccess = LocalFileAccess,
        to: FileAccess = from,
        bookmarks: Boolean = true,
    ): Flow<OpProgress> = flow {
        if (inputs.size < 2) throw IOException(tr(R.string.pdf_merge_need_two))
        val files = inputs.map { from.file(it) }
        files.firstOrNull { !it.isFile }?.let { throw IOException(tr(R.string.pdf_merge_not_file, it.name)) }
        val out = to.file(outputPath)
        if (FsUtil.exists(out, to)) throw IOException(tr(R.string.common_already_exists, out.name))
        if (files.any { it.absolutePath == out.absolutePath }) throw IOException(tr(R.string.pdf_merge_output_is_input))
        val versions = files.map { f ->
            ChannelSource(from.openReadChannel(f)).use { PdfFile.header(it) } ?: throw PdfException(tr(R.string.pdf_not_pdf_named, f.name))
        }
        val total = files.sumOf { it.length() }.coerceAtLeast(1)
        try {
            to.openOutput(out).use { stream ->
                val writer = PdfWriter(stream)
                writer.header(versions.maxOrNull()?.takeIf { it > "1.4" } ?: "1.4")
                val catalog = 1
                val pagesRoot = 2
                val outlines = 3
                var next = 4
                val kids = ArrayList<Int>()
                val marks = ArrayList<Pair<String, Int>>()
                var done = 0L
                files.forEachIndexed { index, file ->
                    emit(OpProgress.Running(file.name, done, total, index, files.size))
                    ChannelSource(from.openReadChannel(file)).use { source ->
                        val doc = try {
                            PdfFile.open(source)
                        } catch (e: PdfPasswordException) {
                            throw PdfPasswordException(tr(R.string.pdf_password_named, file.name))
                        } catch (e: IOException) {
                            throw PdfException("${file.name}: ${e.message}")
                        }
                        val pages = try {
                            doc.pages()
                        } catch (e: IOException) {
                            throw PdfException("${file.name}: ${e.message}")
                        }
                        val copier = Copier(doc, writer, pagesRoot, catalog) { next++ }
                        val numbers = copier.reserve(pages)
                        pages.forEachIndexed { i, page ->
                            currentCoroutineContext().ensureActive()
                            copier.writePage(page, numbers[i])
                        }
                        kids += numbers
                        marks += file.name.substringBeforeLast('.', file.name) to numbers.first()
                    }
                    done += file.length()
                    emit(OpProgress.Running(file.name, done, total, index + 1, files.size))
                }
                if (bookmarks) {
                    val items = marks.map { next++ }
                    marks.forEachIndexed { i, (title, page) ->
                        val item = PdfDictionary()
                        item["Title"] = textString(title)
                        item["Parent"] = PdfReference(outlines, 0)
                        if (i > 0) item["Prev"] = PdfReference(items[i - 1], 0)
                        if (i < items.lastIndex) item["Next"] = PdfReference(items[i + 1], 0)
                        item["Dest"] = PdfArray(mutableListOf(PdfReference(page, 0), PdfName("Fit")))
                        writer.write(items[i], item)
                    }
                    writer.write(
                        outlines,
                        PdfDictionary().apply {
                            this["Type"] = PdfName("Outlines")
                            this["First"] = PdfReference(items.first(), 0)
                            this["Last"] = PdfReference(items.last(), 0)
                            this["Count"] = PdfNumber(items.size.toLong())
                        },
                    )
                }
                writer.write(
                    pagesRoot,
                    PdfDictionary().apply {
                        this["Type"] = PdfName("Pages")
                        this["Kids"] = PdfArray(kids.mapTo(ArrayList()) { PdfReference(it, 0) })
                        this["Count"] = PdfNumber(kids.size.toLong())
                    },
                )
                writer.write(
                    catalog,
                    PdfDictionary().apply {
                        this["Type"] = PdfName("Catalog")
                        this["Pages"] = PdfReference(pagesRoot, 0)
                        if (bookmarks) this["Outlines"] = PdfReference(outlines, 0)
                    },
                )
                val id = MessageDigest.getInstance("MD5").digest((outputPath + System.nanoTime() + inputs.joinToString("\u0000")).toByteArray())
                writer.finish(catalog, next, id)
            }
        } catch (e: Throwable) {
            runCatching { FsUtil.deleteRecursively(out, to) }
            throw e
        }
        emit(OpProgress.Finished(1, emptyList(), created = listOf(out.absolutePath)))
    }.flowOn(Dispatchers.IO)

    private fun textString(text: String): PdfString =
        if (text.all { it.code in 0x20..0x7E }) PdfString(text.toByteArray(Charsets.ISO_8859_1))
        else PdfString(byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + text.toByteArray(Charsets.UTF_16BE))

    private class Copier(
        private val doc: PdfFile,
        private val writer: PdfWriter,
        private val pagesRoot: Int,
        private val catalog: Int,
        private val allocate: () -> Int,
    ) {
        private val numbers = HashMap<Int, Int>()
        private val queue = ArrayDeque<Int>()
        private val treeNodes = HashSet(doc.pageTreeNodes)
        private val catalogNumber = doc.catalogNumber
        private val encryptNumber = (doc.trailer["Encrypt"] as? PdfReference)?.number

        fun reserve(pages: List<PdfPage>): List<Int> = pages.map { p -> numbers.getOrPut(p.ref.number) { allocate() } }

        fun writePage(page: PdfPage, number: Int) {
            val d = PdfDictionary()
            for ((k, v) in page.dictionary.entries) if (k !in DROPPED) d[k] = map(v)
            d["Type"] = PdfName("Page")
            d["Parent"] = PdfReference(pagesRoot, 0)
            writer.write(number, d)
            drain()
        }

        private fun drain() {
            while (queue.isNotEmpty()) {
                val old = queue.removeFirst()
                val number = numbers.getValue(old)
                when (val obj = doc.getObject(old)) {
                    is PdfStream -> stream(number, obj)
                    null -> writer.write(number, PdfNull)
                    else -> writer.write(number, map(obj))
                }
            }
        }

        private fun stream(number: Int, s: PdfStream) {
            val dict = PdfDictionary()
            for ((k, v) in s.dictionary.entries) if (k != "Length") dict[k] = map(v)
            dropCrypt(dict)
            val raw = doc.rawLength(s)
            if (raw != null) {
                writer.writeStream(number, dict, raw) { doc.copyRaw(s, it) }
            } else {
                val bytes = doc.streamBytes(s)
                writer.writeStream(number, dict, bytes.size.toLong()) { it.write(bytes) }
            }
        }

        private fun dropCrypt(dict: PdfDictionary) {
            when (val f = dict["Filter"]) {
                is PdfName -> if (f.name == "Crypt") {
                    dict.remove("Filter")
                    dict.remove("DecodeParms")
                }
                is PdfArray -> {
                    val i = f.items.indexOfFirst { (it as? PdfName)?.name == "Crypt" }
                    if (i >= 0) {
                        f.items.removeAt(i)
                        (dict["DecodeParms"] as? PdfArray)?.items?.let { if (i < it.size) it.removeAt(i) }
                        if (f.items.isEmpty()) {
                            dict.remove("Filter")
                            dict.remove("DecodeParms")
                        }
                    }
                }
                else -> Unit
            }
        }

        fun map(o: PdfObject): PdfObject = when (o) {
            is PdfReference -> reference(o)
            is PdfArray -> PdfArray(o.items.mapTo(ArrayList(o.items.size)) { map(it) })
            is PdfDictionary -> PdfDictionary(LinkedHashMap<String, PdfObject>(o.entries.size).also { m -> for ((k, v) in o.entries) m[k] = map(v) })
            is PdfStream -> map(o.dictionary)
            else -> o
        }

        private fun reference(r: PdfReference): PdfObject {
            val n = r.number
            if (n in treeNodes) return PdfReference(pagesRoot, 0)
            if (n == catalogNumber) return PdfReference(catalog, 0)
            if (n == encryptNumber) return PdfNull
            numbers[n]?.let { return PdfReference(it, 0) }
            if (doc.getObject(n) == null) return PdfNull
            val number = allocate()
            numbers[n] = number
            queue += n
            return PdfReference(number, 0)
        }

        private companion object {
            val DROPPED = setOf("Parent", "StructParents", "B")
        }
    }
}
