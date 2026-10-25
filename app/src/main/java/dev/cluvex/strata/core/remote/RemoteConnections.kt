package dev.cluvex.strata.core.remote

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

object RemoteConnections {

    private val json = Json { ignoreUnknownKeys = true }

    fun newId(): String = UUID.randomUUID().toString()

    fun upsert(list: List<RemoteConnection>, connection: RemoteConnection): List<RemoteConnection> {
        val c = if (connection.id.isBlank()) connection.copy(id = newId()) else connection
        val stored = if (c.savePassword) c else c.copy(password = "")
        val index = list.indexOfFirst { it.id == c.id }
        return if (index < 0) list + stored else list.toMutableList().also { it[index] = stored }
    }

    fun remove(list: List<RemoteConnection>, id: String): List<RemoteConnection> = list.filterNot { it.id == id }

    fun find(list: List<RemoteConnection>, id: String): RemoteConnection? = list.firstOrNull { it.id == id }

    fun encode(list: List<RemoteConnection>): String = json.encodeToString(list)

    fun decode(raw: String?): List<RemoteConnection> =
        if (raw.isNullOrBlank()) emptyList() else runCatching { json.decodeFromString<List<RemoteConnection>>(raw) }.getOrDefault(emptyList())
}
