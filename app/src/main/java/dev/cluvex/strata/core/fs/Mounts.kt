package dev.cluvex.strata.core.fs

import java.io.File

data class MountEntry(val device: String, val point: String, val type: String, val options: List<String>) {
    val readOnly: Boolean get() = "ro" in options
}

object Mounts {
    val SYSTEM_POINTS = setOf("/", "/system", "/system_ext", "/vendor", "/product", "/odm", "/oem", "/system_root")

    private val READ_ONLY_TYPES = setOf("erofs", "squashfs", "iso9660")

    fun parse(text: String): List<MountEntry> = text.lineSequence().mapNotNull { line ->
        val f = line.trim().split(Regex("\\s+"))
        if (f.size < 4) null else MountEntry(unescape(f[0]), unescape(f[1]), f[2], f[3].split(','))
    }.toList()

    private fun unescape(s: String): String = s.replace(Regex("\\\\([0-7]{3})")) { it.groupValues[1].toInt(8).toChar().toString() }

    fun mountOf(file: File, entries: List<MountEntry>): MountEntry? =
        mountOf(runCatching { file.canonicalPath }.getOrDefault(file.absolutePath), entries)

    fun readOnly(folder: File, entries: List<MountEntry>, viaRoot: Boolean): Boolean {
        val m = mountOf(folder, entries)
        val roFileSystem = m != null && m.readOnly && m.type !in STORAGE_VIEWS
        return roFileSystem || (!viaRoot && !folder.canWrite())
    }

    private val STORAGE_VIEWS = setOf("fuse", "fuseblk", "sdcardfs", "esdfs")

    fun mountOf(path: String, entries: List<MountEntry>): MountEntry? {
        val p = path.trimEnd('/').ifEmpty { "/" }
        return entries.asReversed()
            .filter { m -> m.point == "/" || p == m.point || p.startsWith(m.point.trimEnd('/') + "/") }
            .maxByOrNull { it.point.length }
    }

    fun canRemountRw(m: MountEntry): Boolean = m.point in SYSTEM_POINTS && m.type !in READ_ONLY_TYPES

    fun isReadOnlyType(m: MountEntry): Boolean = m.type in READ_ONLY_TYPES

    fun remountCommand(m: MountEntry, writable: Boolean): String =
        "mount -o ${if (writable) "rw" else "ro"},remount ${shellQuote(m.point)}"

    private fun shellQuote(s: String) = "'" + s.replace("'", "'\\''") + "'"
}
