package dev.cluvex.strata.core.props

data class PosixStat(
    val mode: Int,
    val uid: Int,
    val gid: Int,
    val size: Long,
    val mtimeMillis: Long,
    val isDirectory: Boolean,
    val isSymlink: Boolean,
)

interface PosixApi {
    fun lstat(path: String): PosixStat
    fun chmod(path: String, mode: Int)
    fun chown(path: String, uid: Int, gid: Int)
    fun readlink(path: String): String?
    fun userName(uid: Int): String?
    fun groupName(gid: Int): String?
}
