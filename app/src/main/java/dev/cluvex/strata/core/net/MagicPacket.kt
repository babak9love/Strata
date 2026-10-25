package dev.cluvex.strata.core.net

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

object MagicPacket {
    fun parseMac(mac: String): ByteArray? {
        val parts = mac.trim().split(':', '-')
        if (parts.size != 6) return null
        return ByteArray(6) { i -> (parts[i].toIntOrNull(16)?.takeIf { it in 0..255 } ?: return null).toByte() }
    }

    fun build(mac: String): ByteArray {
        val m = parseMac(mac) ?: throw IllegalArgumentException("Not a MAC address: $mac")
        val pkt = ByteArray(6 + 16 * 6)
        for (i in 0 until 6) pkt[i] = 0xFF.toByte()
        for (rep in 0 until 16) m.copyInto(pkt, 6 + rep * 6)
        return pkt
    }

    fun send(mac: String, broadcast: String = "255.255.255.255", port: Int = 9) {
        val data = build(mac)
        DatagramSocket().use { s ->
            s.broadcast = true
            s.send(DatagramPacket(data, data.size, InetAddress.getByName(broadcast), port))
        }
    }
}
