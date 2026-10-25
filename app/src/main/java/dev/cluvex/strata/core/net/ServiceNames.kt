package dev.cluvex.strata.core.net

object ServiceNames {
    val COMMON_PORTS: List<Int> = listOf(
        20, 21, 22, 23, 25, 53, 67, 68, 69, 80, 110, 111, 123, 135, 137, 138, 139, 143, 161, 162,
        179, 389, 443, 445, 465, 514, 515, 587, 631, 636, 873, 993, 995, 1080, 1194, 1433, 1521,
        1723, 1883, 2049, 2082, 2083, 2086, 2087, 3128, 3306, 3389, 5000, 5060, 5222, 5353, 5432,
        5555, 5900, 5938, 6379, 6881, 8000, 8008, 8080, 8081, 8443, 8888, 9000, 9090, 9100, 9200,
        11211, 27017,
    )

    private val NAMES: Map<Int, String> = mapOf(
        20 to "ftp-data", 21 to "ftp", 22 to "ssh", 23 to "telnet", 25 to "smtp", 53 to "domain",
        67 to "dhcp", 68 to "dhcp", 69 to "tftp", 80 to "http", 110 to "pop3", 111 to "rpcbind",
        123 to "ntp", 135 to "msrpc", 137 to "netbios-ns", 138 to "netbios-dgm", 139 to "netbios-ssn",
        143 to "imap", 161 to "snmp", 162 to "snmptrap", 179 to "bgp", 389 to "ldap", 443 to "https",
        445 to "microsoft-ds", 465 to "smtps", 514 to "syslog", 515 to "printer", 587 to "submission",
        631 to "ipp", 636 to "ldaps", 873 to "rsync", 993 to "imaps", 995 to "pop3s", 1080 to "socks",
        1194 to "openvpn", 1433 to "ms-sql", 1521 to "oracle", 1723 to "pptp", 1883 to "mqtt",
        2049 to "nfs", 2082 to "cpanel", 2083 to "cpanel-ssl", 2086 to "whm", 2087 to "whm-ssl",
        3128 to "squid", 3306 to "mysql", 3389 to "rdp", 5000 to "upnp", 5060 to "sip", 5222 to "xmpp",
        5353 to "mdns", 5432 to "postgresql", 5555 to "adb", 5900 to "vnc", 5938 to "teamviewer",
        6379 to "redis", 6881 to "bittorrent", 8000 to "http-alt", 8008 to "http-alt",
        8080 to "http-proxy", 8081 to "http-alt", 8443 to "https-alt", 8888 to "http-alt",
        9000 to "http-alt", 9090 to "http-alt", 9100 to "jetdirect", 9200 to "elasticsearch",
        11211 to "memcached", 27017 to "mongodb",
    )

    fun of(port: Int): String? = NAMES[port]
}
