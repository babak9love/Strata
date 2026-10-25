package dev.cluvex.strata.feature.apk

import dev.cluvex.strata.core.i18n.separator
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.GppBad
import androidx.compose.material.icons.rounded.GppGood
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.foundation.Image
import androidx.annotation.StringRes
import dev.cluvex.strata.R
import dev.cluvex.strata.core.apk.ApkManifestInfo
import dev.cluvex.strata.core.apk.ApkManifestReader
import dev.cluvex.strata.core.apk.ApkSignature
import dev.cluvex.strata.core.apk.ApkSignatureInspector
import dev.cluvex.strata.core.util.Formatters
import dev.cluvex.strata.ui.rememberTextClipboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private data class ApkInfoUi(
    val manifest: ApkManifestInfo?,
    val signature: ApkSignature,
    val icon: ImageBitmap?,
    val size: Long,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ApkInfoScreen(path: String, onBack: () -> Unit, onSign: () -> Unit) {
    val context = LocalContext.current
    val file = remember(path) { File(path) }
    val state by produceState<ApkInfoUi?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val manifest = runCatching { ApkManifestReader(pm).read(path) }.getOrNull()
            val signature = ApkSignatureInspector.inspect(file)
            val icon = runCatching {
                @Suppress("DEPRECATION")
                pm.getPackageArchiveInfo(path, 0)?.applicationInfo?.apply {
                    sourceDir = path; publicSourceDir = path
                }?.loadIcon(pm)?.toBitmap()?.asImageBitmap()
            }.getOrNull()
            ApkInfoUi(manifest, signature, icon, file.length())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back)) } },
                title = { Text(stringResource(R.string.apk_info_title)) },
            )
        },
    ) { padding ->
        val ui = state
        if (ui == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header(ui, file.name)
            ui.manifest?.let { ManifestCard(it, ui.size) }
            SignatureCard(ui.signature)
            ui.manifest?.permissions?.takeIf { it.isNotEmpty() }?.let { PermissionsCard(it) }
            FilledTonalButton(onClick = onSign, modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                Icon(Icons.Rounded.Key, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text(stringResource(R.string.common_sign_apk))
            }
        }
    }
}

@Composable
private fun Header(ui: ApkInfoUi, fileName: String) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (ui.icon != null) {
            Image(ui.icon, contentDescription = null, modifier = Modifier.size(56.dp))
        }
        Column(Modifier.padding(start = 12.dp)) {
            Text(ui.manifest?.label ?: fileName, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                ui.manifest?.packageName ?: fileName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ManifestCard(m: ApkManifestInfo, size: Long) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            InfoRow(stringResource(R.string.apk_version), "${m.versionName} (${m.versionCode})")
            InfoRow(stringResource(R.string.common_size), Formatters.size(size))
            InfoRow(stringResource(R.string.apk_min_sdk), Formatters.number(m.minSdk.toLong()))
            InfoRow(stringResource(R.string.apk_target_sdk), Formatters.number(m.targetSdk.toLong()))
            if (m.compileSdk > 0) InfoRow(stringResource(R.string.apk_compile_sdk), Formatters.number(m.compileSdk.toLong()))
            InfoRow(stringResource(R.string.apk_install_location), stringResource(installLocationLabel(m.installLocation)))
            if (m.debuggable) InfoRow(stringResource(R.string.apk_debuggable), stringResource(R.string.common_yes))
            if (m.splitNames.isNotEmpty()) InfoRow(stringResource(R.string.apk_splits), m.splitNames.joinToString(", "))
        }
    }
}

@Composable
private fun SignatureCard(sig: ApkSignature) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            val (icon, tint, label) = when {
                sig.error != null -> Triple(Icons.Rounded.GppBad, MaterialTheme.colorScheme.error, stringResource(R.string.apk_sig_unreadable))
                sig.verified -> Triple(Icons.Rounded.GppGood, MaterialTheme.colorScheme.primary, stringResource(R.string.apk_sig_verified))
                else -> Triple(Icons.Rounded.GppBad, MaterialTheme.colorScheme.error, stringResource(R.string.apk_sig_invalid))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = tint)
                Text(label, style = MaterialTheme.typography.titleMedium, color = tint, modifier = Modifier.padding(start = 8.dp))
            }
            if (sig.schemes.isNotEmpty()) {
                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    sig.schemes.forEach { AssistChip(onClick = {}, label = { Text(it) }) }
                }
            }
            sig.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp)) }
            sig.certificates.forEachIndexed { i, cert ->
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                if (sig.certificates.size > 1) Text(stringResource(R.string.apk_certificate_n, i + 1), style = MaterialTheme.typography.labelLarge)
                InfoRow(stringResource(R.string.apk_cert_subject), cert.subject)
                if (!cert.selfSigned) InfoRow(stringResource(R.string.keys_cert_issuer), cert.issuer)
                InfoRow(stringResource(R.string.keys_cert_valid), "${Formatters.date(cert.notBefore)} – ${Formatters.date(cert.notAfter)}")
                InfoRow(stringResource(R.string.keys_cert_key), stringResource(R.string.apk_key_bits, cert.keyAlgorithm, cert.keyBits) + separator() + cert.signatureAlgorithm)
                Fingerprint("SHA-256", cert.fingerprints.sha256)
                Fingerprint("SHA-1", cert.fingerprints.sha1)
                Fingerprint("MD5", cert.fingerprints.md5)
            }
        }
    }
}

@Composable
private fun PermissionsCard(permissions: List<String>) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.apk_permissions_count, permissions.size), style = MaterialTheme.typography.titleMedium)
            permissions.forEach {
                Text(
                    it.removePrefix("android.permission."),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun InfoRow(key: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Fingerprint(label: String, value: String) {
    val clipboard = rememberTextClipboard()
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
        IconButton(onClick = { clipboard.setText(value) }) {
            Icon(Icons.Rounded.ContentCopy, contentDescription = stringResource(R.string.apk_copy_named, label))
        }
    }
}

@StringRes
private fun installLocationLabel(value: Int): Int = when (value) {
    0 -> R.string.apk_location_internal
    1 -> R.string.apk_location_auto
    2 -> R.string.apk_location_external
    else -> R.string.apk_location_unspecified
}
