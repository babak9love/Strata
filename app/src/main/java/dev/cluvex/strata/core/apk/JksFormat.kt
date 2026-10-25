package dev.cluvex.strata.core.apk

import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.pkcs.EncryptedPrivateKeyInfo
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec

object JksFormat {

    const val MAGIC = 0xFEEDFEED.toInt()
    private const val VERSION = 2
    private const val PRIVATE_KEY = 1
    private const val TRUSTED_CERT = 2
    private const val SALT = 20
    private const val DIGEST = 20
    private val KEY_PROTECTOR = ASN1ObjectIdentifier("1.3.6.1.4.1.42.2.17.1.1")
    private val WHITENER = "Mighty Aphrodite".toByteArray(Charsets.UTF_8)

    class Entry(
        val alias: String,
        val created: Long,
        val protectedKey: ByteArray?,
        val certificates: List<X509Certificate>,
    ) {
        val isKey: Boolean get() = protectedKey != null
    }

    class WrongPasswordException : IOException(tr(R.string.keys_error_wrong_store_password))

    fun isJks(head: ByteArray): Boolean =
        head.size >= 4 && ((head[0].toInt() and 0xFF) shl 24 or ((head[1].toInt() and 0xFF) shl 16) or ((head[2].toInt() and 0xFF) shl 8) or (head[3].toInt() and 0xFF)) == MAGIC

    fun read(bytes: ByteArray, storePassword: CharArray?): List<Entry> {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        if (input.readInt() != MAGIC) throw IOException(tr(R.string.keys_error_not_jks))
        val version = input.readInt()
        if (version != 1 && version != 2) throw IOException(tr(R.string.keys_error_jks_version, version))
        val certs = CertificateFactory.getInstance("X.509")
        fun cert(): X509Certificate {
            if (version == 2) input.readUTF()
            val der = ByteArray(input.readInt()).also { input.readFully(it) }
            return certs.generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        }
        val entries = ArrayList<Entry>()
        repeat(input.readInt()) {
            when (val tag = input.readInt()) {
                PRIVATE_KEY -> {
                    val alias = input.readUTF()
                    val created = input.readLong()
                    val key = ByteArray(input.readInt()).also { input.readFully(it) }
                    val chain = List(input.readInt()) { cert() }
                    entries += Entry(alias, created, key, chain)
                }
                TRUSTED_CERT -> {
                    val alias = input.readUTF()
                    val created = input.readLong()
                    entries += Entry(alias, created, null, listOf(cert()))
                }
                else -> throw IOException(tr(R.string.keys_error_jks_entry_type, tag))
            }
        }
        if (storePassword != null) {
            val end = bytes.size - input.available()
            val expected = ByteArray(DIGEST).also { input.readFully(it) }
            val actual = MessageDigest.getInstance("SHA-1").run {
                update(utf16(storePassword)); update(WHITENER); update(bytes, 0, end); digest()
            }
            if (!MessageDigest.isEqual(expected, actual)) throw WrongPasswordException()
        }
        return entries
    }

    fun recoverKey(entry: Entry, keyPassword: CharArray): PrivateKey {
        val info = EncryptedPrivateKeyInfo.getInstance(entry.protectedKey ?: throw IOException(tr(R.string.keys_error_alias_no_key, entry.alias)))
        if (info.encryptionAlgorithm.algorithm != KEY_PROTECTOR) {
            throw IOException(tr(R.string.keys_error_key_protection, entry.alias, info.encryptionAlgorithm.algorithm.id))
        }
        val protected = info.encryptedData
        if (protected.size <= SALT + DIGEST) throw IOException(tr(R.string.keys_error_key_damaged, entry.alias))
        val salt = protected.copyOfRange(0, SALT)
        val encrypted = protected.copyOfRange(SALT, protected.size - DIGEST)
        val check = protected.copyOfRange(protected.size - DIGEST, protected.size)
        val pw = utf16(keyPassword)
        val plain = xor(encrypted, keyStream(pw, salt, encrypted.size))
        val digest = MessageDigest.getInstance("SHA-1").run { update(pw); update(plain); digest() }
        if (!MessageDigest.isEqual(digest, check)) throw IOException(tr(R.string.keys_error_wrong_key_password, entry.alias))
        return privateKey(plain)
    }

    class KeyToWrite(val alias: String, val key: PrivateKey, val chain: List<X509Certificate>, val keyPassword: CharArray, val created: Long = System.currentTimeMillis())

    fun write(keys: List<KeyToWrite>, storePassword: CharArray, certificates: Map<String, X509Certificate> = emptyMap(), random: SecureRandom = SecureRandom()): ByteArray {
        val body = ByteArrayOutputStream()
        val out = DataOutputStream(body)
        out.writeInt(MAGIC)
        out.writeInt(VERSION)
        out.writeInt(keys.size + certificates.size)
        fun cert(c: X509Certificate) {
            out.writeUTF("X.509")
            val der = c.encoded
            out.writeInt(der.size)
            out.write(der)
        }
        for (k in keys) {
            require(k.chain.isNotEmpty()) { "A key needs its certificate" }
            out.writeInt(PRIVATE_KEY)
            out.writeUTF(k.alias.lowercase())
            out.writeLong(k.created)
            val protected = protect(k.key, k.keyPassword, random)
            out.writeInt(protected.size)
            out.write(protected)
            out.writeInt(k.chain.size)
            k.chain.forEach(::cert)
        }
        for ((alias, c) in certificates) {
            out.writeInt(TRUSTED_CERT)
            out.writeUTF(alias.lowercase())
            out.writeLong(System.currentTimeMillis())
            cert(c)
        }
        out.flush()
        val data = body.toByteArray()
        val digest = MessageDigest.getInstance("SHA-1").run { update(utf16(storePassword)); update(WHITENER); update(data); digest() }
        return data + digest
    }

    private fun protect(key: PrivateKey, password: CharArray, random: SecureRandom): ByteArray {
        val plain = key.encoded ?: throw IOException(tr(R.string.keys_error_not_exportable))
        val pw = utf16(password)
        val salt = ByteArray(SALT).also(random::nextBytes)
        val encrypted = xor(plain, keyStream(pw, salt, plain.size))
        val check = MessageDigest.getInstance("SHA-1").run { update(pw); update(plain); digest() }
        return EncryptedPrivateKeyInfo(AlgorithmIdentifier(KEY_PROTECTOR, DERNull.INSTANCE), salt + encrypted + check).encoded
    }

    private fun keyStream(pw: ByteArray, salt: ByteArray, length: Int): ByteArray {
        val out = ByteArray(length)
        val md = MessageDigest.getInstance("SHA-1")
        var digest = salt
        var offset = 0
        while (offset < length) {
            md.update(pw)
            md.update(digest)
            digest = md.digest()
            System.arraycopy(digest, 0, out, offset, minOf(DIGEST, length - offset))
            offset += DIGEST
        }
        return out
    }

    private fun xor(a: ByteArray, b: ByteArray) = ByteArray(a.size) { (a[it].toInt() xor b[it].toInt()).toByte() }

    private fun utf16(password: CharArray): ByteArray {
        val out = ByteArray(password.size * 2)
        for (i in password.indices) {
            out[2 * i] = (password[i].code shr 8).toByte()
            out[2 * i + 1] = password[i].code.toByte()
        }
        return out
    }

    fun privateKey(pkcs8: ByteArray): PrivateKey {
        val oid = PrivateKeyInfo.getInstance(pkcs8).privateKeyAlgorithm.algorithm.id
        val algorithm = when (oid) {
            "1.2.840.113549.1.1.1" -> "RSA"
            "1.2.840.10045.2.1" -> "EC"
            "1.2.840.10040.4.1" -> "DSA"
            else -> throw IOException(tr(R.string.keys_error_key_algorithm, oid))
        }
        return KeyFactory.getInstance(algorithm).generatePrivate(PKCS8EncodedKeySpec(pkcs8))
    }
}
