package dev.cluvex.strata.core.root

import java.io.File

object RootPaths {
    fun isRestrictedShared(path: String, storageRoot: String): Boolean {
        val base = storageRoot.trimEnd('/')
        val p = path.trimEnd('/')
        return listOf("$base/Android/data", "$base/Android/obb").any { p == it || p.startsWith("$it/") }
    }

    fun needsRoot(dir: File, storageRoot: String, write: Boolean): Boolean =
        isRestrictedShared(dir.absolutePath, storageRoot) || !dir.canRead() || (write && !dir.canWrite())
}
