package dev.cluvex.strata.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

enum class SortBy { NAME, SIZE, DATE, TYPE }

data class SortOrder(
    val by: SortBy = SortBy.NAME,
    val descending: Boolean = false,
    val dirsFirst: Boolean = true,
) {
    fun comparator(): Comparator<FileItem> {
        val base: Comparator<FileItem> = when (by) {
            SortBy.NAME -> compareBy { it.name.lowercase() }
            SortBy.SIZE -> compareBy { it.size }
            SortBy.DATE -> compareBy { it.lastModified }
            SortBy.TYPE -> compareBy({ f: FileItem -> f.extension }, { f: FileItem -> f.name.lowercase() })
        }
        val directioned = if (descending) base.reversed() else base
        return if (dirsFirst) {
            compareByDescending<FileItem> { it.isDirectory }.then(directioned)
        } else {
            directioned
        }
    }
}

object FolderSorts {
    const val MAX = 500

    fun key(path: String): String = path.trimEnd('/').ifEmpty { "/" }

    fun effective(path: String, global: SortOrder, overrides: Map<String, SortOrder>): SortOrder =
        overrides[key(path)] ?: global

    fun with(overrides: Map<String, SortOrder>, path: String, order: SortOrder?): Map<String, SortOrder> {
        val k = key(path)
        val next = LinkedHashMap(overrides)
        next.remove(k)
        if (order != null) next[k] = order
        while (next.size > MAX) next.remove(next.keys.first())
        return next
    }

    fun encode(overrides: Map<String, SortOrder>): String = JsonObject(
        overrides.mapValues { (_, o) ->
            buildJsonObject { put("by", o.by.name); put("desc", o.descending); put("dirs", o.dirsFirst) }
        },
    ).toString()

    fun decode(json: String?): Map<String, SortOrder> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            Json.parseToJsonElement(json).jsonObject.mapNotNull { (path, v) ->
                val o = v.jsonObject
                val by = runCatching { SortBy.valueOf(o["by"]!!.jsonPrimitive.content) }.getOrNull() ?: return@mapNotNull null
                path to SortOrder(by, o["desc"]?.jsonPrimitive?.boolean ?: false, o["dirs"]?.jsonPrimitive?.boolean ?: true)
            }.toMap(LinkedHashMap())
        }.getOrDefault(emptyMap())
    }
}
