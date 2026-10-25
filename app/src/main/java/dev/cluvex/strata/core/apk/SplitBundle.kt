package dev.cluvex.strata.core.apk

import com.android.apksig.apk.ApkUtils
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

sealed interface SplitKind {
    data object Base : SplitKind
    data class Abi(val abi: String) : SplitKind
    data class Density(val density: String) : SplitKind
    data class Language(val language: String) : SplitKind
    data class Feature(val name: String) : SplitKind
}

data class SplitApk(val entry: String, val size: Long, val kind: SplitKind) {
    val fileName: String get() = entry.substringAfterLast('/')
}

data class SplitBundle(val apks: List<SplitApk>, val obbEntries: List<String>, val packageName: String?) {
    val base: SplitApk? get() = apks.firstOrNull { it.kind == SplitKind.Base }
}

data class BaseInfo(val packageName: String, val versionCode: Long, val minSdk: Int)

data class DeviceConfig(val abis: List<String>, val densityDpi: Int, val languages: List<String>)

object SplitBundles {
    private val abiNames = listOf("arm64_v8a", "armeabi_v7a", "armeabi", "x86_64", "x86", "mips64", "mips")

    private val densities = linkedMapOf(
        "ldpi" to 120, "mdpi" to 160, "tvdpi" to 213, "hdpi" to 240,
        "xhdpi" to 320, "xxhdpi" to 480, "xxxhdpi" to 640,
    )

    fun read(bundle: File): SplitBundle = ZipFile(bundle).use { zip ->
        val entries = zip.entries().asSequence().filterNot { it.isDirectory }.toList()
        val apks = entries.filter { it.name.endsWith(".apk", ignoreCase = true) }
            .map { SplitApk(it.name, it.size, kindOf(it.name)) }
        val obbs = entries.map { it.name }.filter { it.endsWith(".obb", ignoreCase = true) }
        val pkg = zip.getEntry("manifest.json")?.let { e ->
            zip.getInputStream(e).bufferedReader().use { packageFromXapkManifest(it.readText()) }
        }
        val normalized = if (apks.none { it.kind == SplitKind.Base } && apks.size == 1) {
            listOf(apks.single().copy(kind = SplitKind.Base))
        } else {
            apks
        }
        SplitBundle(normalized, obbs, pkg)
    }

    fun kindOf(entry: String): SplitKind {
        val name = entry.substringAfterLast('/').removeSuffix(".apk").removeSuffix(".APK")
        if (name == "base" || name == "base-master") return SplitKind.Base
        val config = name.removePrefix("split_").let { n ->
            when {
                n.startsWith("config.") -> n.removePrefix("config.")
                n.startsWith("base-") -> n.removePrefix("base-")
                else -> null
            }
        } ?: return if (name.contains("config.") || name.startsWith("split_")) SplitKind.Feature(name) else SplitKind.Base
        return when {
            config in abiNames -> SplitKind.Abi(config)
            config in densities -> SplitKind.Density(config)
            config.length in 2..3 && config.all { it.isLetter() } -> SplitKind.Language(config.lowercase(Locale.ROOT))
            config.matches(Regex("[a-z]{2,3}_[A-Za-z]{2,4}")) -> SplitKind.Language(config.substringBefore('_').lowercase(Locale.ROOT))
            else -> SplitKind.Feature(config)
        }
    }

    fun select(bundle: SplitBundle, device: DeviceConfig): List<SplitApk> {
        val apks = bundle.apks
        val abiSplits = apks.filter { it.kind is SplitKind.Abi }
        val bestAbi = device.abis.map { it.replace('-', '_') }.firstNotNullOfOrNull { abi ->
            abiSplits.firstOrNull { (it.kind as SplitKind.Abi).abi == abi }
        }
        val densitySplits = apks.filter { it.kind is SplitKind.Density }
        val bestDensity = densitySplits.minByOrNull { split ->
            val dpi = densities.getValue((split.kind as SplitKind.Density).density)
            if (dpi >= device.densityDpi) dpi - device.densityDpi else 10_000 + (device.densityDpi - dpi)
        }
        val wanted = device.languages.map { it.lowercase(Locale.ROOT).substringBefore('-').substringBefore('_') }.toSet()
        val languageSplits = apks.filter { it.kind is SplitKind.Language }
        val languages = languageSplits.filter { (it.kind as SplitKind.Language).language in wanted }
            .ifEmpty { languageSplits.filter { (it.kind as SplitKind.Language).language == "en" } }
        return apks.filter {
            it.kind == SplitKind.Base || it.kind is SplitKind.Feature ||
                it == bestAbi || it == bestDensity || it in languages
        }
    }

    fun readBaseInfo(bundle: File, base: SplitApk): BaseInfo? = runCatching {
        ZipFile(bundle).use { zip ->
            val entry = zip.getEntry(base.entry) ?: return null
            ZipInputStream(zip.getInputStream(entry).buffered()).use { inner ->
                generateSequence { inner.nextEntry }.firstOrNull { it.name == ApkUtils.ANDROID_MANIFEST_ZIP_ENTRY_NAME }
                    ?: return null
                val bytes = inner.readBytes()
                fun manifest() = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                BaseInfo(
                    packageName = ApkUtils.getPackageNameFromBinaryAndroidManifest(manifest()),
                    versionCode = ApkUtils.getLongVersionCodeFromBinaryAndroidManifest(manifest()),
                    minSdk = runCatching { ApkUtils.getMinSdkVersionFromBinaryAndroidManifest(manifest()) }.getOrDefault(1),
                )
            }
        }
    }.getOrNull()

    fun packageFromXapkManifest(json: String): String? =
        Regex("\"package_name\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)

    fun obbTarget(storageRoot: String, entry: String): String? {
        val idx = entry.indexOf("Android/obb/")
        if (idx < 0) return null
        val rel = entry.substring(idx)
        if (rel.split('/').any { it == ".." }) return null
        return "$storageRoot/$rel"
    }
}
