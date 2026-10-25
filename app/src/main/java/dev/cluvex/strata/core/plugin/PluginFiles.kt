package dev.cluvex.strata.core.plugin

import dev.cluvex.strata.R
import dev.cluvex.strata.core.fs.FileAccess
import dev.cluvex.strata.core.fs.FsUtil
import dev.cluvex.strata.core.fs.LocalFileAccess
import dev.cluvex.strata.core.i18n.tr
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

object PluginFiles {
    fun copyCapped(input: InputStream, output: OutputStream, max: Long): Long {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) return total
            total += n
            if (total > max) throw IOException(tr(R.string.plugin_file_too_large, max shr 20))
            output.write(buf, 0, n)
        }
    }

    fun save(result: File, original: File, suggestedName: String?, replace: Boolean, access: FileAccess = LocalFileAccess): File {
        val target = if (replace) {
            original
        } else {
            val dir = original.parentFile ?: throw IOException(tr(R.string.plugin_no_folder))
            FsUtil.uniqueSibling(access.child(dir, suggestedName ?: original.name), isDirectory = false, access = access)
        }
        result.inputStream().use { input -> access.openOutput(target).use { input.copyTo(it) } }
        return target
    }
}
