package dev.cluvex.strata.core.props

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants

class OsPosixApi : PosixApi {

    override fun lstat(path: String): PosixStat {
        val st = Os.lstat(path)
        return PosixStat(
            mode = st.st_mode,
            uid = st.st_uid,
            gid = st.st_gid,
            size = st.st_size,
            mtimeMillis = st.st_mtime * 1000L,
            isDirectory = OsConstants.S_ISDIR(st.st_mode),
            isSymlink = OsConstants.S_ISLNK(st.st_mode),
        )
    }

    override fun chmod(path: String, mode: Int) {
        Os.chmod(path, mode and PermissionMode.PERMISSION_MASK)
    }

    override fun chown(path: String, uid: Int, gid: Int) {
        Os.chown(path, uid, gid)
    }

    override fun readlink(path: String): String? = try {
        Os.readlink(path)
    } catch (_: ErrnoException) {
        null
    }

    override fun userName(uid: Int): String = AndroidIds.name(uid)

    override fun groupName(gid: Int): String = AndroidIds.name(gid)
}
