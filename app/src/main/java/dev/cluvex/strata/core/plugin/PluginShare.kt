package dev.cluvex.strata.core.plugin

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import dev.cluvex.strata.R
import dev.cluvex.strata.core.fs.FsUtil
import dev.cluvex.strata.core.i18n.tr
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

class PluginShareProvider : FileProvider()

class StagedFile(val uri: Uri, val file: File, internal val dir: File)

class PluginShare(private val context: Context) {

    private val shareRoot = File(context.cacheDir, "plugin-share")
    private val resultRoot = File(context.cacheDir, "plugin-result")

    fun stage(input: InputStream, name: String): StagedFile {
        val dir = File(shareRoot, UUID.randomUUID().toString()).apply { mkdirs() }
        val file = File(dir, name)
        input.use { i -> file.outputStream().use { i.copyTo(it) } }
        return StagedFile(FileProvider.getUriForFile(context, authority(context), file), file, dir)
    }

    fun release(staged: StagedFile) {
        runCatching { context.revokeUriPermission(staged.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        FsUtil.deleteRecursively(staged.dir)
    }

    fun receive(uri: Uri, name: String): File {
        val file = File(File(resultRoot, UUID.randomUUID().toString()).apply { mkdirs() }, name)
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException(tr(R.string.plugin_file_cant_open))
        input.use { i -> file.outputStream().use { o -> PluginFiles.copyCapped(i, o, PluginProtocol.MAX_RESULT_BYTES) } }
        return file
    }

    fun receiveText(text: String, name: String): File =
        File(File(resultRoot, UUID.randomUUID().toString()).apply { mkdirs() }, name).apply { writeText(text) }

    fun discard(result: File) {
        result.parentFile?.let { FsUtil.deleteRecursively(it) }
    }

    fun cleanUp() {
        FsUtil.deleteRecursively(shareRoot)
        FsUtil.deleteRecursively(resultRoot)
    }

    companion object {
        fun authority(context: Context) = "${context.packageName}.pluginshare"
    }
}
