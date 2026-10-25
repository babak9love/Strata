package dev.cluvex.strata.core.open

import kotlinx.serialization.json.Json

object OpenDefaults {

    private val json = Json { ignoreUnknownKeys = true }

    fun decode(text: String?): Map<String, OpenTarget> {
        if (text.isNullOrBlank()) return emptyMap()
        val raw = runCatching { json.decodeFromString<Map<String, String>>(text) }.getOrNull() ?: return emptyMap()
        return raw.mapNotNull { (ext, target) -> OpenTarget.decode(target)?.let { key(ext) to it } }.toMap()
    }

    fun encode(defaults: Map<String, OpenTarget>): String {
        val sorted: Map<String, String> = LinkedHashMap(defaults.mapValues { it.value.encode() }.toSortedMap())
        return json.encodeToString(sorted)
    }

    fun set(defaults: Map<String, OpenTarget>, extension: String, target: OpenTarget): Map<String, OpenTarget> =
        defaults + (key(extension) to target)

    fun remove(defaults: Map<String, OpenTarget>, extension: String): Map<String, OpenTarget> = defaults - key(extension)

    fun key(extension: String): String = extension.trim().trimStart('.').lowercase().ifEmpty { OpenResolver.NO_EXTENSION }
}
