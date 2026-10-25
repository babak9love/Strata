package dev.cluvex.strata.core.pdf

import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class PdfSecurity private constructor(
    private val key: ByteArray,
    private val stringMethod: Method,
    private val streamMethod: Method,
    val encryptMetadata: Boolean,
) {
    enum class Method { NONE, RC4, AES128, AES256 }

    fun decryptString(bytes: ByteArray, ref: PdfReference): ByteArray = decrypt(bytes, ref, stringMethod)

    fun decryptStream(bytes: ByteArray, ref: PdfReference): ByteArray = decrypt(bytes, ref, streamMethod)

    val encryptsStreams: Boolean get() = streamMethod != Method.NONE

    private fun decrypt(bytes: ByteArray, ref: PdfReference, method: Method): ByteArray = when (method) {
        Method.NONE -> bytes
        Method.RC4 -> rc4(objectKey(ref, aes = false), bytes)
        Method.AES128 -> aes(objectKey(ref, aes = true), bytes)
        Method.AES256 -> aes(key, bytes)
    }

    private fun objectKey(ref: PdfReference, aes: Boolean): ByteArray {
        val md5 = MessageDigest.getInstance("MD5")
        md5.update(key)
        md5.update(byteArrayOf(ref.number.toByte(), (ref.number shr 8).toByte(), (ref.number shr 16).toByte()))
        md5.update(byteArrayOf(ref.generation.toByte(), (ref.generation shr 8).toByte()))
        if (aes) md5.update(byteArrayOf(0x73, 0x41, 0x6C, 0x54))
        return md5.digest().copyOf(minOf(key.size + 5, 16))
    }

    companion object {
        private val PADDING = intArrayOf(
            0x28, 0xBF, 0x4E, 0x5E, 0x4E, 0x75, 0x8A, 0x41, 0x64, 0x00, 0x4E, 0x56, 0xFF, 0xFA, 0x01, 0x08,
            0x2E, 0x2E, 0x00, 0xB6, 0xD0, 0x68, 0x3E, 0x80, 0x2F, 0x0C, 0xA9, 0xFE, 0x64, 0x53, 0x69, 0x7A,
        ).map { it.toByte() }.toByteArray()

        fun open(dict: PdfDictionary, firstId: ByteArray, password: String): PdfSecurity {
            val filter = dict.name("Filter")
            if (filter != "Standard") throw PdfException(if (filter != null) tr(R.string.pdf_error_unsupported_encryption, filter) else tr(R.string.pdf_error_unknown_encryption))
            val v = (dict["V"] as? PdfNumber)?.int ?: 0
            val r = (dict["R"] as? PdfNumber)?.int ?: 2
            val o = (dict["O"] as? PdfString)?.bytes ?: throw PdfException(tr(R.string.pdf_error_encryption_dictionary))
            val u = (dict["U"] as? PdfString)?.bytes ?: throw PdfException(tr(R.string.pdf_error_encryption_dictionary))
            val p = (dict["P"] as? PdfNumber)?.long?.toInt() ?: 0
            val encryptMetadata = (dict["EncryptMetadata"] as? PdfBoolean)?.value ?: true

            if (v >= 5 || r >= 5) {
                val ue = (dict["UE"] as? PdfString)?.bytes
                val oe = (dict["OE"] as? PdfString)?.bytes
                val pw = password.toByteArray(Charsets.UTF_8).let { if (it.size > 127) it.copyOf(127) else it }
                val key = aes256Key(pw, o, u, ue, oe, r) ?: throw PdfPasswordException(tr(R.string.pdf_error_password))
                val (strM, stmM) = cryptFilters(dict, v)
                return PdfSecurity(key, strM, stmM, encryptMetadata)
            }

            val declared = (dict["Length"] as? PdfNumber)?.int ?: if (v == 4) 128 else 40
            val lengthBits = if (declared in 5..16) declared * 8 else declared
            val n = if (r == 2) 5 else (lengthBits / 8).coerceIn(5, 16)
            val pw = password.toByteArray(Charsets.ISO_8859_1)
            val key = legacyKey(pw, o, p, firstId, r, n, encryptMetadata).takeIf { checkUser(it, u, firstId, r) }
                ?: ownerToUser(pw, o, r, n)?.let { legacyKey(it, o, p, firstId, r, n, encryptMetadata) }?.takeIf { checkUser(it, u, firstId, r) }
                ?: throw PdfPasswordException(tr(R.string.pdf_error_password))
            val (strM, stmM) = if (v == 4) cryptFilters(dict, v) else Method.RC4 to Method.RC4
            return PdfSecurity(key, strM, stmM, encryptMetadata)
        }

        private fun cryptFilters(dict: PdfDictionary, v: Int): Pair<Method, Method> {
            val filters = dict["CF"] as? PdfDictionary
            fun method(name: String?): Method {
                if (name == null || name == "Identity") return Method.NONE
                val cf = filters?.get(name) as? PdfDictionary ?: return if (v >= 5) Method.AES256 else Method.RC4
                return when (cf.name("CFM")) {
                    "AESV2" -> Method.AES128
                    "AESV3" -> Method.AES256
                    "V2" -> Method.RC4
                    "None" -> Method.NONE
                    else -> if (v >= 5) Method.AES256 else Method.RC4
                }
            }
            return method(dict.name("StrF")) to method(dict.name("StmF"))
        }

        private fun pad(password: ByteArray): ByteArray {
            val out = ByteArray(32)
            val n = minOf(password.size, 32)
            System.arraycopy(password, 0, out, 0, n)
            System.arraycopy(PADDING, 0, out, n, 32 - n)
            return out
        }

        private fun legacyKey(password: ByteArray, o: ByteArray, p: Int, id: ByteArray, r: Int, n: Int, encryptMetadata: Boolean): ByteArray {
            val md5 = MessageDigest.getInstance("MD5")
            md5.update(pad(password))
            md5.update(o.copyOf(32))
            md5.update(byteArrayOf(p.toByte(), (p shr 8).toByte(), (p shr 16).toByte(), (p shr 24).toByte()))
            md5.update(id)
            if (r >= 4 && !encryptMetadata) md5.update(byteArrayOf(-1, -1, -1, -1))
            var key = md5.digest()
            if (r >= 3) repeat(50) { key = MessageDigest.getInstance("MD5").digest(key.copyOf(n)) }
            return key.copyOf(n)
        }

        private fun checkUser(key: ByteArray, u: ByteArray, id: ByteArray, r: Int): Boolean {
            if (r == 2) return rc4(key, PADDING).contentEquals(u.copyOf(32))
            val md5 = MessageDigest.getInstance("MD5")
            md5.update(PADDING)
            md5.update(id)
            var x = rc4(key, md5.digest())
            for (i in 1..19) x = rc4(ByteArray(key.size) { (key[it].toInt() xor i).toByte() }, x)
            return u.size >= 16 && x.copyOf(16).contentEquals(u.copyOf(16))
        }

        private fun ownerToUser(owner: ByteArray, o: ByteArray, r: Int, n: Int): ByteArray? {
            var h = MessageDigest.getInstance("MD5").digest(pad(owner))
            if (r >= 3) repeat(50) { h = MessageDigest.getInstance("MD5").digest(h) }
            val rc4Key = h.copyOf(n)
            var x = o.copyOf(32)
            if (r == 2) {
                x = rc4(rc4Key, x)
            } else {
                for (i in 19 downTo 0) x = rc4(ByteArray(rc4Key.size) { (rc4Key[it].toInt() xor i).toByte() }, x)
            }
            return x
        }

        private fun aes256Key(pw: ByteArray, o: ByteArray, u: ByteArray, ue: ByteArray?, oe: ByteArray?, r: Int): ByteArray? {
            if (u.size >= 48 && ue != null) {
                if (hash(pw, u.copyOfRange(32, 40), ByteArray(0), r).contentEquals(u.copyOf(32))) {
                    val k = hash(pw, u.copyOfRange(40, 48), ByteArray(0), r)
                    return aesNoPadding(k, ue.copyOf(32))
                }
            }
            if (o.size >= 48 && u.size >= 48 && oe != null) {
                val udata = u.copyOf(48)
                if (hash(pw, o.copyOfRange(32, 40), udata, r).contentEquals(o.copyOf(32))) {
                    val k = hash(pw, o.copyOfRange(40, 48), udata, r)
                    return aesNoPadding(k, oe.copyOf(32))
                }
            }
            return null
        }

        private fun aesNoPadding(key: ByteArray, data: ByteArray): ByteArray {
            val c = Cipher.getInstance("AES/CBC/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(16)))
            return c.doFinal(data)
        }

        fun hash(password: ByteArray, salt: ByteArray, udata: ByteArray, r: Int): ByteArray {
            val sha256 = MessageDigest.getInstance("SHA-256")
            sha256.update(password)
            sha256.update(salt)
            sha256.update(udata)
            var k = sha256.digest()
            if (r < 6) return k
            var round = 0
            while (true) {
                val block = password + k + udata
                val k1 = ByteArray(block.size * 64)
                for (i in 0 until 64) System.arraycopy(block, 0, k1, i * block.size, block.size)
                val c = Cipher.getInstance("AES/CBC/NoPadding")
                c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(k.copyOf(16), "AES"), IvParameterSpec(k.copyOfRange(16, 32)))
                val e = c.doFinal(k1)
                var sum = 0
                for (i in 0 until 16) sum += e[i].toInt() and 0xFF
                k = when (sum % 3) {
                    0 -> MessageDigest.getInstance("SHA-256").digest(e)
                    1 -> MessageDigest.getInstance("SHA-384").digest(e)
                    else -> MessageDigest.getInstance("SHA-512").digest(e)
                }
                round++
                if (round >= 64 && (e[e.size - 1].toInt() and 0xFF) <= round - 32) break
            }
            return k.copyOf(32)
        }

        fun rc4(key: ByteArray, data: ByteArray): ByteArray {
            val s = IntArray(256) { it }
            var j = 0
            for (i in 0 until 256) {
                j = (j + s[i] + (key[i % key.size].toInt() and 0xFF)) and 0xFF
                val t = s[i]
                s[i] = s[j]
                s[j] = t
            }
            val out = ByteArray(data.size)
            var i = 0
            j = 0
            for (n in data.indices) {
                i = (i + 1) and 0xFF
                j = (j + s[i]) and 0xFF
                val t = s[i]
                s[i] = s[j]
                s[j] = t
                out[n] = (data[n].toInt() xor s[(s[i] + s[j]) and 0xFF]).toByte()
            }
            return out
        }

        fun aes(key: ByteArray, data: ByteArray): ByteArray {
            val usable = data.size - (data.size - 16).coerceAtLeast(0) % 16
            if (usable <= 16) return ByteArray(0)
            val c = Cipher.getInstance("AES/CBC/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(data, 0, 16))
            val plain = c.doFinal(data, 16, usable - 16)
            val pad = plain[plain.size - 1].toInt() and 0xFF
            val valid = pad in 1..16 && pad <= plain.size && (plain.size - pad until plain.size).all { (plain[it].toInt() and 0xFF) == pad }
            return if (valid) plain.copyOf(plain.size - pad) else plain
        }
    }
}
