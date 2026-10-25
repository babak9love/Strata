package dev.cluvex.strata.core.remote

import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

class FtpFileSystem(private val connection: RemoteConnection) : RemoteFileSystem {

    private val client: FTPClient = if (connection.protocol == RemoteProtocol.FTPS) {
        FTPSClient(false)
    } else {
        FTPClient()
    }

    override fun connect() {
        client.connectTimeout = 15_000
        client.controlEncoding = "UTF-8"
        client.autodetectUTF8 = true
        try {
            client.connect(connection.host, connection.port)
        } catch (e: IOException) {
            throw when (e) {
                is java.net.ConnectException, is java.net.UnknownHostException, is java.net.SocketTimeoutException ->
                    RemoteConnectException(tr(R.string.remote_cant_reach, "${connection.host}:${connection.port}"), e)
                else -> RemoteConnectException(tr(R.string.remote_couldnt_connect_reason, e.message ?: e.javaClass.simpleName), e)
            }
        }
        if (!FTPReply.isPositiveCompletion(client.replyCode)) {
            silently { client.disconnect() }
            throw RemoteConnectException(tr(R.string.remote_server_refused, client.replyCode))
        }
        val user = if (connection.anonymous) "anonymous" else connection.username
        val pass = if (connection.anonymous) "anonymous@" else connection.password
        val ok = try {
            client.login(user, pass)
        } catch (e: IOException) {
            silently { client.disconnect() }
            throw RemoteConnectException(tr(R.string.remote_login_failed), e)
        }
        if (!ok) {
            silently { client.logout() }
            silently { client.disconnect() }
            throw RemoteAuthException(if (connection.anonymous) tr(R.string.remote_anonymous_refused) else tr(R.string.remote_wrong_login))
        }
        if (client is FTPSClient) {
            client.execPBSZ(0)
            client.execPROT("P")
        }
        client.enterLocalPassiveMode()
        client.setFileType(FTP.BINARY_FILE_TYPE)
    }

    override fun list(path: String): List<RemoteEntry> {
        val dir = RemotePaths.normalize(path)
        val files = client.listFiles(dir) ?: emptyArray()
        checkReply(dir)
        return files
            .filter { it != null && it.name != "." && it.name != ".." }
            .map { it.toEntry(RemotePaths.join(dir, it.name)) }
    }

    override fun stat(path: String): RemoteEntry? {
        val p = RemotePaths.normalize(path)
        if (p == "/") return RemoteEntry("/", "/", isDirectory = true, size = 0, modified = 0)
        runCatching { client.mlistFile(p) }.getOrNull()?.let { return it.toEntry(p) }
        val name = RemotePaths.name(p)
        return runCatching { client.listFiles(RemotePaths.parent(p)) }.getOrNull()
            ?.firstOrNull { it != null && it.name == name }
            ?.toEntry(p)
    }

    override fun openInput(path: String): InputStream {
        val p = RemotePaths.normalize(path)
        val stream = client.retrieveFileStream(p)
            ?: throw IOException(tr(R.string.remote_open_failed_reply, p, client.replyString?.trim()))
        return object : FilterInputStream(stream) {
            private var completed = false
            override fun close() {
                super.close()
                if (!completed) {
                    completed = true
                    silently { client.completePendingCommand() }
                }
            }
        }
    }

    override fun download(path: String, into: OutputStream, onBytes: (Long) -> Unit) {
        val counting = object : FilterOutputStream(into) {
            var count = 0L
            override fun write(b: Int) { super.write(b); onBytes(++count) }
            override fun write(b: ByteArray, off: Int, len: Int) {
                out.write(b, off, len); count += len; onBytes(count)
            }
        }
        if (!client.retrieveFile(RemotePaths.normalize(path), counting)) {
            throw IOException(tr(R.string.remote_download_failed_reply, client.replyString?.trim()))
        }
    }

    override fun upload(from: InputStream, path: String, onBytes: (Long) -> Unit) {
        val counting = object : FilterInputStream(from) {
            var count = 0L
            override fun read(): Int = super.read().also { if (it >= 0) onBytes(++count) }
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                super.read(b, off, len).also { if (it > 0) { count += it; onBytes(count) } }
        }
        if (!client.storeFile(RemotePaths.normalize(path), counting)) {
            throw IOException(tr(R.string.remote_upload_failed_reply, client.replyString?.trim()))
        }
    }

    override fun mkdir(path: String) {
        if (!client.makeDirectory(RemotePaths.normalize(path))) {
            throw IOException(tr(R.string.remote_mkdir_failed_reply, client.replyString?.trim()))
        }
    }

    override fun delete(path: String, recursive: Boolean) {
        val p = RemotePaths.normalize(path)
        val entry = stat(p)
        if (entry?.isDirectory == true) {
            if (recursive) list(p).forEach { delete(it.path, recursive = true) }
            if (!client.removeDirectory(p)) throw IOException(tr(R.string.remote_delete_folder_failed_reply, client.replyString?.trim()))
        } else {
            if (!client.deleteFile(p)) throw IOException(tr(R.string.remote_delete_file_failed_reply, client.replyString?.trim()))
        }
    }

    override fun rename(from: String, to: String) {
        if (!client.rename(RemotePaths.normalize(from), RemotePaths.normalize(to))) {
            throw IOException(tr(R.string.remote_rename_failed_reply, client.replyString?.trim()))
        }
    }

    override val isConnected: Boolean get() = client.isConnected && client.isAvailable

    override fun close() {
        silently { if (client.isConnected) client.logout() }
        silently { if (client.isConnected) client.disconnect() }
    }

    private fun checkReply(dir: String) {
        if (!FTPReply.isPositiveCompletion(client.replyCode) && client.replyCode != 0) {
            throw IOException(tr(R.string.remote_list_failed_reply, dir, client.replyString?.trim()))
        }
    }

    private fun FTPFile.toEntry(fullPath: String) = RemoteEntry(
        name = name,
        path = fullPath,
        isDirectory = isDirectory,
        size = size.coerceAtLeast(0),
        modified = timestamp?.timeInMillis ?: 0,
        isSymlink = isSymbolicLink,
    )

    private inline fun silently(block: () -> Unit) = try { block() } catch (_: Exception) {}
}
