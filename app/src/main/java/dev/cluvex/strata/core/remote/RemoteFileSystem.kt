package dev.cluvex.strata.core.remote

import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.vault.Vault
import dev.cluvex.strata.core.vault.VaultFileSystem
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class RemoteAuthException(message: String) : IOException(message)

class RemoteConnectException(message: String, cause: Throwable? = null) : IOException(message, cause)

interface RemoteFileSystem : Closeable {
    fun connect()

    fun list(path: String): List<RemoteEntry>

    fun stat(path: String): RemoteEntry?

    fun openInput(path: String): InputStream

    fun download(path: String, into: OutputStream, onBytes: (Long) -> Unit = {})

    fun upload(from: InputStream, path: String, onBytes: (Long) -> Unit = {})

    fun mkdir(path: String)

    fun delete(path: String, recursive: Boolean)

    fun rename(from: String, to: String)

    fun newlyTrustedHostKey(): String? = null

    val isConnected: Boolean get() = true
}

class RemotePasswordNeededException(val connection: RemoteConnection) :
    IOException(tr(R.string.remote_password_needed, connection.name.ifBlank { connection.host }))

class RemoteHostKeyChangedException(message: String, val presented: String? = null) : IOException(message)

object RemoteFileSystems {
    fun create(connection: RemoteConnection): RemoteFileSystem = when (connection.protocol) {
        RemoteProtocol.FTP, RemoteProtocol.FTPS -> FtpFileSystem(connection)
        RemoteProtocol.SFTP -> SftpFileSystem(connection)
        RemoteProtocol.SMB -> SmbFileSystem(connection)
        RemoteProtocol.WEBDAV, RemoteProtocol.WEBDAVS -> WebDavFileSystem(connection)
        RemoteProtocol.SAF -> SafFileSystem(connection, SafFileSystem.appContext ?: throw IOException(tr(R.string.remote_saf_not_here)))
        RemoteProtocol.VAULT -> VaultFileSystem(Vault.store)
    }
}
