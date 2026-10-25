package dev.cluvex.strata.core.apk

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build

data class ApkManifestInfo(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val compileSdk: Int,
    val debuggable: Boolean,
    val installLocation: Int,
    val permissions: List<String>,
    val splitNames: List<String>,
)

class ApkManifestReader(private val pm: PackageManager) {

    @Suppress("DEPRECATION")
    fun read(path: String): ApkManifestInfo? {
        val info: PackageInfo = pm.getPackageArchiveInfo(path, PackageManager.GET_PERMISSIONS) ?: return null
        val app = info.applicationInfo ?: return null
        app.sourceDir = path
        app.publicSourceDir = path
        val versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        val compileSdk = if (Build.VERSION.SDK_INT >= 31) app.compileSdkVersion else 0
        return ApkManifestInfo(
            packageName = info.packageName.orEmpty(),
            label = runCatching { app.loadLabel(pm).toString() }.getOrDefault(info.packageName.orEmpty()),
            versionName = info.versionName.orEmpty(),
            versionCode = versionCode,
            minSdk = app.minSdkVersion,
            targetSdk = app.targetSdkVersion,
            compileSdk = compileSdk,
            debuggable = (app.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0,
            installLocation = info.installLocation,
            permissions = info.requestedPermissions?.toList().orEmpty().sorted(),
            splitNames = info.splitNames?.toList().orEmpty(),
        )
    }
}
