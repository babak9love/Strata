package dev.cluvex.strata.core.apk

import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8DecryptorProviderBuilder
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo
import org.bouncycastle.util.encoders.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.UnrecoverableKeyException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Locale

enum class KeystoreFormat(val label: String, val extension: String) {
    JKS("JKS (Java KeyStore)", "jks"),
    PKCS12("PKCS #12", "p12"),
    BKS("BKS (Bouncy Castle)", "bks"),
}

data class KeystoreEntryInfo(val alias: String, val isKey: Boolean, val created: Long?, val chain: List<X509Certificate>) {
    val certificate: X509Certificate? get() = chain.firstOrNull()
}

class OpenedKeystore internal constructor(
    val format: KeystoreFormat,
    val entries: List<KeystoreEntryInfo>,
    private val jks: List<JksFormat.Entry>?,
    private val store: KeyStore?,
) {
    val keyAliases: List<String> get() = entries.filter { it.isKey }.map { it.alias }

    fun key(alias: String, keyPassword: CharArray): SigningKey {
        val info = entries.firstOrNull { it.alias.equals(alias, ignoreCase = format == KeystoreFormat.JKS) && it.isKey }
            ?: throw IOException(tr(R.string.keys_error_no_key_named, alias))
        val key: PrivateKey = if (jks != null) {
            JksFormat.recoverKey(jks.first { it.alias == info.alias }, keyPassword)
        } else {
            try {
                store!!.getKey(info.alias, keyPassword) as? PrivateKey ?: throw IOException(tr(R.string.keys_error_no_private_key, alias))
            } catch (e: UnrecoverableKeyException) {
                throw IOException(tr(R.string.keys_error_wrong_key_password, alias), e)
            }
        }
        if (info.chain.isEmpty()) throw IOException(tr(R.string.keys_error_key_no_cert, alias))
        return SigningKey(key, info.chain)
    }
}

object KeystoreFile {

    private val bc = BouncyCastleProvider()
    private const val JCEKS_MAGIC = 0xCECECECE.toInt()

    fun detect(bytes: ByteArray): KeystoreFormat {
        if (bytes.size < 4) throw IOException(tr(R.string.keys_error_not_keystore))
        val magic = (bytes[0].toInt() and 0xFF shl 24) or (bytes[1].toInt() and 0xFF shl 16) or (bytes[2].toInt() and 0xFF shl 8) or (bytes[3].toInt() and 0xFF)
        return when {
            magic == JksFormat.MAGIC -> KeystoreFormat.JKS
            magic == JCEKS_MAGIC -> throw IOException(tr(R.string.keys_error_jceks))
            bytes[0].toInt() == 0x30 -> KeystoreFormat.PKCS12
            magic == 1 || magic == 2 -> KeystoreFormat.BKS
            else -> throw IOException(tr(R.string.keys_error_unknown_keystore))
        }
    }

    fun open(bytes: ByteArray, storePassword: CharArray): OpenedKeystore = when (val format = detect(bytes)) {
        KeystoreFormat.JKS -> {
            val entries = JksFormat.read(bytes, storePassword).sortedBy { it.alias }
            OpenedKeystore(format, entries.map { KeystoreEntryInfo(it.alias, it.isKey, it.created, it.certificates) }, entries, null)
        }
        else -> {
            val store = KeyStore.getInstance(if (format == KeystoreFormat.BKS) "BKS" else "PKCS12", bc)
            try {
                store.load(ByteArrayInputStream(bytes), storePassword)
            } catch (e: IOException) {
                val m = e.message.orEmpty().lowercase(Locale.ROOT)
                throw if (e.cause is UnrecoverableKeyException || "password" in m || "mac" in m || "integrity" in m) {
                    JksFormat.WrongPasswordException()
                } else e
            }
            val entries = store.aliases().toList().sorted().map { alias ->
                val chain = (store.getCertificateChain(alias) ?: store.getCertificate(alias)?.let { arrayOf(it) } ?: emptyArray())
                    .filterIsInstance<X509Certificate>()
                KeystoreEntryInfo(alias, store.isKeyEntry(alias), runCatching { store.getCreationDate(alias)?.time }.getOrNull(), chain)
            }
            OpenedKeystore(format, entries, null, store)
        }
    }

    fun open(file: File, storePassword: CharArray): OpenedKeystore = open(file.readBytes(), storePassword)

    fun export(format: KeystoreFormat, keys: List<Pair<String, SigningKey>>, storePassword: CharArray, keyPassword: CharArray = storePassword): ByteArray {
        require(keys.isNotEmpty()) { "Nothing to export" }
        return when (format) {
            KeystoreFormat.JKS -> JksFormat.write(keys.map { (alias, k) -> JksFormat.KeyToWrite(alias, k.privateKey, k.certificates, keyPassword) }, storePassword)
            else -> {
                val store = KeyStore.getInstance(if (format == KeystoreFormat.BKS) "BKS" else "PKCS12", bc).apply { load(null, null) }
                for ((alias, k) in keys) store.setKeyEntry(alias, k.privateKey, keyPassword, k.certificates.toTypedArray())
                ByteArrayOutputStream().also { store.store(it, storePassword) }.toByteArray()
            }
        }
    }

    fun pk8Pair(file: File): Pair<File, File>? {
        val dir = file.parentFile ?: return null
        val base = when {
            file.name.endsWith(".pk8", ignoreCase = true) -> file.name.dropLast(4)
            file.name.endsWith(".x509.pem", ignoreCase = true) -> file.name.dropLast(9)
            else -> return null
        }
        val pk8 = File(dir, "$base.pk8")
        val pem = File(dir, "$base.x509.pem")
        return if (pk8.isFile && pem.isFile) pk8 to pem else null
    }

    fun fromPk8(pk8: ByteArray, certificate: ByteArray, password: CharArray? = null): SigningKey {
        val key = try {
            JksFormat.privateKey(pk8)
        } catch (e: Exception) {
            val encrypted = runCatching { PKCS8EncryptedPrivateKeyInfo(pk8) }.getOrNull() ?: throw IOException(tr(R.string.keys_error_not_pkcs8), e)
            if (password == null) throw NeedsPasswordException()
            val info = try {
                encrypted.decryptPrivateKeyInfo(JceOpenSSLPKCS8DecryptorProviderBuilder().setProvider(bc).build(password))
            } catch (e2: Exception) {
                throw IOException(tr(R.string.keys_error_wrong_pk8_password), e2)
            }
            JksFormat.privateKey(info.encoded)
        }
        val certs = CertificateFactory.getInstance("X.509").generateCertificates(ByteArrayInputStream(certificate)).filterIsInstance<X509Certificate>()
        if (certs.isEmpty()) throw IOException(tr(R.string.keys_error_no_cert_in_pem))
        return SigningKey(key, certs)
    }

    class NeedsPasswordException : IOException(tr(R.string.keys_error_pk8_protected))

    fun pk8(key: SigningKey): ByteArray = key.privateKey.encoded ?: throw IOException(tr(R.string.keys_error_not_exportable))

    fun x509Pem(key: SigningKey): String = key.certificates.joinToString("") { pem(it) }

    fun pem(cert: X509Certificate): String =
        "-----BEGIN CERTIFICATE-----\n" + Base64.toBase64String(cert.encoded).chunked(64).joinToString("\n") + "\n-----END CERTIFICATE-----\n"

    fun fingerprint(cert: X509Certificate, algorithm: String = "SHA-256"): String =
        MessageDigest.getInstance(algorithm).digest(cert.encoded).joinToString(":") { "%02X".format(Locale.ROOT, it) }
}
