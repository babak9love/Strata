package dev.cluvex.strata.core.res

import com.reandroid.apk.AndroidFrameworks
import com.reandroid.apk.ApkModule
import com.reandroid.arsc.chunk.PackageBlock
import com.reandroid.arsc.chunk.TableBlock
import com.reandroid.arsc.chunk.xml.ResXmlDocument
import com.reandroid.xml.XMLFactory
import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr
import org.xmlpull.v1.XmlPullParserException
import java.io.File
import java.io.IOException
import java.io.StringReader

class AxmlEncodeException(message: String, val line: Int = -1, cause: Throwable? = null) : IOException(message, cause)

object AxmlCodec {
    fun encodeForApk(text: String, apk: File): ByteArray = ApkModule.loadApkFile(apk).use { module ->
        val doc = ResXmlDocument()
        doc.setApkFile(module)
        if (module.hasTableBlock()) {
            val table = module.tableBlock
            linkFramework(table, frameworkApi(module))
            table.pickOne()?.let { doc.setPackageBlock(it) }
        } else {
            standalonePackage()?.let { doc.setPackageBlock(it) }
        }
        parse(doc, text)
    }

    fun encodeStandalone(text: String): ByteArray {
        val doc = ResXmlDocument()
        standalonePackage()?.let { doc.setPackageBlock(it) }
        return parse(doc, text)
    }

    internal fun standalonePackage(): PackageBlock? = runCatching {
        TableBlock().apply { frameworkTable(null)?.let { addFramework(it) } }.newPackage(0x7f, "app")
    }.getOrNull()

    internal fun linkFramework(table: TableBlock, api: Int?) {
        if (table.hasFramework()) return
        frameworkTable(api)?.let { table.addFramework(it) }
    }

    private fun frameworkTable(api: Int?): TableBlock? = runCatching {
        (if (api != null) AndroidFrameworks.getBestMatch(api) else AndroidFrameworks.getLatest())?.tableBlock
    }.getOrNull()

    private fun frameworkApi(module: ApkModule): Int? = runCatching { module.androidFrameworkVersion }.getOrNull()

    private fun parse(doc: ResXmlDocument, text: String): ByteArray {
        try {
            doc.parse(XMLFactory.newPullParser(StringReader(text)))
        } catch (e: XmlPullParserException) {
            val line = e.lineNumber
            val message = (e.message ?: tr(R.string.arsc_invalid_xml)).substringBefore(" (position")
            throw AxmlEncodeException(if (line > 0) tr(R.string.arsc_xml_line, line, message) else message, line, e)
        } catch (e: Exception) {
            throw AxmlEncodeException(e.message ?: e.javaClass.simpleName, cause = e)
        }
        doc.refreshFull()
        return doc.bytes
    }
}
