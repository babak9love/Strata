package dev.cluvex.strata.core.remote

import android.annotation.SuppressLint
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class SafFileSystem(private val c: RemoteConnection, context: Context) : RemoteFileSystem {

    private val resolver = context.applicationContext.contentResolver
    private val tree: Uri = Uri.parse(c.host)
    private val rootId: String = DocumentsContract.getTreeDocumentId(tree)
    private val isTermux = tree.authority == TERMUX_AUTHORITY
    private val ids = HashMap<String, String>()
    private var connected = false

    override fun connect() {
        val probe = runCatching { query(DocumentsContract.buildDocumentUriUsingTree(tree, rootId)) { it.moveToFirst() } }
        if (probe.isFailure || probe.getOrNull() != true) {
            val e = probe.exceptionOrNull()
            throw if (e is SecurityException) {
                RemoteAuthException(tr(R.string.remote_saf_no_access))
            } else {
                RemoteConnectException(e?.message?.let { tr(R.string.remote_saf_unavailable, it) } ?: tr(R.string.remote_saf_unavailable_silent), e)
            }
        }
        ids.clear()
        ids["/"] = rootId
        connected = true
    }

    override val isConnected: Boolean get() = connected

    private fun docUri(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)

    private fun <T> query(uri: Uri, read: (Cursor) -> T): T =
        (resolver.query(uri, COLUMNS, null, null, null) ?: throw IOException(tr(R.string.remote_provider_no_answer))).use(read)

    private fun entry(cur: Cursor, path: String): RemoteEntry = RemoteEntry(
        name = RemotePaths.name(path).ifEmpty { "/" },
        path = path,
        isDirectory = cur.getString(2) == Document.MIME_TYPE_DIR,
        size = if (cur.isNull(3)) 0 else cur.getLong(3),
        modified = if (cur.isNull(4)) 0 else cur.getLong(4),
    )

    private fun listChildren(path: String, id: String): List<RemoteEntry> {
        val out = ArrayList<RemoteEntry>()
        query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)) { cur ->
            while (cur.moveToNext()) {
                val name = cur.getString(1) ?: continue
                val childPath = RemotePaths.join(path, name)
                ids[childPath] = cur.getString(0)
                out += entry(cur, childPath)
            }
        }
        return out
    }

    private fun idOf(path: String): String? {
        val n = RemotePaths.normalize(path)
        ids[n]?.let { return it }
        if (n == "/") return rootId
        val parent = RemotePaths.parent(n)
        val parentId = idOf(parent) ?: return null
        listChildren(parent, parentId)
        return ids[n]
    }

    private fun need(path: String): String = idOf(path) ?: throw FileNotFoundException(tr(R.string.remote_no_such_path, path))

    private fun forget(path: String) {
        val n = RemotePaths.normalize(path)
        ids.keys.removeAll { it == n || it.startsWith("$n/") }
    }

    override fun list(path: String): List<RemoteEntry> {
        val n = RemotePaths.normalize(path)
        return listChildren(n, need(n))
    }

    override fun stat(path: String): RemoteEntry? {
        val n = RemotePaths.normalize(path)
        val id = idOf(n) ?: return null
        return runCatching { query(docUri(id)) { cur -> if (cur.moveToFirst()) entry(cur, n) else null } }.getOrNull()
    }

    override fun openInput(path: String): InputStream =
        resolver.openInputStream(docUri(need(path))) ?: throw IOException(tr(R.string.remote_cant_open, path))

    override fun download(path: String, into: OutputStream, onBytes: (Long) -> Unit) {
        openInput(path).use { copy(it, into, onBytes) }
    }

    override fun upload(from: InputStream, path: String, onBytes: (Long) -> Unit) {
        val n = RemotePaths.normalize(path)
        val existing = idOf(n)
        val uri = if (existing != null) {
            docUri(existing)
        } else {
            val parentId = need(RemotePaths.parent(n))
            val created = DocumentsContract.createDocument(resolver, docUri(parentId), "application/octet-stream", RemotePaths.name(n))
                ?: throw IOException(tr(R.string.remote_provider_cant_create, RemotePaths.name(n)))
            ids[n] = DocumentsContract.getDocumentId(created)
            created
        }
        val out = runCatching { resolver.openOutputStream(uri, "wt") }.getOrNull()
            ?: resolver.openOutputStream(uri, "w") ?: throw IOException(tr(R.string.remote_cant_write, path))
        out.use { copy(from, it, onBytes) }
    }

    override fun mkdir(path: String) {
        val n = RemotePaths.normalize(path)
        if (idOf(n) != null) throw IOException(tr(R.string.remote_already_exists))
        val created = DocumentsContract.createDocument(resolver, docUri(need(RemotePaths.parent(n))), Document.MIME_TYPE_DIR, RemotePaths.name(n))
            ?: throw IOException(tr(R.string.remote_provider_cant_mkdir))
        ids[n] = DocumentsContract.getDocumentId(created)
    }

    override fun delete(path: String, recursive: Boolean) {
        val n = RemotePaths.normalize(path)
        need(n) // ensure path resolves before we walk children
        val isDir = stat(n)?.isDirectory == true
        if (isDir) {
            val children = list(n)
            if (!recursive && children.isNotEmpty()) {
                throw IOException(tr(R.string.remote_folder_not_empty))
            }
            // Termux (and some other SAF providers) refuse a single deleteDocument on a
            // non-empty folder. Remove children first, depth-first, then the folder.
            if (recursive) {
                for (child in children) {
                    delete(child.path, recursive = true)
                }
            }
        }
        if (!DocumentsContract.deleteDocument(resolver, docUri(need(n)))) {
            throw IOException(tr(R.string.remote_provider_refused_delete, path))
        }
        forget(n)
    }

    @SuppressLint("NewApi")
    override fun rename(from: String, to: String) {
        val src = RemotePaths.normalize(from)
        val dst = RemotePaths.normalize(to)
        // Termux's provider never supports rename/move ("Rename not supported"), so go straight to copy + delete.
        if (isTermux) {
            if (src != dst) renameByCopy(src, dst)
            return
        }
        val id = need(src)
        var uri = docUri(id)
        val srcParent = RemotePaths.parent(src)
        val dstParent = RemotePaths.parent(dst)
        if (srcParent != dstParent) {
            uri = DocumentsContract.moveDocument(resolver, uri, docUri(need(srcParent)), docUri(need(dstParent)))
                ?: throw IOException(tr(R.string.remote_provider_cant_move))
        }
        if (RemotePaths.name(src) != RemotePaths.name(dst)) {
            uri = DocumentsContract.renameDocument(resolver, uri, RemotePaths.name(dst)) ?: uri
        }
        forget(src)
        forget(dst)
        ids[dst] = DocumentsContract.getDocumentId(uri)
    }

    /** Termux only: its provider has no rename/move, so copy to the new name, then delete the original. */
    private fun renameByCopy(src: String, dst: String) {
        val entry = stat(src) ?: throw FileNotFoundException(tr(R.string.remote_no_such_path, src))
        if (idOf(dst) != null) throw IOException(tr(R.string.remote_already_exists))
        if (entry.isDirectory) {
            mkdir(dst)
            for (child in list(src)) {
                renameByCopy(child.path, RemotePaths.normalize("${dst.trimEnd('/')}/${RemotePaths.name(child.path)}"))
            }
            delete(src, recursive = false)
        } else {
            openInput(src).use { upload(it, dst) }
            delete(src, recursive = false)
        }
    }

    override fun close() {
        connected = false
        ids.clear()
    }

    private fun copy(input: InputStream, out: OutputStream, onBytes: (Long) -> Unit) {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
            onBytes(total)
        }
    }

    companion object {
        private val COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED,
        )

        @Volatile
        var appContext: Context? = null

        const val TERMUX_AUTHORITY = "com.termux.documents"
        private const val TERMUX_HOME = "/data/data/com.termux/files/home"

        fun termuxHome(context: Context): Uri? = runCatching {
            context.packageManager.getPackageInfo("com.termux", 0)
            DocumentsContract.buildDocumentUri(TERMUX_AUTHORITY, TERMUX_HOME)
        }.getOrNull()

        /** True when [oldHost] (a stored tree URI) points at the same storage as [new]: any Termux tree, or an identical authority + tree id. */
        fun sameStorage(oldHost: String, new: Uri): Boolean {
            val old = runCatching { Uri.parse(oldHost) }.getOrNull() ?: return false
            if (old.authority != new.authority) return false
            if (new.authority == TERMUX_AUTHORITY) return true
            return runCatching {
                DocumentsContract.getTreeDocumentId(old) == DocumentsContract.getTreeDocumentId(new)
            }.getOrDefault(false)
        }

        fun label(context: Context, tree: Uri): String {
            if (tree.authority == TERMUX_AUTHORITY) return "Termux"
            val resolver = context.contentResolver
            val docName = runCatching {
                val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
                resolver.query(root, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull()
            val appName = runCatching {
                @Suppress("DEPRECATION")
                context.packageManager.resolveContentProvider(tree.authority ?: "", 0)?.loadLabel(context.packageManager)?.toString()
            }.getOrNull()
            return docName?.takeIf { it.isNotBlank() } ?: appName?.takeIf { it.isNotBlank() } ?: tr(RemoteProtocol.SAF.label)
        }

        fun release(context: Context, treeUri: String) {
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(treeUri),
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
    }
}
