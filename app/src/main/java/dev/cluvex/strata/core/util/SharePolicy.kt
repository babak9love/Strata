package dev.cluvex.strata.core.util

import dev.cluvex.strata.core.fs.FsUtil
import java.io.File

object SharePolicy {
    fun mayShare(file: File, privateDirs: List<File>, cacheDirs: List<File>): Boolean =
        privateDirs.none { FsUtil.isSameOrDescendant(it, file) } || cacheDirs.any { FsUtil.isSameOrDescendant(it, file) }
}
