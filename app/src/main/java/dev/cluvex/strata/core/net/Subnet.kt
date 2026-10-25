package dev.cluvex.strata.core.net

class Subnet private constructor(val networkInt: Int, val prefix: Int) {
    val mask: Int get() = if (prefix == 0) 0 else (-1 shl (32 - prefix))
    val network: String get() = intToIp(networkInt)
    val broadcast: String get() = intToIp(networkInt or mask.inv())
    val size: Long get() = 1L shl (32 - prefix)

    fun hosts(cap: Int = 65536): List<String> {
        val count = size
        val (firstOff, lastOff) = when (prefix) {
            32 -> 0L to 0L
            31 -> 0L to 1L
            else -> 1L to (count - 2)
        }
        val n = lastOff - firstOff + 1
        require(n in 1..cap) { "Subnet /$prefix is too large to scan ($n hosts)" }
        val base = networkInt.toLong() and 0xFFFFFFFFL
        return (firstOff..lastOff).map { intToIp(((base + it) and 0xFFFFFFFFL).toInt()) }
    }

    override fun toString() = "$network/$prefix"

    companion object {
        fun ipToInt(ip: String): Int? {
            val p = ip.trim().split('.')
            if (p.size != 4) return null
            var v = 0
            for (part in p) {
                val b = part.toIntOrNull() ?: return null
                if (b !in 0..255) return null
                v = (v shl 8) or b
            }
            return v
        }

        fun intToIp(v: Int): String =
            "${(v ushr 24) and 0xFF}.${(v ushr 16) and 0xFF}.${(v ushr 8) and 0xFF}.${v and 0xFF}"

        fun of(address: String, prefix: Int): Subnet? {
            val a = ipToInt(address) ?: return null
            if (prefix !in 0..32) return null
            val mask = if (prefix == 0) 0 else (-1 shl (32 - prefix))
            return Subnet(a and mask, prefix)
        }

        fun parse(cidr: String): Subnet? {
            val s = cidr.trim()
            val slash = s.indexOf('/')
            if (slash < 0) return of(s, 32)
            val prefix = s.substring(slash + 1).toIntOrNull() ?: return null
            return of(s.substring(0, slash), prefix)
        }
    }
}
