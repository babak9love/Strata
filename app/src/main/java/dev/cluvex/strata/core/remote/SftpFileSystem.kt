package dev.cluvex.strata.core.remote

import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.PublicKey
import java.util.EnumSet

class SftpFileSystem(private val connection: RemoteConnection) : RemoteFileSystem {

    private var sshClient: SSHClient? = null
    private var sftp: SFTPClient? = null
    private var captured: String? = null
    private var keyRejected = false
    private var presented: String? = null

    override fun connect() {
        CryptoProviders.ensureBouncyCastle()
        val ssh = SSHClient().also { sshClient = it }
        ssh.connectTimeout = 15_000
        ssh.timeout = 30_000
        ssh.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                val fingerprint = sha256(key.encoded)
                if (connection.hostKey.isBlank()) {
                    captured = fingerprint
                    return true
                }
                return (connection.hostKey == fingerprint).also { ok -> if (!ok) { keyRejected = true; presented = fingerprint } }
            }

            override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
        })
        try {
            ssh.connect(connection.host, connection.port)
        } catch (e: IOException) {
            silently { ssh.disconnect() }
            if (keyRejected) throw RemoteHostKeyChangedException(tr(R.string.remote_host_key_changed), presented)
            throw when (e) {
                is java.net.ConnectException, is java.net.UnknownHostException, is java.net.SocketTimeoutException ->
                    RemoteConnectException(tr(R.string.remote_cant_reach, "${connection.host}:${connection.port}"), e)
                else -> RemoteConnectException(tr(R.string.remote_couldnt_connect_reason, rootMessage(e)), e)
            }
        }
        try {
            val user = if (connection.anonymous) "anonymous" else connection.username
            ssh.authPassword(user, connection.password)
        } catch (e: UserAuthException) {
            silently { ssh.disconnect() }
            throw RemoteAuthException(tr(R.string.remote_wrong_login))
        } catch (e: IOException) {
            silently { ssh.disconnect() }
            throw RemoteConnectException(tr(R.string.remote_auth_failed), e)
        }
        sftp = ssh.newSFTPClient()
    }

    private fun rootMessage(e: Throwable): String {
        var t: Throwable = e
        while (t.cause != null && t.cause !== t) t = t.cause!!
        return t.message ?: t.javaClass.simpleName
    }

    override fun newlyTrustedHostKey(): String? = captured

    override val isConnected: Boolean get() = sftp != null && sshClient?.isConnected == true

    override fun list(path: String): List<RemoteEntry> {
        val dir = RemotePaths.normalize(path)
        return client().ls(dir)
            .filter { it.name != "." && it.name != ".." }
            .map { info ->
                val attrs = info.attributes
                RemoteEntry(
                    name = info.name,
                    path = RemotePaths.join(dir, info.name),
                    isDirectory = info.isDirectory,
                    size = attrs.size.coerceAtLeast(0),
                    modified = attrs.mtime * 1000L,
                    isSymlink = info.attributes.type == net.schmizz.sshj.sftp.FileMode.Type.SYMLINK,
                )
            }
    }

    override fun stat(path: String): RemoteEntry? {
        val p = RemotePaths.normalize(path)
        if (p == "/") return RemoteEntry("/", "/", isDirectory = true, size = 0, modified = 0)
        return runCatching {
            val a = client().stat(p)
            RemoteEntry(
                name = RemotePaths.name(p),
                path = p,
                isDirectory = a.type == net.schmizz.sshj.sftp.FileMode.Type.DIRECTORY,
                size = a.size.coerceAtLeast(0),
                modified = a.mtime * 1000L,
                isSymlink = a.type == net.schmizz.sshj.sftp.FileMode.Type.SYMLINK,
            )
        }.getOrNull()
    }

    override fun openInput(path: String): InputStream {
        val remote: RemoteFile = client().open(RemotePaths.normalize(path), EnumSet.of(OpenMode.READ))
        val stream = remote.RemoteFileInputStream()
        return object : FilterInputStream(stream) {
            override fun close() {
                super.close()
                silently { remote.close() }
            }
        }
    }

    override fun download(path: String, into: OutputStream, onBytes: (Long) -> Unit) {
        client().open(RemotePaths.normalize(path), EnumSet.of(OpenMode.READ)).use { remote ->
            remote.RemoteFileInputStream().use { input -> copy(input, into, onBytes) }
        }
    }

    override fun upload(from: InputStream, path: String, onBytes: (Long) -> Unit) {
        val modes = EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)
        client().open(RemotePaths.normalize(path), modes).use { remote ->
            remote.RemoteFileOutputStream().use { output -> copy(from, output, onBytes) }
        }
    }

    override fun mkdir(path: String) = client().mkdir(RemotePaths.normalize(path))

    override fun delete(path: String, recursive: Boolean) {
        val p = RemotePaths.normalize(path)
        val entry = stat(p)
        if (entry?.isDirectory == true) {
            if (recursive) list(p).forEach { delete(it.path, recursive = true) }
            client().rmdir(p)
        } else {
            client().rm(p)
        }
    }

    override fun rename(from: String, to: String) =
        client().rename(RemotePaths.normalize(from), RemotePaths.normalize(to))

    override fun close() {
        silently { sftp?.close() }
        silently { sshClient?.takeIf { it.isConnected }?.disconnect() }
    }

    private fun client(): SFTPClient = sftp ?: error(tr(R.string.remote_not_connected))

    private fun copy(input: InputStream, output: OutputStream, onBytes: (Long) -> Unit) {
        val buf = ByteArray(1 shl 16)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
            total += n
            onBytes(total)
        }
    }

    private fun sha256(bytes: ByteArray): String =
        java.util.Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes))

    private inline fun silently(block: () -> Unit) = try { block() } catch (_: Exception) {}
}
