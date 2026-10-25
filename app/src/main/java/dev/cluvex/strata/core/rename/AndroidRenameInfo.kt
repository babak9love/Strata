package dev.cluvex.strata.core.rename

import android.content.Context
import android.media.ExifInterface
import android.os.Build
import dev.cluvex.strata.core.model.FileItem

class AndroidRenameInfo(private val context: Context) : RenameInfo {

    private val exif = HashMap<String, Long?>()
    private val apks = HashMap<String, ApkInfo?>()

    @Synchronized
    override fun exifTime(item: FileItem): Long? {
        if (item.isDirectory) return null
        if (!exif.containsKey(item.path)) exif[item.path] = readExif(item.path)
        return exif[item.path]
    }

    @Synchronized
    override fun apk(item: FileItem): ApkInfo? {
        if (item.isDirectory || item.extension != "apk") return null
        if (!apks.containsKey(item.path)) apks[item.path] = readApk(item.path)
        return apks[item.path]
    }

    private fun readExif(path: String): Long? = runCatching {
        val tags = ExifInterface(path)
        val raw = tags.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: tags.getAttribute(ExifInterface.TAG_DATETIME)
        raw?.let { ExifDate.parse(it) }
    }.getOrNull()

    private fun readApk(path: String): ApkInfo? = runCatching {
        val pm = context.packageManager
        val info = pm.getPackageArchiveInfo(path, 0) ?: return null
        val app = info.applicationInfo ?: return null
        app.sourceDir = path
        app.publicSourceDir = path
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        ApkInfo(app.loadLabel(pm).toString(), info.packageName, info.versionName.orEmpty(), code)
    }.getOrNull()
}
