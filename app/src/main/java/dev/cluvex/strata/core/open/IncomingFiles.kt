package dev.cluvex.strata.core.open

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.system.Os
import java.io.File

object IncomingFiles {
    private const val DATA_COLUMN = "_data"

    fun locate(context: Context, uri: Uri): String? {
        val sources: List<() -> String?> = when (uri.scheme) {
            "file" -> listOf { uri.path }
            "content" -> listOf(
                { documentPath(context, uri) },
                { descriptorPath(context, uri) },
                { dataColumn(context, uri) },
            )
            else -> emptyList()
        }
        var hidden: String? = null
        for (source in sources) {
            val path = runCatching(source).getOrNull()?.let(IncomingPaths::normalize) ?: continue
            val file = File(path)
            if (file.exists()) return canonical(file)
            if (hidden == null) hidden = path
        }
        return hidden
    }

    fun displayName(context: Context, uri: Uri): String? =
        runCatching { column(context, uri, OpenableColumns.DISPLAY_NAME) }.getOrNull() ?: uri.lastPathSegment

    fun size(context: Context, uri: Uri): Long =
        runCatching { column(context, uri, OpenableColumns.SIZE)?.toLongOrNull() }.getOrNull() ?: -1L

    private fun canonical(file: File): String {
        val parent = file.parentFile ?: return file.path
        return runCatching { File(parent.canonicalFile, file.name).path }.getOrDefault(file.path)
    }

    private fun documentPath(context: Context, uri: Uri): String? {
        if (!DocumentsContract.isDocumentUri(context, uri)) return null
        val primary = Environment.getExternalStorageDirectory().absolutePath
        return IncomingPaths.fromDocumentId(uri.authority, DocumentsContract.getDocumentId(uri), primary)
    }

    private fun descriptorPath(context: Context, uri: Uri): String? =
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val path = IncomingPaths.normalize(Os.readlink("/proc/self/fd/${pfd.fd}")) ?: return@use null
            val file = File(path)
            path.takeIf { pfd.statSize < 0 || !file.exists() || file.length() == pfd.statSize }
        }

    private fun dataColumn(context: Context, uri: Uri): String? = column(context, uri, DATA_COLUMN)

    private fun column(context: Context, uri: Uri, name: String): String? =
        context.contentResolver.query(uri, arrayOf(name), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
}
