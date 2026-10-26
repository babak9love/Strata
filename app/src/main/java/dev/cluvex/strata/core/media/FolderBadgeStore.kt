package dev.cluvex.strata.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import org.json.JSONObject
import java.io.File

/** One folder icon as written to a settings backup: [type] is "app" (uses [packageName]) or "file" (a file inside the folder). */
@Serializable
data class FolderBadgeEntry(
    val path: String,
    val name: String,
    val type: String,
    val packageName: String? = null,
)

/**
 * User-chosen icons for individual folders. Each folder path maps to one spec string:
 *  - "app:<package>"  the icon of an installed app
 *  - "auto"           a file inside the folder: its image / video / APK, else the file's own default icon
 *  - "img:<file>"     a PNG the user picked (copied into the app's private storage)
 * A folder with no entry keeps the automatic icon. Entries are keyed by path, so renaming or moving a folder drops its icon.
 */
object FolderBadgeStore {

    private const val INDEX = "folder_badges.json"
    private const val DIR = "folder_badges"
    private const val MAX_SIDE = 256
    private const val TYPE_APP = "app"
    private const val TYPE_FILE = "file"

    private val _specs = MutableStateFlow<Map<String, String>>(emptyMap())
    val specs: StateFlow<Map<String, String>> = _specs

    private var appContext: Context? = null

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        runCatching {
            val file = File(context.filesDir, INDEX)
            if (file.exists()) {
                val json = JSONObject(file.readText())
                _specs.value = json.keys().asSequence().associateWith { json.getString(it) }
            }
        }
    }

    fun spec(path: String): String? = _specs.value[path]

    fun setApp(path: String, packageName: String) = put(path, "app:${packageName.trim()}")

    fun setAuto(path: String) = put(path, "auto")

    /** Copies [uri] (any image the user picked) into private storage as a small PNG. Returns false if it can't be read. */
    fun setImage(context: Context, path: String, uri: Uri): Boolean {
        val bmp = runCatching { decodeScaled(context, uri) }.getOrNull() ?: return false
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val name = "${path.hashCode().toUInt()}_${System.currentTimeMillis()}.png"
        val ok = runCatching { File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }.getOrDefault(false)
        if (!ok) return false
        deleteImageOf(path)
        put(path, "img:$name")
        return true
    }

    /** Back to the automatic icon: forgets the choice for [path] and deletes its picture, if any. */
    fun clear(path: String) {
        deleteImageOf(path)
        val next = _specs.value - path
        _specs.value = next
        save(next)
    }

    /** The app / file choices for the settings backup. Own pictures are private files and are not included. */
    fun exportEntries(): List<FolderBadgeEntry> = _specs.value.entries.sortedBy { it.key }.mapNotNull { (path, spec) ->
        val name = File(path).name
        when {
            spec.startsWith("app:") -> spec.removePrefix("app:").takeIf { it.isNotBlank() }
                ?.let { FolderBadgeEntry(path, name, TYPE_APP, it) }
            spec == "auto" -> FolderBadgeEntry(path, name, TYPE_FILE)
            else -> null
        }
    }

    /**
     * Applies entries from a backup. A folder that doesn't exist here (or an entry that can't be read) is skipped silently;
     * nothing is thrown. Returns how many were applied.
     */
    fun restore(entries: List<FolderBadgeEntry>): Int {
        var next = _specs.value
        var applied = 0
        for (e in entries) {
            runCatching {
                if (e.path.isBlank() || !File(e.path).isDirectory) return@runCatching
                val spec = when (e.type) {
                    TYPE_APP -> e.packageName?.trim()?.takeIf { it.isNotEmpty() }?.let { "app:$it" }
                    TYPE_FILE -> "auto"
                    else -> null
                } ?: return@runCatching
                deleteImageOf(e.path) // replacing an own picture: drop its file
                next = next + (e.path to spec)
                applied++
            }
        }
        if (applied > 0) {
            _specs.value = next
            save(next)
        }
        return applied
    }

    fun imageFile(context: Context, name: String): File = File(File(context.filesDir, DIR), name)

    private fun put(path: String, spec: String) {
        if (!spec.startsWith("img:")) deleteImageOf(path)
        val next = _specs.value + (path to spec)
        _specs.value = next
        save(next)
    }

    private fun deleteImageOf(path: String) {
        val ctx = appContext ?: return
        val old = _specs.value[path]?.takeIf { it.startsWith("img:") }?.removePrefix("img:") ?: return
        runCatching { imageFile(ctx, old).delete() }
    }

    private fun save(map: Map<String, String>) {
        val ctx = appContext ?: return
        runCatching { File(ctx.filesDir, INDEX).writeText(JSONObject(map as Map<*, *>).toString()) }
    }

    private fun decodeScaled(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_SIDE && bounds.outHeight / (sample * 2) >= MAX_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    }
}
