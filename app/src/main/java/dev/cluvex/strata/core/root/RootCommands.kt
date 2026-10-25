package dev.cluvex.strata.core.root

import dev.cluvex.strata.core.props.PermissionMode
import dev.cluvex.strata.core.props.PosixStat
import java.util.Locale

object RootCommands {
    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    fun stat(path: String): String = "stat -c '%f %u %g %s %Y' ${quote(path)}"

    fun chmod(path: String, mode: Int): String =
        "chmod ${String.format(Locale.US, "%04o", mode and PermissionMode.PERMISSION_MASK)} ${quote(path)}"

    fun chown(path: String, uid: Int, gid: Int): String = "chown $uid:$gid ${quote(path)}"

    fun readlink(path: String): String = "readlink ${quote(path)}"

    fun parseStat(line: String): PosixStat? {
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size < 5) return null
        val mode = parts[0].toIntOrNull(16) ?: return null
        val uid = parts[1].toIntOrNull() ?: return null
        val gid = parts[2].toIntOrNull() ?: return null
        val size = parts[3].toLongOrNull() ?: return null
        val mtime = parts[4].toLongOrNull() ?: return null
        val type = mode and 0xF000
        return PosixStat(
            mode = mode,
            uid = uid,
            gid = gid,
            size = size,
            mtimeMillis = mtime * 1000L,
            isDirectory = type == 0x4000,
            isSymlink = type == 0xA000,
        )
    }
}
