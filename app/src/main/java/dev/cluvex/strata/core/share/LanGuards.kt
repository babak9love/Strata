package dev.cluvex.strata.core.share

import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

class SafeRoot(root: File) {
    val root: File = root.canonicalFile

    fun resolve(clientPath: String?): File? {
        val p = clientPath ?: "/"
        if ('\u0000' in p) return null
        val parts = p.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) return null
        val f = parts.fold(root) { dir, name -> File(dir, name) }
        val canonical = f.canonicalFile
        return f.takeIf { canonical == root || canonical.path.startsWith(root.path + File.separator) }
    }
}

fun safeChildName(raw: String?): String? =
    raw?.takeIf { it.isNotEmpty() && it != "." && it != ".." && it.length <= 255 && it.none { c -> c == '/' || c == '\\' || c == '\u0000' } }

fun uploadName(raw: String?): String? = safeChildName(raw?.substringAfterLast('/')?.substringAfterLast('\\'))

fun isIpLiteralHost(hostHeader: String?): Boolean {
    val h = hostHeader?.trim() ?: return false
    if (h.startsWith("[")) {
        val end = h.indexOf(']')
        return end > 1 && h.substring(1, end).all { it.isLetterOrDigit() || it == ':' || it == '.' || it == '%' }
    }
    val host = h.substringBefore(':')
    if (host == "localhost") return true
    val parts = host.split('.')
    return parts.size == 4 && parts.all { s -> s.isNotEmpty() && s.length <= 3 && s.all(Char::isDigit) && s.toInt() <= 255 }
}

fun isLanPeer(a: InetAddress): Boolean = when {
    a.isLoopbackAddress || a.isLinkLocalAddress || a.isSiteLocalAddress -> true
    a is Inet4Address -> (a.address[0].toInt() and 0xFF) == 100 && (a.address[1].toInt() and 0xC0) == 64
    a is Inet6Address -> (a.address[0].toInt() and 0xFE) == 0xFC
    else -> false
}

class LoginThrottle(private val now: () -> Long = System::currentTimeMillis) {
    private class State {
        var failures = 0
        var lockedUntil = 0L
    }

    private val byIp = ConcurrentHashMap<String, State>()

    fun retryAfterMs(ip: String): Long = byIp[ip]?.let { maxOf(0L, it.lockedUntil - now()) } ?: 0L

    fun failed(ip: String) {
        val s = byIp.computeIfAbsent(ip) { State() }
        synchronized(s) {
            s.failures++
            if (s.failures >= 5) s.lockedUntil = now() + minOf(300_000L, 1000L shl minOf(s.failures - 5, 9))
        }
    }

    fun succeeded(ip: String) {
        byIp.remove(ip)
    }
}
