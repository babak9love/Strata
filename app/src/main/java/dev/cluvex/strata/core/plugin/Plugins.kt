package dev.cluvex.strata.core.plugin

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.Signature
import android.os.Build
import dev.cluvex.strata.core.apk.Hashing

data class TextApp(val label: String, val packageName: String, val activityName: String)

object Plugins {
    fun discover(context: Context, mime: String = "*/*"): List<PluginInfo> {
        val pm = context.packageManager
        val intent = Intent(PluginProtocol.ACTION_FILE).setType(mime)
        return query(pm, intent, PackageManager.GET_META_DATA)
            .mapNotNull { info ->
                val a = info.activityInfo ?: return@mapNotNull null
                if (!a.exported || a.packageName == context.packageName) return@mapNotNull null
                val (signer, past) = signers(pm, a.packageName) ?: return@mapNotNull null
                val meta = a.metaData
                PluginInfo(
                    packageName = a.packageName,
                    activityName = a.name,
                    label = info.loadLabel(pm).toString().ifBlank { a.packageName },
                    description = meta?.getString(PluginProtocol.META_DESCRIPTION)?.trim()?.takeIf { it.isNotEmpty() },
                    extensions = PluginProtocol.parseExtensions(meta?.getString(PluginProtocol.META_EXTENSIONS)),
                    api = meta?.getInt(PluginProtocol.META_API, 1)?.coerceAtLeast(1) ?: 1,
                    signer = signer,
                    pastSigners = past,
                )
            }
            .distinctBy { it.packageName to it.activityName }
            .sortedBy { it.label.lowercase() }
    }

    fun textApps(context: Context): List<TextApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
        return query(pm, intent, 0)
            .mapNotNull { info ->
                val a = info.activityInfo ?: return@mapNotNull null
                if (!a.exported || a.packageName == context.packageName) return@mapNotNull null
                TextApp(info.loadLabel(pm).toString().ifBlank { a.packageName }, a.packageName, a.name)
            }
            .distinctBy { it.packageName to it.activityName }
            .sortedBy { it.label.lowercase() }
    }

    @Suppress("DEPRECATION")
    private fun query(pm: PackageManager, intent: Intent, flags: Int): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= 33) pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
        else pm.queryIntentActivities(intent, flags)

    @Suppress("DEPRECATION")
    fun signers(pm: PackageManager, packageName: String): Pair<String, List<String>>? = try {
        if (Build.VERSION.SDK_INT >= 28) {
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo ?: return null
            val current = info.apkContentsSigners.orEmpty().map(::sha256).sorted().joinToString(",")
            val past = if (info.hasMultipleSigners()) emptyList() else info.signingCertificateHistory.orEmpty().map(::sha256).filter { it != current }
            current to past
        } else {
            val sigs = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures.orEmpty()
            sigs.map(::sha256).sorted().joinToString(",") to emptyList()
        }.takeIf { it.first.isNotEmpty() }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun sha256(s: Signature) = Hashing.hex(s.toByteArray(), "SHA-256")

    fun reply(resultCode: Int, data: Intent?): PluginReply {
        val uri = data?.data
        return PluginReply(
            ok = resultCode == android.app.Activity.RESULT_OK,
            text = runCatching { data?.getCharSequenceExtra(PluginProtocol.EXTRA_RESULT_TEXT)?.toString() }.getOrNull(),
            uri = uri?.toString(),
            uriScheme = uri?.scheme,
            uriAuthority = uri?.authority,
            name = runCatching { data?.getStringExtra(PluginProtocol.EXTRA_RESULT_NAME) }.getOrNull(),
            message = runCatching { data?.getCharSequenceExtra(PluginProtocol.EXTRA_MESSAGE)?.toString() }.getOrNull(),
        )
    }

    fun ownsAuthority(context: Context, authority: String): Boolean {
        val host = authority.substringAfterLast('@').substringBefore(':')
        if (host == "${context.packageName}.fileprovider" || host == PluginShare.authority(context)) return true
        @Suppress("DEPRECATION")
        val provider = runCatching { context.packageManager.resolveContentProvider(host, 0) }.getOrNull()
        return provider?.packageName == context.packageName
    }
}
