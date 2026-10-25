package dev.cluvex.strata.core.fs

import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.model.FileItem
import dev.cluvex.strata.core.remote.RemoteUri
import dev.cluvex.strata.core.root.RootPaths
import java.io.IOException

data class Listing(val items: List<FileItem>, val viaRoot: Boolean)

class DirectoryLister(
    private val storageRoot: String,
    private val local: suspend (String) -> List<FileItem>?,
    private val root: suspend (String) -> List<FileItem>?,
    private val missing: suspend (String) -> Boolean = { false },
    private val fallback: suspend (String) -> List<FileItem>? = { null },
    private val remote: (suspend (String) -> List<FileItem>)? = null,
) {
    suspend fun list(path: String, rootEnabled: Boolean): Listing {
        if (RemoteUri.isRemote(path)) {
            val listRemote = remote ?: throw IOException(tr(R.string.files_list_no_network))
            return Listing(listRemote(path), viaRoot = false)
        }
        val preferRoot = rootEnabled && RootPaths.isRestrictedShared(path, storageRoot)
        if (!preferRoot) local(path)?.let { return Listing(it, viaRoot = false) }
        if (rootEnabled) root(path)?.let { return Listing(it, viaRoot = true) }
        if (preferRoot) local(path)?.let { return Listing(it, viaRoot = false) }
        fallback(path)?.let { return Listing(it, viaRoot = false) }
        if (missing(path)) throw IOException(tr(R.string.files_list_missing))
        throw IOException(tr(if (rootEnabled) R.string.files_list_unreadable else R.string.files_list_denied))
    }
}
