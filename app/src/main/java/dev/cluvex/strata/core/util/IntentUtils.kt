package dev.cluvex.strata.core.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import dev.cluvex.strata.core.model.FileItem
import java.io.File

object IntentUtils {
    fun mayShare(context: Context, file: File): Boolean {
        val private = listOfNotNull(File(context.applicationInfo.dataDir), context.createDeviceProtectedStorageContext().dataDir)
        return SharePolicy.mayShare(file, private, listOf(context.cacheDir))
    }

    private fun uriFor(context: Context, file: File): Uri? =
        if (!mayShare(context, file)) null else runCatching { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file) }.getOrNull()

    fun contentUri(context: Context, item: FileItem): Uri? = uriFor(context, File(item.path))

    fun openIn(context: Context, item: FileItem, packageName: String, activity: String, mime: String = mimeType(item)): Boolean {
        val uri = uriFor(context, File(item.path)) ?: return false
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            component = ComponentName(packageName, activity)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    fun mimeType(item: FileItem): String {
        val ext = item.extension
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }

    fun openWith(context: Context, item: FileItem) {
        val uri = uriFor(context, File(item.path)) ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType(item))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, item.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun share(context: Context, items: List<FileItem>) {
        if (items.isEmpty()) return
        val uris = ArrayList(items.filter { !it.isDirectory }.mapNotNull { uriFor(context, File(it.path)) })
        if (uris.isEmpty()) return
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_STREAM, uris.first())
                type = mimeType(items.first())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                type = "*/*"
            }
        }.apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
