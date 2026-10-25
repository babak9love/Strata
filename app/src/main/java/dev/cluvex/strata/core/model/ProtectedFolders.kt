package dev.cluvex.strata.core.model

import java.io.File

/**
 * Folders the user protected from deletion. The folder itself (and any folder that contains it) cannot be deleted
 * from the app; everything inside it still can. Paths are stored one per line.
 */
object ProtectedFolders {
    fun key(path: String): String = path.trimEnd('/').ifEmpty { "/" }

    /** The path as stored and compared: aliases such as /sdcard are resolved, but a link is never followed to its target. */
    fun canonical(path: String): String {
        if (path.contains("://")) return key(path)
        return runCatching {
            val f = File(path)
            val parent = f.parentFile ?: return@runCatching key(path)
            key(File(parent.canonicalPath, f.name).path)
        }.getOrDefault(key(path))
    }

    fun decode(raw: String?): List<String> =
        raw.orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    fun encode(list: List<String>): String = list.joinToString("\n")

    /** Cheap check for drawing the lock badge: is exactly this folder in the list? */
    fun shows(list: List<String>, path: String): Boolean = list.isNotEmpty() && key(path) in list

    /** [list] after the folder [from] became [to]: the folder itself and any protected folder inside it follow it. */
    fun follow(list: List<String>, from: String, to: String): List<String> {
        val a = canonical(from)
        val b = canonical(to)
        if (a == b || a == "/") return list
        val prefix = "$a/"
        return list.map {
            when {
                it == a -> b
                it.startsWith(prefix) -> b + it.substring(a.length)
                else -> it
            }
        }.distinct()
    }

    /** The protected folders strictly inside [path] (not [path] itself). */
    fun inside(list: List<String>, path: String): List<String> {
        val p = canonical(path)
        val prefix = if (p == "/") "/" else "$p/"
        return list.filter { it != p && it.startsWith(prefix) }
    }

    /** The protected folder that stops [path] from being deleted: that folder itself, or one inside it. Null if it may be deleted. */
    fun blockedBy(list: List<String>, path: String): String? {
        if (list.isEmpty()) return null
        val p = canonical(path)
        val prefix = if (p == "/") "/" else "$p/"
        return list.firstOrNull { it == p || it.startsWith(prefix) }
    }
}
