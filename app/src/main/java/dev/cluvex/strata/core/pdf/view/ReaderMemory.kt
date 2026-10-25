package dev.cluvex.strata.core.pdf.view

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Locale

data class ReadingSpot(val page: Int, val fraction: Float, val zoom: Float)

object ReaderMemory {

    const val MAX_SPOTS = 300
    const val MAX_BOOKMARKED = 200

    fun encodeSpot(spot: ReadingSpot): String = String.format(Locale.ROOT, "%d:%.4f:%.3f", spot.page, spot.fraction, spot.zoom)

    fun decodeSpot(text: String?): ReadingSpot? {
        val parts = text?.split(':') ?: return null
        if (parts.size != 3) return null
        val page = parts[0].toIntOrNull() ?: return null
        val fraction = parts[1].toFloatOrNull() ?: return null
        val zoom = parts[2].toFloatOrNull() ?: return null
        if (page < 0 || !fraction.isFinite() || !zoom.isFinite() || zoom <= 0f) return null
        return ReadingSpot(page, fraction.coerceIn(-0.5f, 1f), zoom.coerceIn(DocLayout.MIN_ZOOM, DocLayout.MAX_ZOOM))
    }

    fun <T> remember(map: Map<String, T>, key: String, value: T?, max: Int): Map<String, T> {
        val next = LinkedHashMap(map)
        next.remove(key)
        if (value != null) next[key] = value
        while (next.size > max) next.remove(next.keys.first())
        return next
    }

    fun encodeSpots(spots: Map<String, ReadingSpot>): String = JsonObject(spots.mapValues { JsonPrimitive(encodeSpot(it.value)) }).toString()

    fun decodeSpots(text: String?): Map<String, ReadingSpot> {
        if (text.isNullOrBlank()) return emptyMap()
        return runCatching {
            Json.parseToJsonElement(text).jsonObject.mapNotNull { (k, v) -> decodeSpot((v as? JsonPrimitive)?.content)?.let { k to it } }.toMap(LinkedHashMap())
        }.getOrDefault(emptyMap())
    }

    fun encodeMarks(marks: Map<String, List<Int>>): String =
        JsonObject(marks.mapValues { (_, pages) -> JsonArray(pages.sorted().distinct().map { JsonPrimitive(it) }) }).toString()

    fun decodeMarks(text: String?): Map<String, List<Int>> {
        if (text.isNullOrBlank()) return emptyMap()
        return runCatching {
            Json.parseToJsonElement(text).jsonObject.mapNotNull { (k, v) ->
                val pages = runCatching { v.jsonArray.mapNotNull { it.jsonPrimitive.intOrNull?.takeIf { p -> p >= 0 } } }.getOrNull() ?: return@mapNotNull null
                k to pages.sorted().distinct()
            }.toMap(LinkedHashMap())
        }.getOrDefault(emptyMap())
    }

    fun toggle(marks: Map<String, List<Int>>, key: String, page: Int): Map<String, List<Int>> {
        val current = marks[key].orEmpty()
        val next = if (page in current) current - page else (current + page).sorted()
        return remember(marks, key, next.takeIf { it.isNotEmpty() }, MAX_BOOKMARKED)
    }
}
