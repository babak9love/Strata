package dev.cluvex.strata.core.apk

import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.io.File
import java.io.IOException
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

class SavedKeys(private val dir: File) {
    data class Saved(val id: String, val name: String, val certificate: X509Certificate) {
        val isDefault: Boolean get() = id == DEFAULT
    }

    private val bc = BouncyCastleProvider()

    fun list(): List<Saved> {
        dir.mkdirs()
        val default = Saved(DEFAULT, DEFAULT_NAME, certificateOf(DEFAULT))
        val others = dir.listFiles { f -> f.isFile && f.name.endsWith(".p12") && f.name != "$DEFAULT.p12" }.orEmpty()
            .mapNotNull { f -> runCatching { val id = f.name.removeSuffix(".p12"); Saved(id, nameOf(id), certificateOf(id)) }.getOrNull() }
            .sortedBy { it.name.lowercase() }
        return listOf(default) + others
    }

    private fun certificateOf(id: String): X509Certificate {
        val crt = File(dir, "$id.crt")
        val stored = if (File(dir, "$id.p12").isFile) {
            runCatching { crt.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate } }.getOrNull()
        } else {
            null
        }
        return stored ?: load(id).certificates.first().also { c -> runCatching { crt.writeBytes(c.encoded) } }
    }

    fun load(id: String): SigningKey {
        if (id == DEFAULT) return SigningKeys.default(dir.parentFile!!)
        val file = File(dir, "$id.p12").takeIf { it.isFile && safeId(id) } ?: throw IOException(tr(R.string.keys_error_no_saved, id))
        return SigningKeys.cached(file) {
            val store = KeyStore.getInstance("PKCS12", bc).apply { file.inputStream().use { load(it, PASSWORD) } }
            val key = store.getKey(ALIAS, PASSWORD) as PrivateKey
            SigningKey(key, store.getCertificateChain(ALIAS).filterIsInstance<X509Certificate>())
        }
    }

    fun save(name: String, key: SigningKey, random: SecureRandom = SecureRandom()): Saved {
        require(name.isNotBlank()) { tr(R.string.keys_error_name_required) }
        dir.mkdirs()
        val id = generateSequence { ByteArray(6).also(random::nextBytes).joinToString("") { "%02x".format(it) } }.first { !File(dir, "$it.p12").exists() }
        val store = KeyStore.getInstance("PKCS12", bc).apply {
            load(null, null)
            setKeyEntry(ALIAS, key.privateKey, PASSWORD, key.certificates.toTypedArray())
        }
        val file = File(dir, "$id.p12")
        file.outputStream().use { store.store(it, PASSWORD) }
        File(dir, "$id.crt").writeBytes(key.certificates.first().encoded)
        File(dir, "$id.name").writeText(name.trim())
        SigningKeys.remember(file, key)
        return Saved(id, name.trim(), key.certificates.first())
    }

    fun rename(id: String, name: String) {
        require(id != DEFAULT && safeId(id) && name.isNotBlank())
        File(dir, "$id.name").writeText(name.trim())
    }

    fun delete(id: String) {
        require(id != DEFAULT && safeId(id)) { tr(R.string.keys_error_default_stays) }
        SigningKeys.forget(File(dir, "$id.p12"))
        File(dir, "$id.p12").delete()
        File(dir, "$id.crt").delete()
        File(dir, "$id.name").delete()
    }

    private fun nameOf(id: String) = File(dir, "$id.name").takeIf { it.isFile }?.readText()?.trim()?.ifEmpty { null } ?: id

    private fun safeId(id: String) = id.isNotEmpty() && id.all { it in '0'..'9' || it in 'a'..'f' }

    companion object {
        const val DEFAULT = "default"
        val DEFAULT_NAME: String get() = tr(R.string.keys_default_name)
        private const val ALIAS = "key"
        private val PASSWORD = "android".toCharArray()
    }
}
