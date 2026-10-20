package dev.cluvex.strata.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.graphics.drawable.toBitmap
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.request.Options
import java.io.File
import java.util.zip.ZipFile

data class ApkIcon(val path: String, val modified: Long)

object ApkIcons {

    const val SIZE = 192

    /** Split-APK bundles (.apks / .xapk / .apkm): zip files that carry the real app inside. */
    val BUNDLE_EXTENSIONS = setOf("apks", "xapk", "apkm")

    private const val MAX_EXTRACT_BYTES = 200L * 1024 * 1024

    fun load(context: Context, path: String, size: Int = SIZE): Bitmap? {
        if (File(path).extension.lowercase() in BUNDLE_EXTENSIONS) return loadFromBundle(context, path, size)
        return loadFromApk(context, path, size)
    }

    /** The app icon of a bundle: its own icon.png when it has one, otherwise the icon of the base APK inside it. */
    private fun loadFromBundle(context: Context, path: String, size: Int): Bitmap? {
        val zip = runCatching { ZipFile(path) }.getOrNull() ?: return null
        zip.use { z ->
            val entries = z.entries().asSequence().filter { !it.isDirectory }.toList()
            // .xapk / .apkm ship the launcher icon next to the manifest.
            entries.firstOrNull { it.name.equals("icon.png", ignoreCase = true) }?.let { e ->
                val bmp = runCatching { z.getInputStream(e).use { BitmapFactory.decodeStream(it) } }.getOrNull()
                if (bmp != null) return Bitmap.createScaledBitmap(bmp, size, size, true)
            }
            val apks = entries.filter { it.name.endsWith(".apk", ignoreCase = true) }
            // base.apk (.apks / .apkm), splits/base-master.apk, or the biggest APK that is not a config/split part (.xapk).
            val base = apks.firstOrNull { it.name.substringAfterLast('/').equals("base.apk", ignoreCase = true) }
                ?: apks.firstOrNull { it.name.substringAfterLast('/').equals("base-master.apk", ignoreCase = true) }
                ?: apks.filter { val n = it.name.substringAfterLast('/').lowercase(); !n.startsWith("config.") && !n.startsWith("split_") && !n.contains("-config.") && !n.contains("-xxhdpi") }
                    .maxByOrNull { it.size }
                ?: apks.maxByOrNull { it.size }
                ?: return null
            if (base.size > MAX_EXTRACT_BYTES) return null
            val tmp = File.createTempFile("bundle-icon-", ".apk", context.cacheDir)
            return try {
                z.getInputStream(base).use { input -> tmp.outputStream().use { input.copyTo(it) } }
                loadFromApk(context, tmp.path, size)
            } catch (_: Exception) {
                null
            } finally {
                tmp.delete()
            }
        }
    }

    private fun loadFromApk(context: Context, path: String, size: Int): Bitmap? {
        val pm = context.packageManager
        val info = runCatching { pm.getPackageArchiveInfo(path, 0) }.getOrNull() ?: return null
        val app = info.applicationInfo ?: return null
        app.sourceDir = path
        app.publicSourceDir = path
        val icon = runCatching { app.loadIcon(pm) }.getOrNull() ?: return null
        return runCatching { icon.toBitmap(size, size, Bitmap.Config.ARGB_8888) }.getOrNull()
    }
}

class ApkIconFetcher(private val data: ApkIcon, private val options: Options) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val bitmap = ApkIcons.load(options.context, data.path) ?: return null
        return ImageFetchResult(image = bitmap.asImage(), isSampled = false, dataSource = DataSource.DISK)
    }

    class Factory : Fetcher.Factory<ApkIcon> {
        override fun create(data: ApkIcon, options: Options, imageLoader: ImageLoader): Fetcher = ApkIconFetcher(data, options)
    }
}

class ApkIconKeyer : Keyer<ApkIcon> {
    override fun key(data: ApkIcon, options: Options): String = "apk-icon:${data.path}:${data.modified}"
}
