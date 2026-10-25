package dev.cluvex.strata.core.props

object AndroidIds {

    private val wellKnown = mapOf(
        0 to "root", 1000 to "system", 1001 to "radio", 1002 to "bluetooth", 1003 to "graphics",
        1004 to "input", 1005 to "audio", 1006 to "camera", 1007 to "log", 1008 to "compass",
        1009 to "mount", 1010 to "wifi", 1011 to "adb", 1012 to "install", 1013 to "media",
        1014 to "dhcp", 1015 to "sdcard_rw", 1016 to "vpn", 1017 to "keystore", 1018 to "usb",
        1019 to "drm", 1020 to "mdnsr", 1021 to "gps", 1023 to "media_rw", 1024 to "mtp",
        1026 to "drmrpc", 1027 to "nfc", 1028 to "sdcard_r", 1029 to "clat", 1030 to "loop_radio",
        1031 to "mediadrm", 1032 to "package_info", 1033 to "sdcard_pics", 1034 to "sdcard_av",
        1035 to "sdcard_all", 1036 to "logd", 1037 to "shared_relro", 1041 to "audioserver",
        1047 to "cameraserver", 1078 to "ext_data_rw", 1079 to "ext_obb_rw",
        2000 to "shell", 2001 to "cache", 2002 to "diag",
        3001 to "net_bt_admin", 3002 to "net_bt", 3003 to "inet", 3004 to "net_raw",
        3005 to "net_admin", 3006 to "net_bw_stats", 3007 to "net_bw_acct", 3009 to "readproc",
        3010 to "wakelock", 9997 to "everybody", 9998 to "misc", 9999 to "nobody",
    )

    private const val PER_USER_RANGE = 100_000

    fun name(id: Int): String {
        if (id < 0) return id.toString()
        val user = id / PER_USER_RANGE
        val app = id % PER_USER_RANGE
        return when (app) {
            in 10_000..19_999 -> "u${user}_a${app - 10_000}"
            in 20_000..29_999 -> "u${user}_a${app - 20_000}_cache"
            in 30_000..39_999 -> "u${user}_a${app - 30_000}_ext"
            in 40_000..49_999 -> "u${user}_a${app - 40_000}_ext_cache"
            in 50_000..59_999 -> "all_a${app - 50_000}"
            in 99_000..99_999 -> "u${user}_i${app - 99_000}"
            else -> {
                val base = wellKnown[app] ?: return id.toString()
                if (user == 0) base else "u${user}_$base"
            }
        }
    }

    private val byName: Map<String, Int> = wellKnown.entries.associate { (id, name) -> name to id }
    private val appName = Regex("""u(\d+)_a(\d+)(_cache|_ext|_ext_cache)?""")
    private val sharedName = Regex("""all_a(\d+)""")
    private val isolatedName = Regex("""u(\d+)_i(\d+)""")
    private val userName = Regex("""u(\d+)_([a-z_]+)""")

    fun idOf(text: String): Int? {
        val n = text.trim()
        if (n.isEmpty()) return null
        n.toIntOrNull()?.let { return it.takeIf { id -> id >= 0 } }
        byName[n]?.let { return it }
        appName.matchEntire(n)?.let { m ->
            val app = m.groupValues[2].toIntOrNull()?.takeIf { it <= 9_999 } ?: return null
            val base = when (m.groupValues[3]) {
                "" -> 10_000
                "_cache" -> 20_000
                "_ext" -> 30_000
                else -> 40_000
            }
            return compose(m.groupValues[1], base + app)
        }
        sharedName.matchEntire(n)?.let { m ->
            val app = m.groupValues[1].toIntOrNull()?.takeIf { it <= 9_999 } ?: return null
            return 50_000 + app
        }
        isolatedName.matchEntire(n)?.let { m ->
            val i = m.groupValues[2].toIntOrNull()?.takeIf { it <= 999 } ?: return null
            return compose(m.groupValues[1], 99_000 + i)
        }
        userName.matchEntire(n)?.let { m ->
            val base = byName[m.groupValues[2]] ?: return null
            return compose(m.groupValues[1], base)
        }
        return null
    }

    private fun compose(user: String, appId: Int): Int? {
        val u = user.toLongOrNull() ?: return null
        val id = u * PER_USER_RANGE + appId
        return if (id <= Int.MAX_VALUE) id.toInt() else null
    }
}
