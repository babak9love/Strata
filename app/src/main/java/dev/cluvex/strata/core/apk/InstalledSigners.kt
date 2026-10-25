package dev.cluvex.strata.core.apk

import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Locale

data class AppSigners(
    val current: List<X509Certificate>,
    val past: List<X509Certificate>,
    val multipleSigners: Boolean,
) {
    val primary: X509Certificate? get() = current.firstOrNull()
}

object InstalledSigners {

    fun of(pm: PackageManager, packageName: String): AppSigners? = runCatching {
        if (Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo ?: return null
            val multiple = info.hasMultipleSigners()
            val current = (if (multiple) info.apkContentsSigners else info.signingCertificateHistory?.takeLast(1)?.toTypedArray())
                .orEmpty().mapNotNull(::x509)
            val past = if (multiple) emptyList() else info.signingCertificateHistory.orEmpty().dropLast(1).mapNotNull(::x509)
            AppSigners(current, past, multiple)
        } else {
            @Suppress("DEPRECATION")
            val sigs = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures.orEmpty()
            AppSigners(sigs.mapNotNull(::x509), emptyList(), sigs.size > 1)
        }
    }.getOrNull()

    private fun x509(s: Signature): X509Certificate? = runCatching {
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(s.toByteArray())) as X509Certificate
    }.getOrNull()

    fun label(cert: X509Certificate): String {
        val dn = cert.subjectX500Principal.name
        fun attr(key: String) = Regex("(?:^|,)$key=((?:\\\\,|[^,])*)").find(dn)?.groupValues?.get(1)?.replace("\\,", ",")?.trim()?.ifBlank { null }
        return attr("CN") ?: attr("O") ?: dn
    }

    fun shortHash(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).take(4).joinToString("") { "%02X".format(Locale.ROOT, it) }

    fun sha256(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(Locale.ROOT, it) }

    fun isDebugKey(cert: X509Certificate): Boolean = cert.subjectX500Principal.name.contains("CN=Android Debug")
}
