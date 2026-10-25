package dev.cluvex.strata.feature.pdf

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import dev.cluvex.strata.core.fs.FsUtil
import dev.cluvex.strata.core.model.FileItem
import dev.cluvex.strata.core.util.IntentUtils
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

object ReaderActions {

    fun outputDir(source: ReaderSource): File {
        val parent = source.path?.let { File(it).parentFile }
        if (parent != null && parent.canWrite()) return parent
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).apply { mkdirs() }
    }

    fun baseName(title: String): String = title.substringBeforeLast('.', title).ifBlank { "document" }

    fun freeFile(dir: File, name: String): File = File(dir, FsUtil.uniqueName(name, false) { File(dir, it).exists() })

    fun saveText(source: ReaderSource, title: String, text: String): File {
        val out = freeFile(outputDir(source), baseName(title) + ".txt")
        out.writeText(text, Charsets.UTF_8)
        return out
    }

    fun savePng(source: ReaderSource, title: String, page: Int, bitmap: Bitmap): File {
        val out = freeFile(outputDir(source), "${baseName(title)}-p${page + 1}.png")
        FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return out
    }

    fun share(context: Context, source: ReaderSource) {
        val path = source.path
        if (path != null) {
            IntentUtils.share(context, listOf(FileItem.of(File(path))))
            return
        }
        val uri = source.uri ?: return
        val send = Intent(Intent.ACTION_SEND).setType("application/pdf").putExtra(Intent.EXTRA_STREAM, Uri.parse(uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun shareText(context: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun openLink(context: Context, uri: String): Boolean = runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.isSuccess

    fun safeLink(uri: String): Boolean {
        val scheme = uri.substringBefore(':', "").lowercase()
        return scheme == "http" || scheme == "https" || scheme == "mailto" || scheme == "tel"
    }

    fun print(context: Context, source: ReaderSource, title: String) {
        val manager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return
        manager.print(title, Adapter(context.applicationContext, source, title), PrintAttributes.Builder().build())
    }

    private class Adapter(private val context: Context, private val source: ReaderSource, private val title: String) : PrintDocumentAdapter() {
        override fun onLayout(old: PrintAttributes?, new: PrintAttributes?, cancel: CancellationSignal?, callback: LayoutResultCallback, extras: Bundle?) {
            if (cancel?.isCanceled == true) {
                callback.onLayoutCancelled()
                return
            }
            callback.onLayoutFinished(PrintDocumentInfo.Builder(title).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), true)
        }

        override fun onWrite(pages: Array<out PageRange>?, destination: ParcelFileDescriptor, cancel: CancellationSignal?, callback: WriteResultCallback) {
            try {
                open()?.use { input -> FileOutputStream(destination.fileDescriptor).use { input.copyTo(it) } } ?: throw IllegalStateException()
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (e: Exception) {
                callback.onWriteFailed(e.message)
            }
        }

        private fun open(): InputStream? = when {
            source.path != null -> File(source.path).takeIf { it.canRead() }?.inputStream()
            source.uri != null -> context.contentResolver.openInputStream(Uri.parse(source.uri))
            else -> null
        }
    }
}
