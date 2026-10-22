package dev.cluvex.strata.core.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

/** A machine on the local network that accepts SMB connections. [name] is the NetBIOS name when it answered. */
data class LanHost(val ip: String, val name: String?)

/**
 * Finds SMB servers on the networks this phone is attached to: Wi-Fi it joined *and* the Wi-Fi hotspot it hosts.
 * For every local IPv4 /24 it tries TCP port 445 on all addresses, then asks each hit for its NetBIOS name (UDP 137).
 * Mobile-data interfaces are skipped.
 */
object LanScanner {
    private const val SMB_PORT = 445
    private const val CONNECT_TIMEOUT_MS = 600
    private const val PARALLEL = 64

    fun scan(): Flow<LanHost> = channelFlow {
        val subnets = localSubnets()
        val own = subnets.map { it.second }.toSet()
        val limiter = Semaphore(PARALLEL)
        coroutineScope {
            subnets.map { it.first }.distinct().flatMap { base ->
                (1..254).map { i -> "$base.$i" }.filter { it !in own }
            }.map { ip ->
                async {
                    limiter.withPermit {
                        if (isSmbOpen(ip)) send(LanHost(ip, netbiosName(ip)))
                    }
                }
            }.awaitAll()
        }
    }.flowOn(Dispatchers.IO)

    /** (first three octets, full local address) for each usable local IPv4 interface address. */
    private fun localSubnets(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val ifaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull().orEmpty()
        for (nif in ifaces) {
            val name = nif.name.lowercase()
            if (!runCatching { nif.isUp && !nif.isLoopback }.getOrDefault(false)) continue
            if (listOf("rmnet", "ccmni", "ppp", "tun", "dummy", "v4-", "clat").any { name.startsWith(it) }) continue
            for (addr in nif.inetAddresses) {
                if (addr is Inet4Address && addr.isSiteLocalAddress) {
                    val ip = addr.hostAddress ?: continue
                    out += ip.substringBeforeLast('.') to ip
                }
            }
        }
        return out
    }

    private fun isSmbOpen(ip: String): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, SMB_PORT), CONNECT_TIMEOUT_MS) }
        true
    } catch (_: Exception) {
        false
    }

    /** NetBIOS node-status query; returns the machine's unique name or null if it does not answer. */
    private fun netbiosName(ip: String): String? = try {
        val req = ByteArray(50)
        req[0] = 0x4E; req[1] = 0x53          // transaction id
        req[5] = 1                            // one question
        req[12] = 0x20                        // name length: 32 encoded chars
        req[13] = 'C'.code.toByte(); req[14] = 'K'.code.toByte() // "*" first-level encoded
        for (i in 15 until 45) req[i] = 'A'.code.toByte()         // 15 NUL bytes
        req[46] = 0                           // name terminator
        req[47] = 0; req[48] = 0x21           // type NBSTAT
        req[49] = 1                           // class IN (high byte already 0)
        DatagramSocket().use { s ->
            s.soTimeout = 700
            s.send(DatagramPacket(req, req.size, InetAddress.getByName(ip), 137))
            val buf = ByteArray(1024)
            val pkt = DatagramPacket(buf, buf.size)
            s.receive(pkt)
            parseNodeStatus(buf, pkt.length)
        }
    } catch (_: Exception) {
        null
    }

    private fun parseNodeStatus(b: ByteArray, len: Int): String? {
        var p = 12
        while (p < len && b[p].toInt() != 0) {
            if ((b[p].toInt() and 0xC0) == 0xC0) { p += 1; break }
            p += (b[p].toInt() and 0xFF) + 1
        }
        p += 1 + 10
        if (p >= len) return null
        val count = b[p].toInt() and 0xFF
        p += 1
        repeat(count) {
            if (p + 18 > len) return null
            val suffix = b[p + 15].toInt() and 0xFF
            val group = (b[p + 16].toInt() and 0x80) != 0
            if (suffix == 0 && !group) return String(b, p, 15, Charsets.US_ASCII).trim().takeIf { it.isNotEmpty() }
            p += 18
        }
        return null
    }
}
