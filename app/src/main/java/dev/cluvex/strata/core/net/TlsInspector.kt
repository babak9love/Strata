package dev.cluvex.strata.core.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Locale
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class CertInfo(
    val subject: String,
    val issuer: String,
    val serial: String,
    val notBefore: String,
    val notAfter: String,
    val subjectAltNames: List<String>,
    val signatureAlgorithm: String,
    val keyAlgorithm: String,
    val keyBits: Int,
    val sha256: String,
    val sha1: String,
)

data class TlsReport(val protocol: String, val cipherSuite: String, val chain: List<CertInfo>)

object TlsInspector {

    fun describe(cert: X509Certificate): CertInfo {
        val sans = runCatching {
            cert.subjectAlternativeNames?.mapNotNull { entry ->
                val type = entry.getOrNull(0) as? Int
                val value = entry.getOrNull(1)?.toString() ?: return@mapNotNull null
                when (type) { 2 -> "DNS:$value"; 7 -> "IP:$value"; 1 -> "email:$value"; else -> value }
            }.orEmpty()
        }.getOrDefault(emptyList())
        return CertInfo(
            subject = cert.subjectX500Principal.name,
            issuer = cert.issuerX500Principal.name,
            serial = cert.serialNumber.toString(16).uppercase(Locale.ROOT),
            notBefore = cert.notBefore.toString(),
            notAfter = cert.notAfter.toString(),
            subjectAltNames = sans,
            signatureAlgorithm = cert.sigAlgName,
            keyAlgorithm = cert.publicKey.algorithm,
            keyBits = keyBits(cert),
            sha256 = fingerprint(cert, "SHA-256"),
            sha1 = fingerprint(cert, "SHA-1"),
        )
    }

    fun fingerprint(cert: X509Certificate, algorithm: String): String =
        MessageDigest.getInstance(algorithm).digest(cert.encoded).joinToString(":") { "%02X".format(Locale.ROOT, it) }

    private fun keyBits(cert: X509Certificate): Int = when (val k = cert.publicKey) {
        is java.security.interfaces.RSAPublicKey -> k.modulus.bitLength()
        is java.security.interfaces.ECPublicKey -> k.params.curve.field.fieldSize
        else -> 0
    }

    suspend fun inspect(host: String, port: Int = 443, timeoutMs: Int = 8000): TlsReport = withContext(Dispatchers.IO) {
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(AllowAll), null) }
        (ctx.socketFactory.createSocket() as SSLSocket).use { socket ->
            socket.connect(java.net.InetSocketAddress(host, port), timeoutMs)
            socket.soTimeout = timeoutMs
            socket.sslParameters = (socket.sslParameters ?: SSLParameters()).apply {
                serverNames = listOf(SNIHostName(host))
            }
            socket.startHandshake()
            val session = socket.session
            val chain = session.peerCertificates.filterIsInstance<X509Certificate>().map(::describe)
            TlsReport(session.protocol, session.cipherSuite, chain)
        }
    }

    private object AllowAll : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
