package dev.cluvex.strata.core.remote

import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap

class RemoteSessions(
    private val lookup: suspend (String) -> RemoteConnection?,
    private val onTrusted: suspend (RemoteConnection) -> Unit = {},
    private val factory: (RemoteConnection) -> RemoteFileSystem = RemoteFileSystems::create,
) {
    private class Session(var connection: RemoteConnection) {
        var fs: RemoteFileSystem? = null
        val lock = Mutex()
    }

    private val sessions = ConcurrentHashMap<String, Session>()

    suspend fun name(connectionId: String): String? = session(connectionId)?.connection?.let { it.name.ifBlank { it.host } }

    suspend fun <T> use(connectionId: String, block: suspend (RemoteFileSystem) -> T): T {
        val session = session(connectionId) ?: throw IOException(tr(R.string.remote_storage_gone))
        return session.lock.withLock {
            val fs = session.fs?.takeIf { it.isConnected } ?: connect(session)
            try {
                block(fs)
            } catch (e: IOException) {
                if (!dropped(fs, e)) throw e
                runCatching { fs.close() }
                session.fs = null
                block(connect(session))
            }
        }
    }

    suspend fun unlock(connectionId: String, password: String) = reconfigure(connectionId) { it.copy(password = password) }

    suspend fun trust(connectionId: String, presented: String) = reconfigure(connectionId) { it.copy(hostKey = presented) }

    private suspend fun reconfigure(connectionId: String, change: (RemoteConnection) -> RemoteConnection) {
        val session = session(connectionId) ?: return
        session.lock.withLock {
            session.fs?.let { runCatching { it.close() } }
            session.fs = null
            session.connection = change(session.connection)
        }
    }

    fun forget(connectionId: String) {
        sessions.remove(connectionId)?.fs?.let { runCatching { it.close() } }
    }

    fun closeAll() {
        sessions.values.forEach { s -> s.fs?.let { runCatching { it.close() } } }
        sessions.clear()
    }

    private suspend fun session(id: String): Session? =
        sessions[id] ?: lookup(id)?.let { c -> sessions.getOrPut(id) { Session(c) } }

    private suspend fun connect(session: Session): RemoteFileSystem {
        val c = session.connection
        if (!c.anonymous && c.password.isEmpty()) throw RemotePasswordNeededException(c)
        val fs = factory(c)
        try {
            fs.connect()
        } catch (e: Exception) {
            runCatching { fs.close() }
            throw e
        }
        fs.newlyTrustedHostKey()?.let { key ->
            session.connection = c.copy(hostKey = key)
            onTrusted(session.connection)
        }
        session.fs = fs
        return fs
    }

    private fun dropped(fs: RemoteFileSystem, e: IOException): Boolean =
        !fs.isConnected || e is SocketException || e.javaClass.simpleName.contains("ConnectionClosed")
}
