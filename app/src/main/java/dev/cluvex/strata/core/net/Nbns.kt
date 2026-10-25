package dev.cluvex.strata.core.net

object Nbns {
    const val PORT = 137

    fun nodeStatusRequest(txId: Int = 0x4a50): ByteArray {
        val out = ArrayList<Byte>(50)
        fun u16(v: Int) { out.add((v ushr 8).toByte()); out.add(v.toByte()) }
        u16(txId)
        u16(0x0000)
        u16(1); u16(0); u16(0); u16(0)
        val name = ByteArray(16); name[0] = '*'.code.toByte()
        out.add(0x20)
        for (b in name) {
            val v = b.toInt() and 0xFF
            out.add(('A'.code + (v ushr 4)).toByte())
            out.add(('A'.code + (v and 0x0F)).toByte())
        }
        out.add(0x00)
        u16(0x0021)
        u16(0x0001)
        return out.toByteArray()
    }

    data class NbName(val name: String, val suffix: Int, val group: Boolean)

    fun parseNodeStatus(data: ByteArray, len: Int = data.size): List<NbName>? {
        if (len < 12) return null
        var p = 12
        while (p < len && data[p].toInt() != 0) p += (data[p].toInt() and 0xFF) + 1
        p += 1 + 4
        if (p >= len) return null
        p += if ((data[p].toInt() and 0xC0) == 0xC0) {
            2
        } else {
            var q = p
            while (q < len && data[q].toInt() != 0) q += (data[q].toInt() and 0xFF) + 1
            q - p + 1
        }
        p += 2 + 2 + 4 + 2
        if (p >= len) return null
        val num = data[p].toInt() and 0xFF; p += 1
        val names = ArrayList<NbName>(num)
        for (i in 0 until num) {
            if (p + 18 > len) break
            val raw = String(data, p, 15, Charsets.US_ASCII).trimEnd(' ', '\u0000')
            val suffix = data[p + 15].toInt() and 0xFF
            val flags = ((data[p + 16].toInt() and 0xFF) shl 8) or (data[p + 17].toInt() and 0xFF)
            names.add(NbName(raw, suffix, flags and 0x8000 != 0))
            p += 18
        }
        return names
    }
}
