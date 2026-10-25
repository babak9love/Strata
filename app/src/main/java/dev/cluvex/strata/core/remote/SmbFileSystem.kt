package dev.cluvex.strata.core.remote

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mserref.NtStatus
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

class SmbFileSystem(private val c: RemoteConnection) : RemoteFileSystem {

    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private val shares = HashMap<String, DiskShare>()

    override fun connect() {
        val cfg = SmbConfig.builder().withTimeout(30, TimeUnit.SECONDS).withSoTimeout(60, TimeUnit.SECONDS).build()
        val cl = SMBClient(cfg)
        client = cl
        val conn = try {
            cl.connect(c.host, c.port)
        } catch (e: Exception) {
            throw RemoteConnectException(tr(R.string.remote_cant_reach_reason, "${c.host}:${c.port}", e.message ?: e.javaClass.simpleName), e)
        }
        connection = conn
        val auth = if (c.anonymous) AuthenticationContext.guest() else {
            val (domain, user) = splitUser(c.username)
            AuthenticationContext(user, c.password.toCharArray(), domain)
        }
        session = try {
            conn.authenticate(auth)
        } catch (e: SMBApiException) {
            throw if (e.status == NtStatus.STATUS_LOGON_FAILURE || e.status == NtStatus.STATUS_ACCESS_DENIED) {
                RemoteAuthException(if (c.anonymous) tr(R.string.remote_no_guests) else tr(R.string.remote_wrong_login))
            } else RemoteConnectException(tr(R.string.remote_sign_in_failed, e.status), e)
        }
    }

    override val isConnected: Boolean get() = connection?.isConnected == true

    private fun session() = session ?: throw IOException(tr(R.string.remote_not_connected))

    private fun split(path: String): Pair<String?, String> {
        val parts = RemotePaths.normalize(path).removePrefix("/").split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return null to ""
        return parts.first() to parts.drop(1).joinToString("\\")
    }

    private fun share(name: String): DiskShare = shares[name]?.takeIf { it.isConnected } ?: run {
        val s = try {
            session().connectShare(name) as? DiskShare ?: throw IOException(tr(R.string.remote_not_folder_share, name))
        } catch (e: SMBApiException) {
            throw if (e.status == NtStatus.STATUS_BAD_NETWORK_NAME) NoShareException(name) else translate(e)
        }
        shares[name] = s
        s
    }

    private fun translate(e: SMBApiException): IOException = when (e.status) {
        NtStatus.STATUS_ACCESS_DENIED -> IOException(tr(R.string.remote_access_denied))
        NtStatus.STATUS_OBJECT_NAME_NOT_FOUND, NtStatus.STATUS_OBJECT_PATH_NOT_FOUND -> IOException(tr(R.string.remote_not_found))
        NtStatus.STATUS_OBJECT_NAME_COLLISION -> IOException(tr(R.string.remote_already_exists))
        NtStatus.STATUS_DIRECTORY_NOT_EMPTY -> IOException(tr(R.string.remote_folder_not_empty))
        NtStatus.STATUS_SHARING_VIOLATION -> IOException(tr(R.string.remote_file_in_use))
        else -> IOException(tr(R.string.remote_smb_error, e.status))
    }

    private inline fun <T> smb(block: () -> T): T = try {
        block()
    } catch (e: SMBApiException) {
        throw translate(e)
    }

    override fun list(path: String): List<RemoteEntry> {
        val (shareName, inner) = split(path)
        if (shareName == null) return listShares()
        val base = RemotePaths.normalize(path)
        return smb {
            share(shareName).list(inner).filter { it.fileName != "." && it.fileName != ".." }.map { f ->
                RemoteEntry(
                    name = f.fileName,
                    path = RemotePaths.join(base, f.fileName),
                    isDirectory = f.fileAttributes and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L,
                    size = f.endOfFile,
                    modified = f.lastWriteTime.toEpochMillis(),
                )
            }
        }
    }

    private fun listShares(): List<RemoteEntry> = try {
        val transport = SMBTransportFactories.SRVSVC.getTransport(session())
        ServerService(transport).shares1
            .filter { it.type == 0 && !it.netName.endsWith("$") }
            .map { RemoteEntry(it.netName, "/" + it.netName, isDirectory = true, size = 0, modified = 0) }
            .sortedBy { it.name.lowercase() }
    } catch (e: Exception) {
        throw IOException(tr(R.string.remote_list_shares_failed, e.message ?: e.javaClass.simpleName), e)
    }

    override fun stat(path: String): RemoteEntry? {
        val (shareName, inner) = split(path)
        val norm = RemotePaths.normalize(path)
        if (shareName == null) return RemoteEntry("/", "/", true, 0, 0)
        return try {
            if (inner.isEmpty()) {
                share(shareName)
                RemoteEntry(shareName, norm, true, 0, 0)
            } else {
                val info = share(shareName).getFileInformation(inner)
                RemoteEntry(
                    RemotePaths.name(norm), norm, info.standardInformation.isDirectory,
                    info.standardInformation.endOfFile, info.basicInformation.lastWriteTime.toEpochMillis(),
                )
            }
        } catch (e: SMBApiException) {
            if (e.status == NtStatus.STATUS_OBJECT_NAME_NOT_FOUND || e.status == NtStatus.STATUS_OBJECT_PATH_NOT_FOUND) null else throw translate(e)
        } catch (_: NoShareException) {
            null
        }
    }

    private fun fileIn(path: String): Pair<DiskShare, String> {
        val (shareName, inner) = split(path)
        if (shareName == null || inner.isEmpty()) throw IOException(tr(R.string.remote_share_not_file))
        return share(shareName) to inner
    }

    override fun openInput(path: String): InputStream {
        val (share, inner) = fileIn(path)
        val file = smb {
            share.openFile(inner, EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)
        }
        return object : FilterInputStream(file.inputStream) {
            override fun close() {
                try { super.close() } finally { file.close() }
            }
        }
    }

    override fun download(path: String, into: OutputStream, onBytes: (Long) -> Unit) {
        openInput(path).use { input -> copy(input, into, onBytes) }
    }

    override fun upload(from: InputStream, path: String, onBytes: (Long) -> Unit) {
        val (share, inner) = fileIn(path)
        smb {
            share.openFile(
                inner,
                EnumSet.of(AccessMask.GENERIC_WRITE),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OVERWRITE_IF,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
            ).use { f -> f.outputStream.use { out -> copy(from, out, onBytes) } }
        }
    }

    override fun mkdir(path: String) {
        val (share, inner) = fileIn(path)
        smb { share.mkdir(inner) }
    }

    override fun delete(path: String, recursive: Boolean) {
        val (share, inner) = fileIn(path)
        smb {
            val dir = share.getFileInformation(inner).standardInformation.isDirectory
            if (dir) share.rmdir(inner, recursive) else share.rm(inner)
        }
    }

    override fun rename(from: String, to: String) {
        val (fromShare, fromInner) = split(from)
        val (toShare, toInner) = split(to)
        if (fromShare == null || fromInner.isEmpty() || toShare == null || toInner.isEmpty()) throw IOException(tr(R.string.remote_shares_no_rename))
        if (fromShare != toShare) throw IOException(tr(R.string.remote_smb_between_shares))
        val share = share(fromShare)
        smb {
            share.open(fromInner, EnumSet.of(AccessMask.DELETE, AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)
                .use { it.rename(toInner, false) }
        }
    }

    override fun close() {
        shares.values.forEach { runCatching { it.close() } }
        shares.clear()
        runCatching { session?.close() }
        runCatching { connection?.close() }
        runCatching { client?.close() }
        session = null
        connection = null
        client = null
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

    private class NoShareException(name: String) : IOException(tr(R.string.remote_no_share, name))

    companion object {
        internal fun splitUser(username: String): Pair<String?, String> {
            val i = username.indexOf('\\')
            return if (i > 0) username.substring(0, i) to username.substring(i + 1) else null to username
        }
    }
}
