package dev.cluvex.strata.core.rename

import dev.cluvex.strata.R
import dev.cluvex.strata.core.fs.FileAccess
import dev.cluvex.strata.core.fs.FileOperations
import dev.cluvex.strata.core.fs.FsUtil
import dev.cluvex.strata.core.fs.LocalFileAccess
import dev.cluvex.strata.core.fs.OpProgress
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.model.FileItem
import dev.cluvex.strata.core.model.SortBy
import dev.cluvex.strata.core.model.SortOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.Serializable
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.regex.PatternSyntaxException

@Serializable
data class RenameRule(
    val find: String = "",
    val replace: String = "",
    val regex: Boolean = false,
    val datePattern: String = DEFAULT_DATE_PATTERN,
) {
    companion object {
        const val DEFAULT_DATE_PATTERN = "yyyyMMdd_HHmmss"
    }
}

interface RenameInfo {
    fun exifTime(item: FileItem): Long? = null
    fun apk(item: FileItem): ApkInfo? = null

    companion object {
        val NONE = object : RenameInfo {}
    }
}

data class ApkInfo(val label: String, val packageName: String, val versionName: String, val versionCode: Long)

enum class RenameStatus {
    OK,

    UNCHANGED,

    INVALID,

    DUPLICATE,

    EXISTS,
}

data class RenamePreview(
    val item: FileItem,
    val newName: String,
    val status: RenameStatus,
    val message: String? = null,
)

data class RenamePlan(val entries: List<RenamePreview>, val error: String? = null) {
    val changes: List<RenamePreview> get() = entries.filter { it.status == RenameStatus.OK }
    val conflicts: Int get() = entries.count { it.status in BLOCKING }

    val canApply: Boolean get() = error == null && conflicts == 0 && changes.isNotEmpty()

    private companion object {
        val BLOCKING = setOf(RenameStatus.INVALID, RenameStatus.DUPLICATE, RenameStatus.EXISTS)
    }
}

enum class RenameOrder { CURRENT, NAME, DATE, SIZE }

object BatchRename {

    private val token = Regex("""\{(P|S|T|E|AN|AP|AV|AC|(z?)(\d{1,18}))\}""", RegexOption.IGNORE_CASE)
    private val dateToken = Regex("""\{[TE]\}""", RegexOption.IGNORE_CASE)

    fun ordered(items: List<FileItem>, order: RenameOrder, descending: Boolean): List<FileItem> {
        val by = when (order) {
            RenameOrder.CURRENT -> return if (descending) items.reversed() else items
            RenameOrder.NAME -> SortBy.NAME
            RenameOrder.DATE -> SortBy.DATE
            RenameOrder.SIZE -> SortBy.SIZE
        }
        return items.sortedWith(SortOrder(by, descending, dirsFirst = false).comparator())
    }

    fun plan(
        items: List<FileItem>,
        rule: RenameRule,
        existing: Collection<String>,
        info: RenameInfo = RenameInfo.NONE,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): RenamePlan {
        val pattern = if (dateToken.containsMatchIn(rule.replace)) rule.datePattern.ifBlank { RenameRule.DEFAULT_DATE_PATTERN } else RenameRule.DEFAULT_DATE_PATTERN
        val dates = try {
            SimpleDateFormat(pattern, Locale.US).apply { this.timeZone = timeZone }
        } catch (e: IllegalArgumentException) {
            return RenamePlan(items.map { RenamePreview(it, it.name, RenameStatus.UNCHANGED) }, tr(R.string.files_rename_bad_date, e.message))
        }
        val regex = if (rule.regex && rule.find.isNotEmpty()) {
            try {
                Regex(rule.find)
            } catch (e: PatternSyntaxException) {
                return RenamePlan(items.map { RenamePreview(it, it.name, RenameStatus.UNCHANGED) }, tr(R.string.files_rename_bad_regex, e.description))
            }
        } else {
            null
        }

        val names = try {
            items.mapIndexed { i, item -> newName(rule, regex, item, i, items.size, info, dates) }
        } catch (e: RuntimeException) {
            return RenamePlan(items.map { RenamePreview(it, it.name, RenameStatus.UNCHANGED) }, tr(R.string.files_rename_bad_replacement, e.message))
        }

        val first = items.mapIndexed { i, item ->
            val name = names[i]
            val invalid = FileOperations.validateName(name)
            when {
                invalid != null -> RenamePreview(item, name, RenameStatus.INVALID, invalid)
                name == item.name -> RenamePreview(item, name, RenameStatus.UNCHANGED)
                else -> RenamePreview(item, name, RenameStatus.OK)
            }
        }
        val changing = first.filter { it.status == RenameStatus.OK }
        val counts = changing.groupingBy { it.newName.lowercase() }.eachCount()
        val vacated = changing.mapTo(HashSet()) { it.item.name.lowercase() }
        val occupied = existing.mapTo(HashSet()) { it.lowercase() }.apply { removeAll(vacated) }
        first.filter { it.status != RenameStatus.OK }.forEach { occupied += it.item.name.lowercase() }

        val entries = first.map { p ->
            if (p.status != RenameStatus.OK) return@map p
            val key = p.newName.lowercase()
            when {
                (counts[key] ?: 0) > 1 -> p.copy(status = RenameStatus.DUPLICATE, message = tr(R.string.files_rename_duplicate))
                key in occupied -> p.copy(status = RenameStatus.EXISTS, message = tr(R.string.files_name_exists))
                else -> p
            }
        }
        return RenamePlan(entries)
    }

    private fun newName(
        rule: RenameRule,
        regex: Regex?,
        item: FileItem,
        index: Int,
        count: Int,
        info: RenameInfo,
        dates: SimpleDateFormat,
    ): String = when {
        rule.find.isEmpty() -> expand(rule.replace, item, index, count, info, dates) { it }
        regex != null -> regex.replace(item.name, expand(rule.replace, item, index, count, info, dates, Regex::escapeReplacement))
        else -> item.name.replace(rule.find, expand(rule.replace, item, index, count, info, dates) { it })
    }

    internal fun expand(
        template: String,
        item: FileItem,
        index: Int,
        count: Int,
        info: RenameInfo,
        dates: SimpleDateFormat,
        quote: (String) -> String,
    ): String {
        val (prefix, suffix) = split(item)
        val apk by lazy(LazyThreadSafetyMode.NONE) { info.apk(item) }
        return token.replace(template) { m ->
            val value = when (m.groupValues[1].uppercase()) {
                "P" -> prefix
                "S" -> suffix
                "T" -> dates.format(item.lastModified)
                "E" -> dates.format(info.exifTime(item) ?: item.lastModified)
                "AN" -> apk?.label.orEmpty()
                "AP" -> apk?.packageName.orEmpty()
                "AV" -> apk?.versionName.orEmpty()
                "AC" -> apk?.versionCode?.toString().orEmpty()
                else -> number(m.groupValues[3], padded = m.groupValues[2].isNotEmpty(), index, count) ?: return@replace m.value
            }
            quote(value.replace('/', '_').replace('\u0000', '_'))
        }
    }

    private fun number(digits: String, padded: Boolean, index: Int, count: Int): String? {
        val start = digits.toLongOrNull() ?: return null
        val value = start + index
        if (!padded) return value.toString()
        val largest = start + (count - 1).coerceAtLeast(0)
        val width = maxOf(digits.length, largest.toString().length)
        return value.toString().padStart(width, '0')
    }

    private fun split(item: FileItem): Pair<String, String> {
        val dot = item.name.lastIndexOf('.')
        return if (!item.isDirectory && dot > 0) item.name.substring(0, dot) to item.name.substring(dot) else item.name to ""
    }

    fun apply(plan: RenamePlan, access: FileAccess = LocalFileAccess): Flow<OpProgress> = flow {
        require(plan.canApply) { "The rename plan has conflicts" }
        val changes = plan.changes
        val total = changes.size
        val oldNames = changes.mapTo(HashSet()) { it.item.name.lowercase() }
        val failed = mutableListOf<String>()
        val created = mutableListOf<String>()
        val moves = mutableListOf<Pair<String, String>>()
        var done = 0

        val (viaTemp, direct) = changes.partition { it.newName.lowercase() in oldNames }

        val parked = mutableListOf<Pair<RenamePreview, File>>()
        for (c in viaTemp) {
            val src = access.file(c.item.path)
            val temp = tempSibling(src, access)
            if (temp == null || !src.renameTo(temp)) {
                for ((p, t) in parked.asReversed()) t.renameTo(access.file(p.item.path))
                emit(OpProgress.Finished(0, listOf(tr(R.string.files_rename_rolled_back, c.item.name))))
                return@flow
            }
            parked += c to temp
        }

        for (c in direct) {
            emit(OpProgress.Running(c.item.name, done.toLong(), total.toLong(), done, total))
            val src = access.file(c.item.path)
            val dst = access.child(src.parentFile ?: continue, c.newName)
            if (!FsUtil.exists(dst, access) && src.renameTo(dst)) {
                done++
                created += dst.absolutePath
                moves += src.absolutePath to dst.absolutePath
            } else {
                failed += c.item.name
            }
        }

        for ((c, temp) in parked) {
            emit(OpProgress.Running(c.item.name, done.toLong(), total.toLong(), done, total))
            val dst = access.child(temp.parentFile ?: continue, c.newName)
            if (!FsUtil.exists(dst, access) && temp.renameTo(dst)) {
                done++
                created += dst.absolutePath
                moves += access.file(c.item.path).absolutePath to dst.absolutePath
            } else {
                val home = access.file(c.item.path)
                val restored = !FsUtil.exists(home, access) && temp.renameTo(home)
                failed += if (restored) c.item.name else tr(R.string.files_rename_left_as, c.item.name, temp.name)
            }
        }
        emit(OpProgress.Finished(done, failed, created = created, moves = moves))
    }.flowOn(Dispatchers.IO)

    private fun tempSibling(src: File, access: FileAccess): File? {
        val parent = src.parentFile ?: return null
        repeat(5) {
            val candidate = access.child(parent, ".strata-rename-${UUID.randomUUID().toString().take(8)}")
            if (!FsUtil.exists(candidate, access)) return candidate
        }
        return null
    }
}
