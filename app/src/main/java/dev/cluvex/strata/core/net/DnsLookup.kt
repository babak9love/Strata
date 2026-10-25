package dev.cluvex.strata.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xbill.DNS.DClass
import org.xbill.DNS.Message
import org.xbill.DNS.Name
import org.xbill.DNS.Record
import org.xbill.DNS.ReverseMap
import org.xbill.DNS.Section
import org.xbill.DNS.SimpleResolver
import org.xbill.DNS.Type
import java.net.InetAddress

data class DnsRecord(val name: String, val type: String, val ttl: Long, val data: String)

object DnsLookup {
    val TYPES = listOf("A", "AAAA", "CNAME", "MX", "NS", "TXT", "SOA", "SRV", "CAA", "PTR")

    fun describe(r: Record): DnsRecord =
        DnsRecord(r.name.toString(), Type.string(r.type), r.ttl, r.rdataToString())

    suspend fun lookup(host: String, type: String, server: String, timeoutMs: Int = 5000): List<DnsRecord> =
        withContext(Dispatchers.IO) {
            val t = Type.value(type).takeIf { it >= 0 } ?: throw IllegalArgumentException("Unknown record type: $type")
            val qname = if (t == Type.PTR && isIp(host)) ReverseMap.fromAddress(InetAddress.getByName(host))
            else Name.fromString(if (host.endsWith(".")) host else "$host.")
            val resolver = SimpleResolver(server).apply { setTimeout(java.time.Duration.ofMillis(timeoutMs.toLong())) }
            val response = resolver.send(Message.newQuery(Record.newRecord(qname, t, DClass.IN)))
            response.getSection(Section.ANSWER).map(::describe)
        }

    private fun isIp(s: String): Boolean =
        Subnet.ipToInt(s) != null || s.contains(':')
}
