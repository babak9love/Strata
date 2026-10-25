package dev.cluvex.strata.core.apk

import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date

data class KeyIdentity(
    val commonName: String,
    val organizationalUnit: String = "",
    val organization: String = "",
    val locality: String = "",
    val state: String = "",
    val country: String = "",
    val validityYears: Int = 30,
    val keyAlgorithm: String = "RSA",
    val keySizeBits: Int = 2048,
)

data class KeystoreSpec(
    val file: File,
    val storePassword: CharArray,
    val alias: String,
    val keyPassword: CharArray,
    val type: String = "PKCS12",
)

data class SigningKey(val privateKey: PrivateKey, val certificates: List<X509Certificate>)

object KeyStoreFactory {

    private val bc = BouncyCastleProvider()

    fun generate(spec: KeystoreSpec, identity: KeyIdentity, random: SecureRandom = SecureRandom()): SigningKey {
        val key = generateKey(identity, random)
        val format = when (spec.type.uppercase()) {
            "JKS" -> KeystoreFormat.JKS
            "BKS" -> KeystoreFormat.BKS
            else -> KeystoreFormat.PKCS12
        }
        spec.file.parentFile?.mkdirs()
        spec.file.writeBytes(KeystoreFile.export(format, listOf(spec.alias to key), spec.storePassword, spec.keyPassword))
        return key
    }

    fun generateKey(identity: KeyIdentity, random: SecureRandom = SecureRandom()): SigningKey {
        require(identity.commonName.isNotBlank()) { tr(R.string.keys_error_cn_required) }
        val keyGen = KeyPairGenerator.getInstance(identity.keyAlgorithm)
        keyGen.initialize(identity.keySizeBits, random)
        val keyPair = keyGen.generateKeyPair()

        val name = x500(identity)
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 24L * 60 * 60 * 1000)
        val notAfter = Date(now + identity.validityYears.coerceAtLeast(1) * 365L * 24 * 60 * 60 * 1000)
        val serial = BigInteger(64, random).abs().let { if (it.signum() == 0) BigInteger.ONE else it }

        val signatureAlgorithm = if (identity.keyAlgorithm.equals("EC", true)) "SHA256withECDSA" else "SHA256withRSA"
        val builder = JcaX509v3CertificateBuilder(name, serial, notBefore, notAfter, name, keyPair.public)
        val signer = JcaContentSignerBuilder(signatureAlgorithm).setProvider(bc).build(keyPair.private)
        val certificate = JcaX509CertificateConverter().setProvider(bc).getCertificate(builder.build(signer))

        return SigningKey(keyPair.private, listOf(certificate))
    }

    fun load(spec: KeystoreSpec): SigningKey {
        val store = KeystoreFile.open(spec.file, spec.storePassword)
        val alias = spec.alias.ifBlank { store.keyAliases.firstOrNull() ?: throw IllegalArgumentException(tr(R.string.keys_error_no_key_in_store)) }
        return store.key(alias, spec.keyPassword)
    }

    fun aliases(file: File, storePassword: CharArray): List<String> = KeystoreFile.open(file, storePassword).keyAliases

    private fun x500(id: KeyIdentity): X500Name = X500NameBuilder(BCStyle.INSTANCE).apply {
        addRDN(BCStyle.CN, id.commonName)
        if (id.organizationalUnit.isNotBlank()) addRDN(BCStyle.OU, id.organizationalUnit)
        if (id.organization.isNotBlank()) addRDN(BCStyle.O, id.organization)
        if (id.locality.isNotBlank()) addRDN(BCStyle.L, id.locality)
        if (id.state.isNotBlank()) addRDN(BCStyle.ST, id.state)
        if (id.country.isNotBlank()) addRDN(BCStyle.C, id.country)
    }.build()
}
