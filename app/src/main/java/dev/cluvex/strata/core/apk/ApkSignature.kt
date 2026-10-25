package dev.cluvex.strata.core.apk

import java.security.MessageDigest
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.security.interfaces.ECKey
import java.security.interfaces.RSAKey

data class Fingerprints(val sha256: String, val sha1: String, val md5: String)

data class ApkCertificate(
    val subject: String,
    val issuer: String,
    val serialHex: String,
    val notBefore: Long,
    val notAfter: Long,
    val signatureAlgorithm: String,
    val keyAlgorithm: String,
    val keyBits: Int,
    val fingerprints: Fingerprints,
) {
    val selfSigned: Boolean get() = subject == issuer
}

data class ApkSignature(
    val verified: Boolean,
    val v1: Boolean,
    val v2: Boolean,
    val v3: Boolean,
    val v31: Boolean,
    val v4: Boolean,
    val certificates: List<ApkCertificate>,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
    val error: String? = null,
) {
    val schemes: List<String>
        get() = buildList {
            if (v1) add("v1")
            if (v2) add("v2")
            if (v3) add("v3")
            if (v31) add("v3.1")
            if (v4) add("v4")
        }

    companion object {
        fun failure(message: String) = ApkSignature(false, false, false, false, false, false, emptyList(), error = message)
    }
}

internal object Hashing {
    fun hex(bytes: ByteArray, algorithm: String): String =
        MessageDigest.getInstance(algorithm).digest(bytes).joinToString(":") { "%02X".format(it) }

    fun fingerprints(der: ByteArray) = Fingerprints(
        sha256 = hex(der, "SHA-256"),
        sha1 = hex(der, "SHA-1"),
        md5 = hex(der, "MD5"),
    )

    fun keyBits(key: PublicKey): Int = when (key) {
        is RSAKey -> key.modulus.bitLength()
        is ECKey -> key.params.curve.field.fieldSize
        else -> 0
    }
}

internal fun X509Certificate.toApkCertificate(): ApkCertificate = ApkCertificate(
    subject = subjectX500Principal.name,
    issuer = issuerX500Principal.name,
    serialHex = serialNumber.toString(16).uppercase(),
    notBefore = notBefore.time,
    notAfter = notAfter.time,
    signatureAlgorithm = sigAlgName,
    keyAlgorithm = publicKey.algorithm,
    keyBits = Hashing.keyBits(publicKey),
    fingerprints = Hashing.fingerprints(encoded),
)
