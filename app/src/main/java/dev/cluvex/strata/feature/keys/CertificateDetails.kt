package dev.cluvex.strata.feature.keys

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.cluvex.strata.R
import dev.cluvex.strata.core.apk.KeystoreFile
import dev.cluvex.strata.core.util.Formatters
import java.security.cert.X509Certificate
import java.security.interfaces.ECKey
import java.security.interfaces.RSAKey
import java.util.Date

fun keyDescription(cert: X509Certificate): String {
    val key = cert.publicKey
    val bits = when (key) {
        is RSAKey -> key.modulus.bitLength()
        is ECKey -> key.params.order.bitLength()
        else -> null
    }
    return listOfNotNull(key.algorithm, bits?.toString()).joinToString(" ")
}

fun commonName(cert: X509Certificate): String {
    val dn = cert.subjectX500Principal.name
    return Regex("(?:^|,)CN=([^,]*)").find(dn)?.groupValues?.get(1)?.ifBlank { null } ?: dn
}

@Composable
fun CertificateDetails(cert: X509Certificate, modifier: Modifier = Modifier) {
    val from = Formatters.date(cert.notBefore.time)
    val until = Formatters.date(cert.notAfter.time)
    val valid = if (cert.notAfter.before(Date())) stringResource(R.string.keys_cert_valid_expired, from, until) else "$from – $until"
    val rows = listOf(
        Triple(stringResource(R.string.common_owner), cert.subjectX500Principal.name, false),
        Triple(stringResource(R.string.keys_cert_issuer), cert.issuerX500Principal.name, false),
        Triple(stringResource(R.string.keys_cert_serial), cert.serialNumber.toString(16), true),
        Triple(stringResource(R.string.keys_cert_valid), valid, false),
        Triple(stringResource(R.string.keys_cert_key), keyDescription(cert), false),
        Triple(stringResource(R.string.apk_signature), cert.sigAlgName, false),
        Triple("SHA-256", KeystoreFile.fingerprint(cert, "SHA-256"), true),
        Triple("SHA-1", KeystoreFile.fingerprint(cert, "SHA-1"), true),
        Triple("MD5", KeystoreFile.fingerprint(cert, "MD5"), true),
    )
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for ((label, value, mono) in rows) {
                Column {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(
                        value,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = if (mono) FontFamily.Monospace else null,
                    )
                }
            }
        }
    }
}
