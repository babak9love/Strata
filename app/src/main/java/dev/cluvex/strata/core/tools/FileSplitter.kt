package dev.cluvex.strata.core.tools

import dev.cluvex.strata.R
import dev.cluvex.strata.core.fs.FileAccess
import dev.cluvex.strata.core.fs.FileOperations
import dev.cluvex.strata.core.fs.FsUtil
import dev.cluvex.strata.core.fs.LocalFileAccess
import dev.cluvex.strata.core.fs.OpProgress
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.model.FileItem
import dev.cluvex.strata.core.rename.BatchRename
import dev.cluvex.strata.core.rename.RenameInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.roundToLong

sealed interface SplitMethod {
    data class BySize(val bytes: Long) : SplitMethod
    data class ByCount(val parts: Int) : SplitMethod
}

object FileSplitter {
    const val DEFAULT_PATTERN = "{P}{S}.{z001}"
    const val MAX_PARTS = 10_000

    private val numberToken = Regex("""\{z?\d{1,18}\}""", RegexOption.IGNORE_CASE)
    private val sizeText = Regex("""^\s*(\d+(?:\.\d+)?)\s*([kmgt]?)(?:i?b)?\s*$""", RegexOption.IGNORE_CASE)

    fun parseSize(text: String): Long? {
        val m = sizeText.matchEntire(text) ?: return null
        val value = m.groupValues[1].toDoubleOrNull() ?: return null
        val mult = when (m.groupValues[2].lowercase()) {
            "" -> 1L
            "k" -> 1L shl 10
            "m" -> 1L shl 20
            "g" -> 1L shl 30
            else -> 1L shl 40
        }
        val bytes = (value * mult).roundToLong()
        return bytes.takeIf { it > 0 }
    }

    fun partSize(total: Long, method: SplitMethod): Long = when (method) {
        is SplitMethod.BySize -> method.bytes
        is SplitMethod.ByCount -> if (method.parts <= 0) 0 else (total + method.parts - 1) / method.parts
    }

    fun partCount(total: Long, partSize: Long): Int =
        if (total <= 0 || partSize <= 0) 0 else ((total + partSize - 1) / partSize).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    fun partNames(fileName: String, pattern: String, count: Int): Result<List<String>> {
        if (!numberToken.containsMatchIn(pattern)) {
            return Result.failure(IllegalArgumentException(tr(R.string.files_split_need_number)))
        }
        val item = FileItem(fileName, fileName, isDirectory = false, size = 0, lastModified = 0, isHidden = false, canRead = true, canWrite = true)
        val dates = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        val names = (0 until count).map { i -> BatchRename.expand(pattern, item, i, count, RenameInfo.NONE, dates) { it } }
        names.firstNotNullOfOrNull { FileOperations.validateName(it) }?.let { return Result.failure(IllegalArgumentException(it)) }
        if (names.map { it.lowercase() }.toSet().size != names.size) {
            return Result.failure(IllegalArgumentException(tr(R.string.files_split_same_names)))
        }
        return Result.success(names)
    }

    fun mergedName(firstPart: String): String {
        val stripped = firstPart.replace(Regex("""\.\d+$"""), "")
        return if (stripped != firstPart && stripped.isNotEmpty()) stripped else "$firstPart.merged"
    }

    fun split(
        path: String,
        destDir: String,
        method: SplitMethod,
        pattern: String = DEFAULT_PATTERN,
        from: FileAccess = LocalFileAccess,
        to: FileAccess = from,
    ): Flow<OpProgress> = flow {
        val src = from.file(path)
        val total = src.length()
        if (!src.isFile) throw IOException(tr(R.string.files_not_a_file, src.name))
        if (total <= 0) throw IOException(tr(R.string.files_split_empty))
        val size = partSize(total, method)
        if (size <= 0) throw IOException(tr(R.string.files_split_zero_size))
        val count = partCount(total, size)
        if (count < 2) throw IOException(tr(R.string.files_split_one_part))
        if (count > MAX_PARTS) throw IOException(tr(R.string.files_split_too_many, count, MAX_PARTS))
        val names = partNames(src.name, pattern, count).getOrElse { throw IOException(it.message) }

        val dest = to.file(destDir)
        val parts = names.map { to.child(dest, it) }
        parts.firstOrNull { FsUtil.exists(it, to) }?.let { throw IOException(tr(R.string.common_already_exists, it.name)) }

        val written = mutableListOf<File>()
        cleanupOnFailure(written, to) {
            from.openInput(src).buffered(BUFFER).use { input ->
                var done = 0L
                parts.forEachIndexed { i, part ->
                    written += part
                    val want = if (i == parts.lastIndex) total - done else size
                    to.openOutput(part).buffered(BUFFER).use { out ->
                        done = copyExactly(input, out, want, done, total, part.name, i, count)
                    }
                }
                if (input.read() != -1) throw IOException(tr(R.string.files_split_changed, src.name))
            }
        }
        emit(OpProgress.Finished(count, emptyList(), created = parts.map { it.absolutePath }))
    }.flowOn(Dispatchers.IO)

    fun merge(
        parts: List<String>,
        outputPath: String,
        from: FileAccess = LocalFileAccess,
        to: FileAccess = from,
    ): Flow<OpProgress> = flow {
        if (parts.size < 2) throw IOException(tr(R.string.files_merge_two_parts))
        val sources = parts.map { from.file(it) }
        sources.firstOrNull { !it.isFile }?.let { throw IOException(tr(R.string.files_not_a_file, it.name)) }
        val out = to.file(outputPath)
        if (FsUtil.exists(out, to)) throw IOException(tr(R.string.common_already_exists, out.name))
        if (sources.any { it.absolutePath == out.absolutePath }) throw IOException(tr(R.string.files_merge_output_is_part))
        val total = sources.sumOf { it.length() }

        cleanupOnFailure(mutableListOf(out), to) {
            to.openOutput(out).buffered(BUFFER).use { output ->
                var done = 0L
                sources.forEachIndexed { i, part ->
                    from.openInput(part).buffered(BUFFER).use { input ->
                        done = copyExactly(input, output, part.length(), done, total, part.name, i, sources.size)
                    }
                }
            }
        }
        emit(OpProgress.Finished(1, emptyList(), created = listOf(out.absolutePath)))
    }.flowOn(Dispatchers.IO)

    private const val BUFFER = 64 * 1024

    private suspend fun FlowCollector<OpProgress>.copyExactly(
        input: InputStream,
        out: OutputStream,
        count: Long,
        doneBefore: Long,
        total: Long,
        name: String,
        index: Int,
        parts: Int,
    ): Long {
        val buf = ByteArray(BUFFER)
        var left = count
        var done = doneBefore
        var lastEmit = -1L
        while (left > 0) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw IOException(tr(R.string.files_unexpected_end, name))
            out.write(buf, 0, n)
            left -= n
            done += n
            if (lastEmit < 0 || done - lastEmit >= PROGRESS_STEP || left == 0L) {
                lastEmit = done
                emit(OpProgress.Running(name, done, total, index, parts))
            }
        }
        return done
    }

    private const val PROGRESS_STEP = 4L * 1024 * 1024

    private inline fun cleanupOnFailure(created: List<File>, access: FileAccess, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            for (f in created) runCatching { FsUtil.deleteRecursively(f, access) }
            throw e
        }
    }
}
