package dev.cluvex.strata.core.vault

import dev.cluvex.strata.core.remote.RemoteEntry
import dev.cluvex.strata.core.remote.RemoteFileSystem
import dev.cluvex.strata.core.remote.RemotePaths
import java.io.InputStream
import java.io.OutputStream

class VaultFileSystem(private val store: VaultStore) : RemoteFileSystem {

    override fun connect() {
        if (!store.isUnlocked) throw VaultLockedException()
    }

    override fun list(path: String): List<RemoteEntry> = store.list(path).map(::entry)

    override fun stat(path: String): RemoteEntry? = store.stat(path)?.let(::entry)

    override fun openInput(path: String): InputStream = store.openInput(path)

    override fun download(path: String, into: OutputStream, onBytes: (Long) -> Unit) {
        store.read(path, into, onBytes)
    }

    override fun upload(from: InputStream, path: String, onBytes: (Long) -> Unit) {
        store.write(path, from, onBytes = onBytes)
    }

    override fun mkdir(path: String) = store.mkdir(path)

    override fun delete(path: String, recursive: Boolean) = store.delete(path, recursive)

    override fun rename(from: String, to: String) = store.rename(from, to)

    override val isConnected: Boolean get() = store.isUnlocked

    override fun close() {}

    private fun entry(e: VaultEntry): RemoteEntry =
        RemoteEntry(if (e.path == "/") "" else RemotePaths.name(e.path), e.path, e.dir, if (e.dir) 0 else e.size, e.modified)
}
