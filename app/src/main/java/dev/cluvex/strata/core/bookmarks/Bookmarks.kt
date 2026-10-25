package dev.cluvex.strata.core.bookmarks

import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class Bookmark(
    val name: String,
    val path: String,
    val group: String = Bookmarks.DEFAULT_GROUP,
)

object Bookmarks {

    const val DEFAULT_GROUP = ""

    private val json = Json { ignoreUnknownKeys = true }

    fun normalize(path: String): String {
        val p = path.trim()
        if (p.isEmpty()) return "/"
        return if (p.length > 1) p.trimEnd('/').ifEmpty { "/" } else p
    }

    fun defaultName(path: String): String = File(normalize(path)).name.ifEmpty { "/" }

    fun contains(list: List<Bookmark>, path: String): Boolean {
        val p = normalize(path)
        return list.any { it.path == p }
    }

    fun add(
        list: List<Bookmark>,
        paths: List<String>,
        group: String = DEFAULT_GROUP,
        nameOf: (String) -> String = ::defaultName,
    ): Pair<List<Bookmark>, Int> {
        val known = list.mapTo(HashSet()) { it.path }
        val added = paths.map(::normalize).filter { known.add(it) }.map { Bookmark(nameOf(it).ifBlank { defaultName(it) }, it, group.trim()) }
        return (list + added) to added.size
    }

    fun update(list: List<Bookmark>, path: String, edited: Bookmark): Result<List<Bookmark>> {
        val old = normalize(path)
        val index = list.indexOfFirst { it.path == old }
        if (index < 0) return Result.failure(NoSuchElementException(tr(R.string.files_bookmark_missing)))
        val name = edited.name.trim()
        if (name.isEmpty()) return Result.failure(IllegalArgumentException(tr(R.string.files_name_empty)))
        val newPath = normalize(edited.path)
        if (newPath != old && list.any { it.path == newPath }) {
            return Result.failure(IllegalArgumentException(tr(R.string.files_bookmark_exists)))
        }
        val group = edited.group.trim()
        val replaced = Bookmark(name, newPath, group)
        val current = list[index]
        if (current.group == group) return Result.success(list.toMutableList().also { it[index] = replaced })
        return Result.success(list.filterIndexed { i, _ -> i != index } + replaced)
    }

    fun remove(list: List<Bookmark>, paths: Collection<String>): List<Bookmark> {
        val gone = paths.mapTo(HashSet(), ::normalize)
        return list.filterNot { it.path in gone }
    }

    fun move(list: List<Bookmark>, path: String, offset: Int): List<Bookmark> {
        val p = normalize(path)
        val index = list.indexOfFirst { it.path == p }
        if (index < 0 || offset == 0) return list
        val group = list[index].group
        val sameGroup = list.indices.filter { list[it].group == group }
        val pos = sameGroup.indexOf(index)
        val targetPos = (pos + offset).coerceIn(0, sameGroup.lastIndex)
        if (targetPos == pos) return list
        val members = sameGroup.map { list[it] }.toMutableList()
        members.add(targetPos, members.removeAt(pos))
        val out = list.toMutableList()
        sameGroup.forEachIndexed { k, slot -> out[slot] = members[k] }
        return out
    }

    fun groups(list: List<Bookmark>): List<String> =
        (listOf(DEFAULT_GROUP) + list.map { it.group }).distinct()

    fun inGroup(list: List<Bookmark>, group: String): List<Bookmark> = list.filter { it.group == group }

    fun deleteGroup(list: List<Bookmark>, group: String): List<Bookmark> {
        if (group == DEFAULT_GROUP) return list
        val (moved, kept) = list.partition { it.group == group }
        return kept + moved.map { it.copy(group = DEFAULT_GROUP) }
    }

    fun renameGroup(list: List<Bookmark>, from: String, to: String): List<Bookmark> {
        val target = to.trim()
        if (from == DEFAULT_GROUP || target == from) return list
        return list.map { if (it.group == from) it.copy(group = target) else it }
    }

    fun encode(list: List<Bookmark>): String = json.encodeToString(list)

    fun decode(text: String?): List<Bookmark> {
        if (text.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<Bookmark>>(text) }.getOrDefault(emptyList())
            .map { it.copy(path = normalize(it.path)) }
            .distinctBy { it.path }
    }
}
