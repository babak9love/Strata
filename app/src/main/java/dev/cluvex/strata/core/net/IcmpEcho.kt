package dev.cluvex.strata.core.net

object IcmpEcho {
    const val ECHO_REQUEST = 8
    const val ECHO_REPLY = 0
    const val ECHOV6_REQUEST = 128
    const val ECHOV6_REPLY = 129

    fun checksum(data: ByteArray, len: Int = data.size): Int {
        var sum = 0L
        var i = 0
        while (i + 1 < len) {
            sum += (((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)).toLong()
            i += 2
        }
        if (i < len) sum += ((data[i].toInt() and 0xFF) shl 8).toLong()
        while ((sum shr 16) != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    fun request(id: Int, seq: Int, payload: ByteArray = ByteArray(32), v6: Boolean = false): ByteArray {
        val pkt = ByteArray(8 + payload.size)
        pkt[0] = (if (v6) ECHOV6_REQUEST else ECHO_REQUEST).toByte()
        pkt[1] = 0
        pkt[4] = (id ushr 8).toByte(); pkt[5] = id.toByte()
        pkt[6] = (seq ushr 8).toByte(); pkt[7] = seq.toByte()
        payload.copyInto(pkt, 8)
        if (!v6) {
            val c = checksum(pkt)
            pkt[2] = (c ushr 8).toByte(); pkt[3] = c.toByte()
        }
        return pkt
    }

    data class Reply(val type: Int, val code: Int, val id: Int, val seq: Int)

    fun parseReply(data: ByteArray, len: Int = data.size): Reply? {
        if (len < 8) return null
        val type = data[0].toInt() and 0xFF
        val code = data[1].toInt() and 0xFF
        val id = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
        val seq = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
        return Reply(type, code, id, seq)
    }

    fun isEchoReply(type: Int) = type == ECHO_REPLY || type == ECHOV6_REPLY
}
