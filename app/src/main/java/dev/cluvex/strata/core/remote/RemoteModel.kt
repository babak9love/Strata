package dev.cluvex.strata.core.remote

import androidx.annotation.StringRes
import dev.cluvex.strata.R

import kotlinx.serialization.Serializable

@Serializable
enum class RemoteProtocol(val defaultPort: Int, @StringRes val label: Int) {
    FTP(21, R.string.model_protocol_ftp),
    FTPS(21, R.string.model_protocol_ftps),
    SFTP(22, R.string.model_protocol_sftp),
    SMB(445, R.string.model_protocol_smb),
    WEBDAV(80, R.string.model_protocol_webdav),
    WEBDAVS(443, R.string.model_protocol_webdavs),
    SAF(0, R.string.model_protocol_saf),
    VAULT(0, R.string.model_protocol_vault),
}

@Serializable
data class RemoteConnection(
    val id: String,
    val name: String,
    val protocol: RemoteProtocol,
    val host: String,
    val port: Int = protocol.defaultPort,
    val username: String = "",
    val password: String = "",
    val anonymous: Boolean = false,
    val savePassword: Boolean = true,
    val initialPath: String = "/",
    val hostKey: String = "",
) {
    val displayAddress: String
        get() = if (protocol == RemoteProtocol.SAF) safAddress() else buildString {
            append(protocol.name.lowercase()).append("://")
            if (!anonymous && username.isNotBlank()) append(username).append('@')
            append(host)
            if (port != protocol.defaultPort) append(':').append(port)
        }

    private fun safAddress(): String = runCatching {
        val uri = java.net.URI(host)
        uri.authority + " · " + uri.path.substringAfter("/tree/")
    }.getOrDefault(host)
}

data class RemoteEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val modified: Long,
    val isSymlink: Boolean = false,
)

object RemotePaths {
    fun normalize(path: String): String {
        val parts = path.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        val stack = ArrayDeque<String>()
        for (p in parts) {
            if (p == "..") { if (stack.isNotEmpty()) stack.removeLast() } else stack.addLast(p)
        }
        return "/" + stack.joinToString("/")
    }

    fun name(path: String): String = normalize(path).substringAfterLast('/')

    fun parent(path: String): String {
        val n = normalize(path)
        if (n == "/") return "/"
        return n.substringBeforeLast('/').ifEmpty { "/" }
    }

    fun join(dir: String, child: String): String {
        val d = normalize(dir)
        return normalize(if (d == "/") "/$child" else "$d/$child")
    }

    fun breadcrumbs(path: String): List<String> {
        val n = normalize(path)
        if (n == "/") return listOf("/")
        val out = ArrayList<String>()
        val sb = StringBuilder()
        out += "/"
        for (seg in n.removePrefix("/").split('/')) {
            sb.append('/').append(seg)
            out += sb.toString()
        }
        return out
    }
}
