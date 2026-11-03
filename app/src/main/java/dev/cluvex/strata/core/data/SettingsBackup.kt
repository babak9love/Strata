package dev.cluvex.strata.core.data

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import dev.cluvex.strata.R
import dev.cluvex.strata.core.bookmarks.Bookmark
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.media.FolderBadgeEntry
import dev.cluvex.strata.core.remote.RemoteConnection
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

object SettingsBackup {

    const val FORMAT = "strata-settings"
    const val VERSION = 1

    private val pretty = Json { prettyPrint = true }

    fun encode(values: Map<String, Any>): String {
        val rank = SettingsRepository.Keys.ORDER.withIndex().associate { (index, name) -> name to index }
        val settings = values.entries.sortedWith(compareBy({ rank[it.key] ?: Int.MAX_VALUE }, { it.key })).mapNotNull { (name, value) ->
            when (value) {
                is Boolean -> name to JsonPrimitive(value)
                is Int -> name to JsonPrimitive(value)
                is Long -> name to JsonPrimitive(value)
                is Float -> name to JsonPrimitive(value)
                is Double -> name to JsonPrimitive(value)
                is String -> name to JsonPrimitive(value)
                else -> null
            }
        }.toMap()
        val root = JsonObject(
            mapOf(
                "format" to JsonPrimitive(FORMAT),
                "version" to JsonPrimitive(VERSION),
                "settings" to JsonObject(settings),
            ),
        )
        return pretty.encodeToString(JsonObject.serializer(), root)
    }

    private val lenient = Json { ignoreUnknownKeys = true }

    const val BOOKMARKS_KEY = "bookmarks"
    const val CONNECTIONS_KEY = "connections"
    const val FOLDER_BADGES_KEY = "folderBadges"

    class Extras(val bookmarks: List<Bookmark>?, val connections: List<RemoteConnection>?, val folderBadges: List<FolderBadgeEntry>? = null)

    fun attach(
        text: String,
        bookmarks: List<Bookmark>,
        connections: List<RemoteConnection>,
        folderBadges: List<FolderBadgeEntry> = emptyList(),
    ): String {
        val root = Json.parseToJsonElement(text).jsonObject
        val extended = JsonObject(
            root + mapOf(
                BOOKMARKS_KEY to lenient.encodeToJsonElement(bookmarks),
                CONNECTIONS_KEY to lenient.encodeToJsonElement(connections),
                FOLDER_BADGES_KEY to lenient.encodeToJsonElement(folderBadges),
            ),
        )
        return pretty.encodeToString(JsonObject.serializer(), extended)
    }

    fun extras(text: String): Extras {
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return Extras(null, null)
        return Extras(
            bookmarks = root.list<Bookmark>(BOOKMARKS_KEY),
            connections = root.list<RemoteConnection>(CONNECTIONS_KEY),
            folderBadges = root.tolerantList<FolderBadgeEntry>(FOLDER_BADGES_KEY),
        )
    }

    /** Like [list], but one malformed entry is skipped instead of dropping the whole list. */
    private inline fun <reified T> JsonObject.tolerantList(key: String): List<T>? =
        (this[key] as? JsonArray)?.mapNotNull { el -> runCatching { lenient.decodeFromJsonElement<T>(el) }.getOrNull() }

    private inline fun <reified T> JsonObject.list(key: String): List<T>? =
        (this[key] as? JsonArray)?.let { runCatching { lenient.decodeFromJsonElement<List<T>>(it) }.getOrNull() }

    fun decode(text: String): Map<String, JsonElement> {
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: throw IllegalArgumentException(tr(R.string.settings_backup_not_strata))
        val format = (root["format"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        require(format == FORMAT) { tr(R.string.settings_backup_not_strata) }
        val version = (root["version"] as? JsonPrimitive)?.intOrNull ?: 0
        require(version in 1..VERSION) { tr(R.string.settings_backup_newer, version) }
        val settings = root["settings"] as? JsonObject ?: throw IllegalArgumentException(tr(R.string.settings_backup_empty))
        return settings
    }

    fun put(p: MutablePreferences, name: String, value: JsonElement): Boolean {
        val primitive = value as? JsonPrimitive ?: return false
        val keys = SettingsRepository.Keys
        keys.BOOLEANS.firstOrNull { it.name == name }?.let { key ->
            val v = primitive.takeUnless { it.isString }?.booleanOrNull ?: return false
            p[key] = v
            return true
        }
        keys.INTS.firstOrNull { it.name == name }?.let { key ->
            val v = primitive.takeUnless { it.isString }?.longOrNull?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE } ?: return false
            p[key] = v.toInt()
            return true
        }
        keys.FLOATS.firstOrNull { it.name == name }?.let { key ->
            val v = primitive.takeUnless { it.isString }?.doubleOrNull?.takeIf { it.isFinite() } ?: return false
            p[key] = v.toFloat()
            return true
        }
        keys.STRINGS.firstOrNull { it.name == name }?.let { key ->
            if (!primitive.isString) return false
            p[key] = primitive.content
            return true
        }
        return false
    }

    fun known(name: String): Preferences.Key<*>? = SettingsRepository.Keys.let { k ->
        (k.BOOLEANS + k.INTS + k.FLOATS + k.STRINGS).firstOrNull { it.name == name }
    }

    fun fileName(epochMillis: Long): String =
        "strata-settings-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.ROOT).format(java.util.Date(epochMillis)) + ".json"
}
