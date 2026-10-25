package dev.cluvex.strata.core.net

import org.apache.commons.net.whois.WhoisClient

object Whois {
    private val REFERRAL = Regex(
        """(?im)^\s*(?:refer|whois server|registrar whois server|referralserver)\s*:\s*(?:whois://)?([a-z0-9.\-]+)""",
    )

    fun referral(response: String): String? =
        REFERRAL.find(response)?.groupValues?.get(1)?.trim()?.lowercase()?.takeIf { it.contains('.') }

    data class Hop(val server: String, val text: String)

    fun lookup(query: String, start: String = "whois.iana.org", maxReferrals: Int = 3, timeoutMs: Int = 8000): List<Hop> {
        val hops = ArrayList<Hop>()
        var server: String? = start
        val seen = HashSet<String>()
        var left = maxReferrals
        while (server != null && seen.add(server)) {
            val text = ask(server, query, timeoutMs)
            hops.add(Hop(server, text))
            val next = referral(text)
            if (next == null || left-- <= 0) break
            server = next
        }
        return hops
    }

    private fun ask(server: String, query: String, timeoutMs: Int): String {
        val c = WhoisClient()
        c.connectTimeout = timeoutMs
        return try {
            c.connect(server)
            c.soTimeout = timeoutMs
            c.query(query)
        } finally {
            runCatching { c.disconnect() }
        }
    }
}
