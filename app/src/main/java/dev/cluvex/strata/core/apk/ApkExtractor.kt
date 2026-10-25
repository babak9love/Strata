package dev.cluvex.strata.core.apk

import dev.cluvex.strata.R
import dev.cluvex.strata.core.fs.FsUtil
import dev.cluvex.strata.core.i18n.tr
import java.io.File
import java.io.IOException
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ApkExtractor {
    const val DEFAULT_PATTERN = "{A}_{V}"

    fun fileName(pattern: String, app: InstalledApp): String {
        val base = pattern.ifBlank { DEFAULT_PATTERN }
            .replace("{A}", app.label)
            .replace("{P}", app.packageName)
            .replace("{V}", app.versionName.ifEmpty { app.versionCode.toString() })
            .replace("{C}", app.versionCode.toString())
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F\\u007F]"), "_")
            .trim()
            .trimEnd('.')
            .ifEmpty { app.packageName }
        return base.take(180) + if (app.isSplit) ".apks" else ".apk"
    }

    fun extract(app: InstalledApp, destDir: File, pattern: String = DEFAULT_PATTERN): File {
        if (!destDir.isDirectory && !destDir.mkdirs()) throw IOException(tr(R.string.apk_error_cannot_create, destDir.path))
        val target = FsUtil.uniqueSibling(File(destDir, fileName(pattern, app)), isDirectory = false)
        try {
            if (app.isSplit) bundle(app, target) else File(app.sourceDir).copyTo(target)
        } catch (e: Exception) {
            target.delete()
            throw e
        }
        return target
    }

    private fun bundle(app: InstalledApp, target: File) {
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            val used = HashSet<String>()
            for (path in app.apkPaths) {
                val file = File(path)
                var name = file.name
                var i = 1
                while (!used.add(name)) name = "${file.nameWithoutExtension}_${i++}.apk"
                val crc = CRC32()
                file.inputStream().use { input ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        crc.update(buf, 0, n)
                    }
                }
                val entry = ZipEntry(name).apply {
                    method = ZipEntry.STORED
                    size = file.length()
                    compressedSize = file.length()
                    this.crc = crc.value
                }
                zip.putNextEntry(entry)
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }
}
