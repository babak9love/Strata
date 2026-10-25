package dev.cluvex.strata.core.archive

import dev.cluvex.strata.R
import dev.cluvex.strata.core.fs.FileAccess
import dev.cluvex.strata.core.fs.FsUtil
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.nativebridge.NativeBridge
import java.io.File
import java.io.IOException

object RarArchive {
    const val FLAG_ENCRYPTED = 0x4
    const val FLAG_DIRECTORY = 0x20
    const val FLAG_LINK = 0x1000

    const val ARCHIVE_ENCRYPTED_HEADERS = 0x80

    const val ERAR_NO_MEMORY = 11
    const val ERAR_BAD_DATA = 12
    const val ERAR_BAD_ARCHIVE = 13
    const val ERAR_UNKNOWN_FORMAT = 14
    const val ERAR_EOPEN = 15
    const val ERAR_MISSING_PASSWORD = 22
    const val ERAR_BAD_PASSWORD = 24
    const val ERAR_LARGE_DICT = 25
    const val CANCELLED = 100
    const val SINK_ERROR = 101

    fun isRar(name: String): Boolean = name.lowercase().endsWith(".rar")

    private val PART = Regex("^(.*)\\.part(\\d+)\\.rar$", RegexOption.IGNORE_CASE)

    fun baseName(name: String): String {
        PART.matchEntire(name)?.let { return it.groupValues[1] }
        return if (isRar(name)) name.dropLast(4) else name
    }

    fun firstVolume(file: File): File {
        val m = PART.matchEntire(file.name) ?: return file
        val digits = m.groupValues[2]
        val first = File(file.parentFile, "${m.groupValues[1]}.part${"1".padStart(digits.length, '0')}.rar")
        return if (first != file && first.exists()) first else file
    }

    internal fun volumes(first: File): List<File> {
        val m = PART.matchEntire(first.name) ?: return listOf(first)
        val base = m.groupValues[1]
        val siblings = first.parentFile?.listFiles().orEmpty()
        return siblings.filter { f -> PART.matchEntire(f.name)?.groupValues?.get(1) == base }.sortedBy { it.name }
    }

    class Readable(val path: String, private val staged: File?) {
        fun cleanup() {
            staged?.let { FsUtil.deleteRecursively(it) }
        }
    }

    fun readable(file: File, from: FileAccess): Readable {
        val first = firstVolume(file)
        if (first.canRead()) return Readable(first.path, null)
        val dir = File(System.getProperty("java.io.tmpdir") ?: "/tmp", "rar-stage-${System.nanoTime()}").apply { mkdirs() }
        try {
            for (part in volumes(first)) from.openInput(part).use { input -> File(dir, part.name).outputStream().use { input.copyTo(it) } }
        } catch (e: IOException) {
            FsUtil.deleteRecursively(dir)
            throw e
        }
        return Readable(File(dir, first.name).path, dir)
    }

    data class Header(val name: String, val flags: Int, val size: Long, val packed: Long, val mtime: Long) {
        val isDirectory get() = flags and FLAG_DIRECTORY != 0
        val isLink get() = flags and FLAG_LINK != 0
        val encrypted get() = flags and FLAG_ENCRYPTED != 0

        fun toEntry() = ArchiveEntry(normalize(name), isDirectory, size, packed, mtime, encrypted)
    }

    internal fun parseHeader(line: String): Header? {
        val f = line.split('\u0000')
        if (f.size < 5) return null
        return Header(f[0], f[1].toIntOrNull() ?: return null, f[2].toLongOrNull() ?: 0, f[3].toLongOrNull() ?: -1, f[4].toLongOrNull() ?: 0)
    }

    internal fun normalize(name: String): String = name.replace('\\', '/').trimStart('/')

    fun list(path: String, password: CharArray?, displayName: String = File(path).name): List<Header> {
        val status = IntArray(2)
        val pw = password?.takeIf { it.isNotEmpty() }?.let(::String)
        val lines = NativeBridge.rarHeaders(path, pw, status)
            ?: throw failure(status[0], displayName, passwordGiven = pw != null, encrypted = true)
        return lines.mapNotNull(::parseHeader).filterNot { it.isLink }
    }

    internal fun failure(code: Int, name: String, passwordGiven: Boolean, encrypted: Boolean): Exception = when (code) {
        ERAR_MISSING_PASSWORD -> PasswordRequiredException(name)
        ERAR_BAD_PASSWORD -> WrongPasswordException(name)
        ERAR_BAD_DATA -> if (passwordGiven && encrypted) WrongPasswordException(name) else IOException(tr(R.string.archive_rar_damaged, name))
        ERAR_EOPEN -> IOException(tr(R.string.archive_rar_cant_open, name))
        ERAR_BAD_ARCHIVE, ERAR_UNKNOWN_FORMAT -> IOException(tr(R.string.archive_rar_not_rar, name))
        ERAR_LARGE_DICT -> IOException(tr(R.string.archive_rar_dictionary, name))
        ERAR_NO_MEMORY -> IOException(tr(R.string.archive_rar_memory, name))
        CANCELLED -> kotlinx.coroutines.CancellationException("Cancelled")
        -1 -> IOException(tr(R.string.archive_rar_unavailable))
        else -> IOException(tr(R.string.archive_rar_error, name, code))
    }
}
