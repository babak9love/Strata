package dev.cluvex.strata.core.media

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Environment
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

/** Request for the small badge drawn on a folder's icon: [path] is the folder, [modified] busts the cache when it changes. */
data class FolderBadge(val path: String, val modified: Long)

object FolderBadges {

    private const val SIZE = 128
    private const val MAX_ENTRIES = 400
    private const val MAX_APK_TRIES = 3

    private val imageFolders = setOf("pictures", "dcim", "camera")
    private val audioFolders = setOf("music")
    private val videoFolders = setOf("movies", "video", "videos")

    /**
     * Badge for [path]:
     *  - top-level Pictures / DCIM / Music / Movies folders show the icon of the phone's default app for that media type;
     *  - any other folder that contains an APK shows that app's icon.
     */
    fun load(context: Context, path: String): Bitmap? {
        val dir = File(path)
        mediaMime(dir)?.let { mime -> defaultAppIcon(context, mime)?.let { return it } }
        return apkIcon(context, dir)
    }

    private fun mediaMime(dir: File): String? {
        val root = runCatching { Environment.getExternalStorageDirectory() }.getOrNull() ?: return null
        if (dir.parentFile?.canonicalPath != root.canonicalPath) return null
        val name = dir.name.lowercase()
        return when (name) {
            in imageFolders -> "image/*"
            in audioFolders -> "audio/*"
            in videoFolders -> "video/*"
            else -> null
        }
    }

    private fun defaultAppIcon(context: Context, mime: String): Bitmap? = runCatching {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_VIEW).setType(mime)
        var info = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        // "android" means the system chooser, i.e. the user has no default app for this type: take the first real handler.
        if (info == null || info.activityInfo?.packageName == "android") {
            info = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                .firstOrNull { it.activityInfo?.packageName != "android" }
        }
        info?.loadIcon(pm)?.toBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
    }.getOrNull()

    private fun apkIcon(context: Context, dir: File): Bitmap? {
        val files = runCatching { dir.listFiles() }.getOrNull() ?: return null
        var tries = 0
        for (f in files.asSequence().take(MAX_ENTRIES).filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }.sortedBy { it.name }) {
            ApkIcons.load(context, f.path, SIZE)?.let { return it }
            if (++tries >= MAX_APK_TRIES) break
        }
        return null
    }
}

class FolderBadgeFetcher(private val data: FolderBadge, private val options: Options) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val bitmap = FolderBadges.load(options.context, data.path) ?: return null
        return ImageFetchResult(image = bitmap.asImage(), isSampled = false, dataSource = DataSource.DISK)
    }

    class Factory : Fetcher.Factory<FolderBadge> {
        override fun create(data: FolderBadge, options: Options, imageLoader: ImageLoader): Fetcher = FolderBadgeFetcher(data, options)
    }
}

class FolderBadgeKeyer : Keyer<FolderBadge> {
    override fun key(data: FolderBadge, options: Options): String = "folder-badge:${data.path}:${data.modified}"
}
