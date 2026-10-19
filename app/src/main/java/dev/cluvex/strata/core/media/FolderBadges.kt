package dev.cluvex.strata.core.media

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
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
    private const val PHOTOS_PACKAGE = "com.google.android.apps.photos"

    private val imageFolders = setOf("pictures")
    private val cameraFolders = setOf("dcim", "camera")
    private val audioFolders = setOf("music")
    // Distinctive enough to match at any depth (the others only at the top level of internal storage).
    private val anyDepthAudio = setOf("audio")
    private val anyDepthRecorder = setOf("recordings", "voice recorder")
    private val videoFolders = setOf("movies", "video", "videos")

    /**
     * Folders that belong to a known app, by lower-case folder name -> candidate package names (first installed wins).
     * Every package listed here must also be declared in the manifest's <queries>, or Android hides it from us.
     */
    private val appFolders: Map<String, List<String>> = mapOf(
        "1dmp" to listOf(
            "idm.internet.download.manager.plus",
            "idm.internet.download.manager",
            "idm.internet.download.manager.adm.lite",
        ),
    )

    /**
     * Badge for [path]:
     *  - top-level Pictures / DCIM / Music / Movies folders show the icon of the phone's default app for that media type;
     *  - any other folder that contains an APK shows that app's icon.
     */
    fun load(context: Context, path: String): Bitmap? {
        val dir = File(path)
        mediaKind(dir)?.let { media -> defaultAppIcon(context, media)?.let { return it } }
        appFolders[dir.name.lowercase()]?.let { packages -> installedAppIcon(context, packages)?.let { return it } }
        return apkIcon(context, dir)
    }

    private enum class Media { IMAGE, CAMERA, AUDIO, VIDEO, RECORDER }

    private fun mediaKind(dir: File): Media? {
        val name = dir.name.lowercase()
        when (name) {
            in anyDepthAudio -> return Media.AUDIO
            in anyDepthRecorder -> return Media.RECORDER
        }
        val root = runCatching { Environment.getExternalStorageDirectory() }.getOrNull() ?: return null
        if (dir.parentFile?.canonicalPath != root.canonicalPath) return null
        return when (name) {
            in imageFolders -> Media.IMAGE
            in cameraFolders -> Media.CAMERA
            in audioFolders -> Media.AUDIO
            in videoFolders -> Media.VIDEO
            else -> null
        }
    }

    private fun defaultAppIcon(context: Context, media: Media): Bitmap? = runCatching {
        val pm = context.packageManager
        val gallery = categoryApp(pm, Intent.CATEGORY_APP_GALLERY)
        val info = when (media) {
            // The phone's own Gallery / Music apps (what the launcher calls "Gallery" and "Music"),
            // not whichever app happens to be the "open with" default for the file type.
            Media.IMAGE -> gallery ?: viewHandler(pm, "image/*", emptySet())
            // DCIM / Camera: the phone's camera app (the app that handles "take a photo").
            Media.CAMERA -> actionHandler(pm, MediaStore.ACTION_IMAGE_CAPTURE) ?: gallery
            // The phone's own voice recorder (the app that handles "record sound"); no fallback, so no wrong icon.
            Media.RECORDER -> actionHandler(pm, MediaStore.Audio.Media.RECORD_SOUND_ACTION)
            Media.AUDIO -> categoryApp(pm, Intent.CATEGORY_APP_MUSIC) ?: viewHandler(pm, "audio/*", emptySet())
            // There is no "video player" category: take a real video handler that is not the gallery / photos app.
            Media.VIDEO -> viewHandler(pm, "video/*", setOfNotNull(gallery?.activityInfo?.packageName, PHOTOS_PACKAGE))
                ?: viewHandler(pm, "video/*", emptySet())
        }
        info?.loadIcon(pm)?.toBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
    }.getOrNull()

    private fun categoryApp(pm: PackageManager, category: String): ResolveInfo? {
        val intent = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, category)
        val direct = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        if (direct != null && direct.activityInfo?.packageName != "android") return direct
        return pm.queryIntentActivities(intent, 0).firstOrNull { it.activityInfo?.packageName != "android" }
    }

    private fun actionHandler(pm: PackageManager, action: String): ResolveInfo? {
        val intent = Intent(action)
        val all = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .filter { it.activityInfo?.packageName != "android" }
        // Prefer a built-in app (the phone's own camera) over third-party ones.
        return all.firstOrNull { (it.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 } ?: all.firstOrNull()
    }

    private fun viewHandler(pm: PackageManager, mime: String, exclude: Set<String>): ResolveInfo? {
        val intent = Intent(Intent.ACTION_VIEW).setType(mime)
        val all = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .filter { it.activityInfo?.packageName.let { pkg -> pkg != "android" && pkg !in exclude } }
        // Prefer a built-in app (the phone's own player) over third-party ones.
        return all.firstOrNull { (it.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 } ?: all.firstOrNull()
    }

    private fun installedAppIcon(context: Context, packages: List<String>): Bitmap? {
        val pm = context.packageManager
        for (pkg in packages) {
            val icon = runCatching { pm.getApplicationIcon(pkg).toBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888) }.getOrNull()
            if (icon != null) return icon
        }
        return null
    }

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
