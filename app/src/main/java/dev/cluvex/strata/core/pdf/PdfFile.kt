package dev.cluvex.strata.core.pdf

import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr
import java.io.Closeable

data class PdfPage(val ref: PdfReference, val dictionary: PdfDictionary)

class PdfFile private constructor(private val source: PdfSource, val version: String, private val password: String) : Closeable {

    private sealed interface Entry {
        data class InFile(val offset: Long, val generation: Int) : Entry
        data class InStream(val stream: Int, val index: Int) : Entry
        data object Free : Entry
    }

    private class ObjectStream(val numbers: IntArray, val offsets: IntArray, val data: ByteArray, val first: Int)

    private class BadOffset : Exception()

    private val entries = HashMap<Int, Entry>()
    private val cache = HashMap<Int, PdfObject>()
    private val objectStreams = HashMap<Int, ObjectStream>()
    private val loading = HashSet<Int>()
    private var repaired = false

    var trailer: PdfDictionary = PdfDictionary()
        private set
    var security: PdfSecurity? = null
        private set
    private var encryptNumber: Int? = null

    val catalogNumber: Int? get() = (trailer["Root"] as? PdfReference)?.number

    val pageTreeNodes = HashSet<Int>()

    fun resolve(obj: PdfObject?): PdfObject? {
        var current = obj
        var hops = 0
        while (current is PdfReference && hops < 32) {
            current = getObject(current.number)
            hops++
        }
        return if (current is PdfReference) null else current
    }

    fun getObject(number: Int): PdfObject? {
        cache[number]?.let { return it.takeUnless { o -> o === PdfNull } }
        if (!loading.add(number)) return null
        try {
            val obj = when (val e = entries[number]) {
                is Entry.InFile -> try {
                    readIndirect(e.offset, number)
                } catch (_: BadOffset) {
                    if (!repaired) {
                        loading.remove(number)
                        repair()
                        return getObject(number)
                    }
                    null
                } catch (_: PdfException) {
                    null
                }
                is Entry.InStream -> runCatching { loadCompressed(number, e.stream, e.index) }.getOrNull()
                Entry.Free, null -> null
            }
            cache[number] = obj ?: PdfNull
            return obj
        } finally {
            loading.remove(number)
        }
    }

    fun streamBytes(stream: PdfStream): ByteArray = when (val d = stream.data) {
        is StreamData.Bytes -> d.bytes
        is StreamData.Range -> {
            val raw = source.readFully(d.offset, d.length.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            val s = security
            if (s != null && d.owner != null) s.decryptStream(raw, d.owner) else raw
        }
    }

    fun decoded(stream: PdfStream): ByteArray = PdfFilters.decode(streamBytes(stream), stream.dictionary, ::resolve)

    fun copyRaw(stream: PdfStream, out: java.io.OutputStream) {
        val d = stream.data as? StreamData.Range
        if (d == null || (security != null && d.owner != null)) {
            out.write(streamBytes(stream))
            return
        }
        val buf = ByteArray(64 * 1024)
        var position = d.offset
        var left = d.length
        while (left > 0) {
            val n = source.read(position, buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n <= 0) break
            out.write(buf, 0, n)
            position += n
            left -= n
        }
    }

    fun rawLength(stream: PdfStream): Long? {
        val d = stream.data
        return when {
            d is StreamData.Bytes -> d.bytes.size.toLong()
            d is StreamData.Range && (security == null || d.owner == null) -> d.length
            else -> null
        }
    }

    fun pages(): List<PdfPage> {
        val catalog = resolve(trailer["Root"]) as? PdfDictionary ?: throw PdfException(tr(R.string.pdf_error_no_catalog))
        val result = ArrayList<PdfPage>()
        val visited = HashSet<Int>()
        pageTreeNodes.clear()

        fun walk(node: PdfObject?, inherited: Map<String, PdfObject>, depth: Int) {
            if (depth > 128) return
            val ref = node as? PdfReference ?: return
            if (!visited.add(ref.number)) return
            val dict = resolve(ref) as? PdfDictionary ?: return
            val kids = resolve(dict["Kids"]) as? PdfArray
            val type = dict.name("Type")
            if (type == "Pages" || (type != "Page" && kids != null)) {
                pageTreeNodes += ref.number
                val next = HashMap(inherited)
                for (key in INHERITABLE) dict[key]?.let { next[key] = it }
                kids?.items?.forEach { walk(it, next, depth + 1) }
            } else {
                val page = dict.copy()
                for ((key, value) in inherited) if (page[key] == null) page[key] = value
                result += PdfPage(ref, page)
            }
        }

        walk(catalog["Pages"], emptyMap(), 0)
        if (result.isEmpty()) {
            for (number in entries.keys.sorted()) {
                val dict = getObject(number) as? PdfDictionary ?: continue
                if (dict.name("Type") == "Page") result += PdfPage(PdfReference(number, 0), dict.copy())
            }
        }
        if (result.isEmpty()) throw PdfException(tr(R.string.pdf_error_no_pages))
        for (page in result) {
            val d = page.dictionary
            if (resolve(d["MediaBox"]) !is PdfArray) d["MediaBox"] = PdfArray(mutableListOf(PdfNumber(0), PdfNumber(0), PdfNumber(612), PdfNumber(792)))
            if (d["Resources"] == null) d["Resources"] = PdfDictionary()
        }
        return result
    }

    override fun close() = source.close()

    private fun lexerAt(position: Long) = PdfLexer(source, position)

    private fun readIndirect(offset: Long, expected: Int?): PdfObject {
        val lexer = lexerAt(offset)
        val n = (lexer.nextToken() as? Token.Num)?.text?.toIntOrNull() ?: throw BadOffset()
        val g = (lexer.nextToken() as? Token.Num)?.text?.toIntOrNull() ?: throw BadOffset()
        val keyword = lexer.nextToken() as? Token.Word
        if (keyword?.text != "obj") throw BadOffset()
        if (expected != null && n != expected) throw BadOffset()
        val ref = PdfReference(n, g)
        val obj = lexer.readObject()
        val save = lexer.position
        val next = lexer.nextToken()
        if (obj is PdfDictionary && next is Token.Word && next.text == "stream") {
            val c = lexer.peek()
            if (c == 13) {
                lexer.position++
                if (lexer.peek() == 10) lexer.position++
            } else if (c == 10) {
                lexer.position++
            }
            val dataStart = lexer.position
            val declared = (resolve(obj["Length"]) as? PdfNumber)?.long
            val length = streamLength(dataStart, declared)
            val decrypt = security != null && n != encryptNumber && obj.name("Type") != "XRef" &&
                !(obj.name("Type") == "Metadata" && security?.encryptMetadata == false) &&
                !identityCrypt(obj)
            return PdfStream(decryptStrings(obj, ref) as PdfDictionary, StreamData.Range(dataStart, length, if (decrypt) ref else null))
        }
        lexer.position = save
        return decryptStrings(obj, ref)
    }

    private fun identityCrypt(dict: PdfDictionary): Boolean {
        val filters = PdfFilters.names(dict, ::resolve)
        if ("Crypt" !in filters) return false
        val params = resolve(dict["DecodeParms"])
        val p = when (params) {
            is PdfDictionary -> params
            is PdfArray -> resolve(params.items.getOrNull(filters.indexOf("Crypt"))) as? PdfDictionary
            else -> null
        }
        return (p?.name("Name") ?: "Identity") == "Identity"
    }

    private fun decryptStrings(obj: PdfObject, ref: PdfReference): PdfObject {
        val s = security ?: return obj
        if (ref.number == encryptNumber) return obj
        return decryptValue(obj, ref, s)
    }

    private fun decryptValue(obj: PdfObject, ref: PdfReference, s: PdfSecurity): PdfObject = when (obj) {
        is PdfString -> PdfString(s.decryptString(obj.bytes, ref), obj.hex)
        is PdfArray -> PdfArray(obj.items.mapTo(ArrayList(obj.items.size)) { decryptValue(it, ref, s) })
        is PdfDictionary -> PdfDictionary(LinkedHashMap<String, PdfObject>(obj.entries.size).also { m ->
            for ((k, v) in obj.entries) m[k] = decryptValue(v, ref, s)
        })
        else -> obj
    }

    private fun streamLength(dataStart: Long, declared: Long?): Long {
        if (declared != null && declared >= 0 && dataStart + declared <= source.size && endstreamAt(dataStart + declared)) return declared
        val found = findEndstream(dataStart) ?: return (declared ?: 0).coerceIn(0, source.size - dataStart)
        var end = found
        if (end > dataStart && byteAt(end - 1) == 10) end--
        if (end > dataStart && byteAt(end - 1) == 13) end--
        return end - dataStart
    }

    private fun byteAt(position: Long): Int {
        val b = ByteArray(1)
        return if (source.read(position, b, 0, 1) == 1) b[0].toInt() and 0xFF else -1
    }

    private fun endstreamAt(position: Long): Boolean {
        val head = source.readFully(position, 32)
        var i = 0
        while (i < head.size && PdfLexer.isSpace(head[i].toInt() and 0xFF)) i++
        return String(head, i, head.size - i, Charsets.ISO_8859_1).startsWith("endstream")
    }

    private fun findEndstream(from: Long): Long? {
        val needle = "endstream".toByteArray(Charsets.ISO_8859_1)
        val chunk = 256 * 1024
        var position = from
        while (position < source.size) {
            val data = source.readFully(position, chunk + needle.size)
            val i = indexOf(data, needle)
            if (i >= 0) return position + i
            if (data.size < chunk + needle.size) break
            position += chunk
        }
        return null
    }

    private fun indexOf(data: ByteArray, needle: ByteArray, from: Int = 0): Int {
        outer@ for (i in from..data.size - needle.size) {
            for (j in needle.indices) if (data[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun loadCompressed(number: Int, streamNumber: Int, index: Int): PdfObject? {
        val os = objectStream(streamNumber) ?: return null
        val i = if (index in os.numbers.indices && os.numbers[index] == number) index else os.numbers.indexOf(number)
        if (i < 0) return null
        val lexer = PdfLexer(BytesSource(os.data), (os.first + os.offsets[i]).toLong())
        return lexer.readObject()
    }

    private fun objectStream(number: Int): ObjectStream? {
        objectStreams[number]?.let { return it }
        val stream = getObject(number) as? PdfStream ?: return null
        val count = (resolve(stream.dictionary["N"]) as? PdfNumber)?.int ?: return null
        val first = (resolve(stream.dictionary["First"]) as? PdfNumber)?.int ?: return null
        val data = decoded(stream)
        val lexer = PdfLexer(BytesSource(data))
        val numbers = IntArray(count)
        val offsets = IntArray(count)
        for (k in 0 until count) {
            numbers[k] = (lexer.nextToken() as? Token.Num)?.text?.toIntOrNull() ?: break
            offsets[k] = (lexer.nextToken() as? Token.Num)?.text?.toIntOrNull() ?: break
        }
        return ObjectStream(numbers, offsets, data, first).also { objectStreams[number] = it }
    }

    private fun readXref() {
        var offset: Long? = findStartXref() ?: throw PdfException("No startxref")
        val seen = HashSet<Long>()
        var first = true
        while (offset != null && offset in 0 until source.size && seen.add(offset)) {
            val section = readXrefSection(offset)
            if (first) {
                trailer = section
                first = false
            } else {
                for ((k, v) in section.entries) if (k != "Prev" && k != "XRefStm" && trailer[k] == null) trailer[k] = v
            }
            (section["XRefStm"] as? PdfNumber)?.long?.let { stm -> if (seen.add(stm)) runCatching { readXrefStream(stm) } }
            offset = (section["Prev"] as? PdfNumber)?.long
        }
        if (first) throw PdfException("No cross-reference table")
        trailer.remove("Prev")
        trailer.remove("XRefStm")
    }

    private fun findStartXref(): Long? {
        val tail = minOf(source.size, 4096L)
        val data = source.readFully(source.size - tail, tail.toInt())
        val text = String(data, Charsets.ISO_8859_1)
        val i = text.lastIndexOf("startxref")
        if (i < 0) return null
        val lexer = PdfLexer(BytesSource(data), (i + "startxref".length).toLong())
        return (lexer.nextToken() as? Token.Num)?.text?.toLongOrNull()
    }

    private fun readXrefSection(offset: Long): PdfDictionary {
        val lexer = lexerAt(offset)
        val save = lexer.position
        val t = lexer.nextToken()
        if (t is Token.Word && t.text == "xref") return readXrefTable(lexer)
        lexer.position = save
        return readXrefStream(offset)
    }

    private fun readXrefTable(lexer: PdfLexer): PdfDictionary {
        while (true) {
            val t = lexer.nextToken()
            if (t is Token.Word && t.text == "trailer") break
            val start = (t as? Token.Num)?.text?.toIntOrNull() ?: throw PdfException("Broken cross-reference table")
            val count = (lexer.nextToken() as? Token.Num)?.text?.toIntOrNull() ?: throw PdfException("Broken cross-reference table")
            for (i in 0 until count) {
                val off = (lexer.nextToken() as? Token.Num)?.text?.toLongOrNull() ?: throw PdfException("Broken cross-reference table")
                val gen = (lexer.nextToken() as? Token.Num)?.text?.toIntOrNull() ?: throw PdfException("Broken cross-reference table")
                val kind = (lexer.nextToken() as? Token.Word)?.text ?: throw PdfException("Broken cross-reference table")
                val number = start + i
                if (number == 0) continue
                entries.putIfAbsent(number, if (kind == "n" && off > 0) Entry.InFile(off, gen) else Entry.Free)
            }
        }
        return lexer.readObject() as? PdfDictionary ?: throw PdfException("Broken trailer")
    }

    private fun readXrefStream(offset: Long): PdfDictionary {
        val stream = readIndirect(offset, null) as? PdfStream ?: throw PdfException("Broken cross-reference stream")
        val dict = stream.dictionary
        val widths = (dict["W"] as? PdfArray)?.items?.map { (it as? PdfNumber)?.int ?: 0 } ?: throw PdfException("Broken cross-reference stream")
        if (widths.size < 3 || widths.any { it < 0 || it > 8 }) throw PdfException("Broken cross-reference stream")
        val size = (dict["Size"] as? PdfNumber)?.int ?: 0
        val index = (dict["Index"] as? PdfArray)?.items?.mapNotNull { (it as? PdfNumber)?.int } ?: listOf(0, size)
        val data = PdfFilters.decode(streamBytes(stream), dict, ::resolve)
        val rowSize = widths.sum()
        var p = 0
        for (pair in index.chunked(2)) {
            if (pair.size < 2) break
            for (i in 0 until pair[1]) {
                if (p + rowSize > data.size) break
                val type = if (widths[0] == 0) 1L else field(data, p, widths[0])
                p += widths[0]
                val f2 = field(data, p, widths[1])
                p += widths[1]
                val f3 = field(data, p, widths[2])
                p += widths[2]
                val number = pair[0] + i
                if (number == 0) continue
                when (type) {
                    0L -> entries.putIfAbsent(number, Entry.Free)
                    1L -> entries.putIfAbsent(number, Entry.InFile(f2, f3.toInt()))
                    2L -> entries.putIfAbsent(number, Entry.InStream(f2.toInt(), f3.toInt()))
                }
            }
        }
        return dict.copy()
    }

    private fun field(data: ByteArray, at: Int, width: Int): Long {
        var v = 0L
        for (i in 0 until width) v = (v shl 8) or (data[at + i].toLong() and 0xFF)
        return v
    }

    private fun repair() {
        repaired = true
        entries.clear()
        cache.clear()
        objectStreams.clear()
        val objectHeader = Regex("(?<![0-9])(\\d{1,10})[ \\t\\r\\n\\u000c\\u0000]+(\\d{1,5})[ \\t\\r\\n\\u000c\\u0000]+obj(?![A-Za-z])")
        val trailers = ArrayList<Long>()
        val chunk = 4 * 1024 * 1024
        val overlap = 64
        var base = 0L
        while (base < source.size) {
            val data = source.readFully(base, chunk + overlap)
            val text = String(data, Charsets.ISO_8859_1)
            val limit = minOf(chunk, data.size)
            for (m in objectHeader.findAll(text)) {
                if (m.range.first >= limit) break
                val number = m.groupValues[1].toIntOrNull() ?: continue
                val gen = m.groupValues[2].toIntOrNull() ?: continue
                if (number > 0) entries[number] = Entry.InFile(base + m.range.first, gen)
            }
            var t = text.indexOf("trailer")
            while (t in 0 until limit) {
                trailers += base + t
                t = text.indexOf("trailer", t + 1)
            }
            if (data.size < chunk + overlap) break
            base += chunk
        }
        val repairedTrailer = PdfDictionary()
        for (position in trailers) {
            val lexer = lexerAt(position + "trailer".length)
            val dict = runCatching { lexer.readObject() as? PdfDictionary }.getOrNull() ?: continue
            for ((k, v) in dict.entries) repairedTrailer[k] = v
        }
        val streams = entries.filterValues { it is Entry.InFile }.keys.sorted()
        val packed = ArrayList<Int>()
        for (number in streams) {
            val stream = runCatching { getObject(number) }.getOrNull() as? PdfStream ?: continue
            when (stream.dictionary.name("Type")) {
                "ObjStm" -> packed += number
                "XRef" -> for (key in listOf("Root", "Info", "ID", "Encrypt")) {
                    stream.dictionary[key]?.let { repairedTrailer.entries.putIfAbsent(key, it) }
                }
            }
        }
        trailer = repairedTrailer
        if (security == null && repairedTrailer["Encrypt"] != null) setupSecurity()
        for (number in packed) {
            val os = runCatching { objectStream(number) }.getOrNull() ?: continue
            os.numbers.forEachIndexed { i, n -> if (n > 0) entries.putIfAbsent(n, Entry.InStream(number, i)) }
        }
        if (resolve(repairedTrailer["Root"]) !is PdfDictionary) {
            repairedTrailer.remove("Root")
            for (number in entries.keys.sortedDescending()) {
                val dict = runCatching { getObject(number) }.getOrNull() as? PdfDictionary ?: continue
                if (dict.name("Type") == "Catalog") {
                    repairedTrailer["Root"] = PdfReference(number, 0)
                    break
                }
            }
        }
        repairedTrailer.remove("Prev")
        repairedTrailer.remove("XRefStm")
        trailer = repairedTrailer
        cache.clear()
    }

    private fun setupSecurity() {
        security = null
        val enc = trailer["Encrypt"] ?: return
        encryptNumber = (enc as? PdfReference)?.number
        cache.remove(encryptNumber ?: -1)
        val dict = resolve(enc) as? PdfDictionary ?: throw PdfException(tr(R.string.pdf_error_encryption_dictionary))
        val id = ((resolve(trailer["ID"]) as? PdfArray)?.items?.firstOrNull() as? PdfString)?.bytes ?: ByteArray(0)
        security = PdfSecurity.open(dict, id, password)
        cache.clear()
        objectStreams.clear()
    }

    companion object {
        private val INHERITABLE = listOf("Resources", "MediaBox", "CropBox", "Rotate")

        fun header(source: PdfSource): String? {
            val head = String(source.readFully(0, minOf(source.size, 1024L).toInt()), Charsets.ISO_8859_1)
            val i = head.indexOf("%PDF-")
            if (i < 0) return null
            return Regex("^\\d\\.\\d").find(head.substring(i + 5))?.value ?: "1.4"
        }

        fun open(source: PdfSource, password: String = ""): PdfFile {
            val version = header(source) ?: throw PdfException(tr(R.string.pdf_error_not_pdf))
            val file = PdfFile(source, version, password)
            val read = try {
                file.readXref()
                true
            } catch (_: Exception) {
                false
            }
            if (read) {
                try {
                    file.setupSecurity()
                } catch (e: PdfPasswordException) {
                    throw e
                } catch (_: Exception) {
                    file.repair()
                }
                if (file.resolve(file.trailer["Root"]) !is PdfDictionary && !file.repaired) file.repair()
            } else {
                file.repair()
            }
            if (file.trailer["Encrypt"] != null && file.security == null) file.setupSecurity()
            if (file.resolve(file.trailer["Root"]) !is PdfDictionary) throw PdfException(tr(R.string.pdf_error_damaged))
            return file
        }
    }
}
