package dev.cluvex.strata.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File

/**
 * User-chosen icons for individual folders. Each folder path maps to one spec string:
 *  - "app:<package>"  the icon of an installed app
 *  - "auto"           an image / video / APK found inside the folder
 *  - "img:<file>"     a PNG the user picked (copied into the app's private storage)
 * A folder with no entry keeps the automatic icon. Entries are keyed by path, so renaming or moving a folder drops its icon.
 */
object FolderIconStore {

    private const val INDEX = "folder_icons.json"
    private const val DIR = "folder_icons"
    private const val MAX_SIDE = 256

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

    fun clear(path: String) {
        deleteImageOf(path)
        val next = _specs.value - path
        _specs.value = next
        save(next)
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
