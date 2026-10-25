package dev.cluvex.strata.core.res

import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.coder.ValueCoder
import com.reandroid.arsc.model.ResourceEntry
import com.reandroid.arsc.value.Entry
import com.reandroid.arsc.value.ValueType
import dev.cluvex.strata.R
import dev.cluvex.strata.core.archive.ZipEditor
import dev.cluvex.strata.core.build.BuildListener
import dev.cluvex.strata.core.build.BuildRun
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.i18n.trPlural
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipFile

data class ArscValue(
    val qualifiers: String,
    val valueType: String,
    val text: String,
    val editable: Boolean,
)

data class ArscResource(val id: Int, val type: String, val name: String, val values: List<ArscValue>) {
    val hexId: String get() = String.format(Locale.ROOT, "0x%08x", id)

    val preview: ArscValue? get() = values.firstOrNull { it.qualifiers.isEmpty() } ?: values.firstOrNull()
}

data class ArscTypeCount(val type: String, val count: Int)

class ArscDocument private constructor(private val table: TableBlock) {
    var dirty = false
        private set

    private var byId: LinkedHashMap<Int, ArscResource>? = null

    val packageNames: List<String> get() = table.listPackages().map { it.name }

    fun types(): List<ArscTypeCount> =
        index().values.groupingBy { it.type }.eachCount().map { (t, n) -> ArscTypeCount(t, n) }

    fun resources(type: String, query: String = ""): List<ArscResource> =
        index().values.filter { it.type == type && matches(it, query) }

    fun search(query: String): List<ArscResource> =
        if (query.isBlank()) emptyList() else index().values.filter { matches(it, query) }

    fun resource(id: Int): ArscResource? = index()[id]

    fun setValue(id: Int, qualifiers: String, text: String): Result<ArscResource> = runCatching {
        val res = table.getResource(id) ?: throw IllegalArgumentException(tr(R.string.arsc_no_resource, String.format(Locale.ROOT, "0x%08x", id)))
        val entry = entries(res).firstOrNull { (it.resConfig?.qualifiers ?: "") == qualifiers }
            ?: throw IllegalArgumentException(tr(R.string.arsc_no_value_for_config, displayQualifiers(qualifiers)))
        require(!entry.isComplex) { tr(R.string.arsc_complex_not_editable) }
        val type = entry.valueType
        val t = text.trim()
        when {
            type == ValueType.STRING -> entry.setValueAsString(text)
            t == "@null" -> entry.setValueAsRaw(ValueType.NULL, 0)
            t.startsWith("@") || t.startsWith("?") -> {
                val r = encodeReference(t) ?: throw IllegalArgumentException(tr(R.string.arsc_unknown_reference, t))
                entry.setValueAsRaw(r.first, r.second)
            }
            type == ValueType.BOOLEAN -> entry.setValueAsBoolean(
                when (t.lowercase()) {
                    "true" -> true
                    "false" -> false
                    else -> throw IllegalArgumentException(tr(R.string.arsc_bool_value))
                },
            )
            else -> {
                val r = (if (type != null) ValueCoder.encode(t, type) else null) ?: ValueCoder.encode(t)
                if (r == null || r.isError) throw IllegalArgumentException(tr(R.string.arsc_invalid_value, t, label(type)))
                entry.setValueAsRaw(r.valueType, r.value)
            }
        }
        dirty = true
        val updated = build(res, packageOf(id))
        index()[id] = updated
        updated
    }

    fun encode(): ByteArray {
        table.refreshFull()
        return table.bytes.also { dirty = false }
    }

    fun writeInto(zip: File, entry: String = RESOURCES_ARSC, tempDir: File, log: BuildListener = BuildListener.NONE) {
        val tmp = File.createTempFile("arsc", ".tmp", tempDir.apply { mkdirs() })
        try {
            log.step(encodeStep(entry))
            tmp.writeBytes(encode())
            log.info(BuildRun.size(tmp.length()))
            log.step(writeStep(zip))
            ZipEditor.put(zip, entry, tmp)
            log.info(tr(R.string.arsc_stored_info, zip.name, BuildRun.size(zip.length())))
        } finally {
            tmp.delete()
        }
    }

    private fun index(): LinkedHashMap<Int, ArscResource> = byId ?: LinkedHashMap<Int, ArscResource>().also { map ->
        for (pkg in table.listPackages()) {
            val it = pkg.resources
            while (it.hasNext()) {
                val res = it.next() ?: continue
                map[res.resourceId] = build(res, pkg)
            }
        }
        byId = map
    }

    private fun build(res: ResourceEntry, pkg: PackageBlock?): ArscResource =
        ArscResource(res.resourceId, res.type ?: "", res.name ?: "", entries(res).map { valueOf(it, pkg) })

    private fun entries(res: ResourceEntry): List<Entry> {
        val out = ArrayList<Entry>()
        val it = res.iterator()
        while (it.hasNext()) {
            val e = it.next() ?: continue
            if (!e.isNull) out += e
        }
        return out
    }

    private fun valueOf(e: Entry, pkg: PackageBlock?): ArscValue {
        val q = e.resConfig?.qualifiers ?: ""
        if (e.isComplex) {
            var n = 0
            val values = e.allValues()
            while (values.hasNext()) { values.next(); n++ }
            return ArscValue(q, "COMPLEX", trPlural(R.plurals.arsc_complex_items, n, e.typeName ?: "", n), editable = false)
        }
        val type = e.valueType ?: return ArscValue(q, "NULL", "@null", editable = true)
        val data = e.resValue?.data ?: 0
        val text = when (type) {
            ValueType.STRING -> e.valueAsString ?: ""
            ValueType.NULL -> "@null"
            ValueType.REFERENCE, ValueType.ATTRIBUTE, ValueType.DYNAMIC_REFERENCE, ValueType.DYNAMIC_ATTRIBUTE ->
                (if (pkg != null) runCatching { ValueCoder.decodeReference(pkg, type, data) }.getOrNull() else null)
                    ?: String.format(Locale.ROOT, "@0x%08x", data)
            else -> runCatching { ValueCoder.decode(type, data) }.getOrNull() ?: data.toString()
        }
        return ArscValue(q, type.name, text, editable = true)
    }

    private fun packageOf(id: Int): PackageBlock? = table.getPackageBlockById(id ushr 24) ?: table.pickOne()

    private fun encodeReference(text: String): Pair<ValueType, Int>? {
        Regex("^[@?]0x([0-9a-fA-F]{1,8})$").find(text)?.let { m ->
            val id = m.groupValues[1].toLong(16).toInt()
            return (if (text.startsWith("?")) ValueType.ATTRIBUTE else ValueType.REFERENCE) to id
        }
        val r = runCatching { ValueCoder.encodeReference(table, text) }.getOrNull() ?: return null
        return if (r.isError) null else r.valueType to r.value
    }

    private fun matches(r: ArscResource, query: String): Boolean {
        if (query.isBlank()) return true
        val q = query.trim()
        return r.name.contains(q, ignoreCase = true) || r.hexId.contains(q, ignoreCase = true) ||
            r.values.any { it.text.contains(q, ignoreCase = true) }
    }

    private fun label(type: ValueType?): String = when (type) {
        ValueType.COLOR_ARGB8, ValueType.COLOR_RGB8, ValueType.COLOR_ARGB4, ValueType.COLOR_RGB4 -> tr(R.string.arsc_kind_color)
        ValueType.DIMENSION -> tr(R.string.arsc_kind_dimension)
        ValueType.FRACTION -> tr(R.string.arsc_kind_fraction)
        ValueType.FLOAT -> tr(R.string.arsc_kind_number)
        ValueType.DEC, ValueType.HEX -> tr(R.string.arsc_kind_integer)
        else -> tr(R.string.arsc_kind_value)
    }

    companion object {
        const val RESOURCES_ARSC = "resources.arsc"

        fun read(bytes: ByteArray): ArscDocument {
            val table = TableBlock()
            bytes.inputStream().use { table.readBytes(it) }
            AxmlCodec.linkFramework(table, null)
            return ArscDocument(table)
        }

        fun read(file: File): ArscDocument = read(file.readBytes())

        fun readFromZip(zip: File, entry: String = RESOURCES_ARSC): ArscDocument = ZipFile(zip).use { z ->
            val e = z.getEntry(entry) ?: throw IOException(tr(R.string.arsc_entry_not_found_in, entry, zip.name))
            read(z.getInputStream(e).use { it.readBytes() })
        }

        fun displayQualifiers(qualifiers: String): String = qualifiers.removePrefix("-").ifEmpty { tr(R.string.arsc_default_config) }

        fun encodeStep(entry: String): String = tr(R.string.arsc_step_encode, entry)

        fun writeStep(zip: File): String = if (zip.name.endsWith(".apk", ignoreCase = true)) tr(R.string.dex_step_write_apk) else tr(R.string.build_step_write_into, zip.name)
    }
}
