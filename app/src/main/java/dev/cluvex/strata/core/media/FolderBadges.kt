package dev.cluvex.strata.core.media

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
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
data class FolderBadge(val path: String, val modified: Long, val spec: String? = null)

object FolderBadges {

    private const val SIZE = 128
    private const val MAX_ENTRIES = 400
    private const val MAX_APK_TRIES = 3
    private const val PHOTOS_PACKAGE = "com.google.android.apps.photos"

    private val imageFolders = setOf("pictures")
    private val cameraFolders = setOf("dcim", "camera")
    private val movieFolders = setOf("movies")
    private val videoFolders = setOf("video", "videos")
    private val audioFolders = setOf("music", "audio")
    private val recorderFolders = setOf("recordings", "voice recorder")

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
        val spec = FolderBadgeStore.spec(path)
        // "A file inside the folder" is final: if it has no image / video / APK, no automatic app icon takes its place
        // (the explorer then shows the default icon of the folder's first file).
        if (spec == "auto") return customIcon(context, dir, spec)
        spec?.let { customIcon(context, dir, it)?.let { icon -> return icon } }
        mediaKind(dir)?.let { media -> defaultAppIcon(context, media)?.let { return it } }
        appFolders[dir.name.lowercase()]?.let { packages -> installedAppIcon(context, packages)?.let { return it } }
        return apkIcon(context, dir)
    }

    private enum class Media { IMAGE, CAMERA, AUDIO, VIDEO, RECORDER }

    /** Folder names are matched at any depth, not only at the top level of internal storage. */
    private fun mediaKind(dir: File): Media? = when (dir.name.lowercase()) {
        in imageFolders -> Media.IMAGE
        in cameraFolders -> Media.CAMERA
        in movieFolders, in videoFolders -> Media.VIDEO
        in audioFolders -> Media.AUDIO
        in recorderFolders -> Media.RECORDER
        else -> null
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

    /** The icon the user picked for this folder; null (so the automatic icon is used) if it can't be produced. */
    private fun customIcon(context: Context, dir: File, spec: String): Bitmap? = runCatching {
        when {
            spec.startsWith("app:") -> installedAppIcon(context, listOf(spec.removePrefix("app:")))
            spec.startsWith("img:") -> BitmapFactory.decodeFile(FolderBadgeStore.imageFile(context, spec.removePrefix("img:")).path)
            spec == "auto" -> contentIcon(context, dir)
            else -> null
        }
    }.getOrNull()

    private val imageExt = setOf("jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "avif")
    private val videoExt = setOf("mp4", "mkv", "webm", "mov", "avi", "3gp", "m4v")

    /** First image, else video, else APK inside the folder (by name; hidden files, whose name starts with a dot, are ignored), rendered as a small bitmap. */
    private fun contentIcon(context: Context, dir: File): Bitmap? {
        val files = dir.listFiles()?.asSequence()?.filter { it.isFile && !it.name.startsWith(".") }?.take(MAX_ENTRIES)?.sortedBy { it.name }?.toList() ?: return null
        files.firstOrNull { it.extension.lowercase() in imageExt }?.let { f ->
            decodeImage(f)?.let { return it }
        }
        files.firstOrNull { it.extension.lowercase() in videoExt }?.let { f ->
            videoFrame(f)?.let { return it }
        }
        return apkIcon(context, dir, skipHidden = true)
    }

    private fun decodeImage(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= SIZE && bounds.outHeight / (sample * 2) >= SIZE) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun videoFrame(file: File): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.path)
            r.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    private fun installedAppIcon(context: Context, packages: List<String>): Bitmap? {
        val pm = context.packageManager
        for (pkg in packages) {
            val icon = runCatching { pm.getApplicationIcon(pkg).toBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888) }.getOrNull()
            if (icon != null) return icon
        }
        return null
    }

    private fun apkIcon(context: Context, dir: File, skipHidden: Boolean = false): Bitmap? {
        val files = runCatching { dir.listFiles() }.getOrNull() ?: return null
        var tries = 0
        for (f in files.asSequence().take(MAX_ENTRIES).filter { it.isFile && it.extension.equals("apk", ignoreCase = true) && !(skipHidden && it.name.startsWith(".")) }.sortedBy { it.name }) {
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
    override fun key(data: FolderBadge, options: Options): String = "folder-badge:${data.path}:${data.modified}:${data.spec.orEmpty()}"
}
