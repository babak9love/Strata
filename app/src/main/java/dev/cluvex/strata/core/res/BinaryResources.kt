package dev.cluvex.strata.core.res

import com.reandroid.apk.ApkModule
import com.reandroid.arsc.chunk.xml.ResXmlDocument
import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr
import java.io.File

object BinaryResources {
    private val AXML_MAGIC = byteArrayOf(0x03, 0x00, 0x08, 0x00)

    private val ARSC_MAGIC = byteArrayOf(0x02, 0x00, 0x0C, 0x00)

    fun isBinaryXml(bytes: ByteArray): Boolean = bytes.size >= 4 && bytes.copyOf(4).contentEquals(AXML_MAGIC)

    fun isBinaryXml(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val head = ByteArray(4)
            var off = 0
            while (off < 4) {
                val n = input.read(head, off, 4 - off)
                if (n < 0) break
                off += n
            }
            off == 4 && head.contentEquals(AXML_MAGIC)
        }
    }.getOrDefault(false)

    fun isResourceTable(bytes: ByteArray): Boolean = bytes.size >= 4 && bytes.copyOf(4).contentEquals(ARSC_MAGIC)

    fun decodeXml(file: File): String = file.inputStream().use { decodeStream(it) }

    fun decodeXml(bytes: ByteArray): String = bytes.inputStream().use { decodeStream(it) }

    private fun decodeStream(input: java.io.InputStream): String {
        val doc = ResXmlDocument()
        doc.readBytes(input)
        AxmlCodec.standalonePackage()?.let { doc.setPackageBlock(it) }
        return doc.serializeToXml()
    }

    fun decodeXmlInApk(apk: File, entryName: String): String {
        return runCatching {
            ApkModule.loadApkFile(apk).use { module ->
                val source = module.getInputSource(entryName) ?: return@use null
                val bytes = source.openStream().use { it.readBytes() }
                val doc = ResXmlDocument()
                bytes.inputStream().use { doc.readBytes(it) }
                doc.setApkFile(module)
                if (module.hasTableBlock()) {
                    val table = module.tableBlock
                    AxmlCodec.linkFramework(table, runCatching { module.androidFrameworkVersion }.getOrNull())
                    table.pickOne()?.let { doc.setPackageBlock(it) }
                } else {
                    AxmlCodec.standalonePackage()?.let { doc.setPackageBlock(it) }
                }
                doc.serializeToXml()
            }
        }.getOrNull() ?: decodeXmlFromApkEntryRaw(apk, entryName)
    }

    private fun decodeXmlFromApkEntryRaw(apk: File, entryName: String): String {
        java.util.zip.ZipFile(apk).use { zip ->
            val entry = zip.getEntry(entryName) ?: throw java.io.IOException(tr(R.string.arsc_entry_not_found, entryName))
            return decodeXml(zip.getInputStream(entry).readBytes())
        }
    }

    fun dumpResources(apkOrArsc: File): String {
        val module = ApkModule.loadApkFile(apkOrArsc)
        val table = if (module.hasTableBlock()) module.tableBlock else return tr(R.string.arsc_no_table)
        return buildString {
            for (pkg in table.listPackages()) {
                append("package ").append(pkg.name).append(" (0x").append(Integer.toHexString(pkg.id)).append(")\n")
                val resources = pkg.resources
                var count = 0
                while (resources.hasNext()) {
                    val r = resources.next()
                    append("  ").append(r.hexId).append("  ").append(r.type).append('/').append(r.name).append('\n')
                    count++
                }
                append("  — ").append(count).append(" resources\n\n")
            }
        }.trimEnd().ifEmpty { tr(R.string.arsc_no_resources) }
    }

    fun dumpResourcesFromArsc(arsc: File): String {
        val table = com.reandroid.arsc.chunk.TableBlock()
        arsc.inputStream().use { table.readBytes(it) }
        return buildString {
            for (pkg in table.listPackages()) {
                append("package ").append(pkg.name).append(" (0x").append(Integer.toHexString(pkg.id)).append(")\n")
                val resources = pkg.resources
                while (resources.hasNext()) {
                    val r = resources.next()
                    append("  ").append(r.hexId).append("  ").append(r.type).append('/').append(r.name).append('\n')
                }
                append('\n')
            }
        }.trimEnd().ifEmpty { tr(R.string.arsc_no_resources) }
    }
}
