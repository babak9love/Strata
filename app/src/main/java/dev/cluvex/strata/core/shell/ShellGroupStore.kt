package dev.cluvex.strata.core.shell

import android.content.Context
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Group names of the shell commands, in display order. They share the "shell_ui" preferences with the shell screen,
 * which also keeps the folded state there (that one is not part of a backup).
 */
class ShellGroupStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("shell_ui", Context.MODE_PRIVATE)
    private val names = ListSerializer(String.serializer())

    fun load(): List<String> =
        runCatching { Json.decodeFromString(names, prefs.getString(KEY, "[]") ?: "[]") }.getOrDefault(emptyList())

    /** The stored order, plus any group named by one of [commands] that is not stored yet. */
    fun merged(commands: List<SavedCommand>): List<String> {
        val result = load().toMutableList()
        for (c in commands) if (c.group.isNotEmpty() && c.group !in result) result += c.group
        return result
    }

    /** Adds the groups that are not here yet at the end, in the given order; the groups already here keep their place. */
    fun addAll(groups: List<String>) {
        val current = load()
        val added = groups.map { it.trim() }.filter { it.isNotEmpty() && it !in current }.distinct()
        if (added.isEmpty()) return
        prefs.edit().putString(KEY, Json.encodeToString(names, current + added)).apply()
    }

    private companion object {
        const val KEY = "groups"
    }
}
