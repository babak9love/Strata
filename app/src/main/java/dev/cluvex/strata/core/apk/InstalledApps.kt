package dev.cluvex.strata.core.apk

import androidx.annotation.StringRes
import dev.cluvex.strata.R

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment

data class InstalledApp(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val sourceDir: String,
    val splitSourceDirs: List<String>,
    val dataDir: String?,
    val externalDataDir: String,
    val uid: Int,
    val firstInstall: Long,
    val lastUpdate: Long,
    val system: Boolean,
    val enabled: Boolean,
    val launchable: Boolean,
) {
    val apkPaths: List<String> get() = listOf(sourceDir) + splitSourceDirs
    val isSplit: Boolean get() = splitSourceDirs.isNotEmpty()
}

enum class VersionRelation(@StringRes val label: Int) {
    NOT_INSTALLED(R.string.model_version_not_installed),
    UPGRADE(R.string.model_version_upgrade),
    SAME(R.string.model_version_same),
    DOWNGRADE(R.string.model_version_downgrade),
    ;

    companion object {
        fun of(fileVersionCode: Long, installed: InstalledApp?): VersionRelation = when {
            installed == null -> NOT_INSTALLED
            fileVersionCode > installed.versionCode -> UPGRADE
            fileVersionCode == installed.versionCode -> SAME
            else -> DOWNGRADE
        }
    }
}

class InstalledApps(private val pm: PackageManager) {

    fun find(packageName: String): InstalledApp? = runCatching {
        @Suppress("DEPRECATION")
        pm.getPackageInfo(packageName, 0).toInstalled()
    }.getOrNull()

    fun list(includeSystem: Boolean): List<InstalledApp> {
        @Suppress("DEPRECATION")
        val packages = pm.getInstalledPackages(0)
        return packages.mapNotNull { runCatching { it.toInstalled() }.getOrNull() }
            .filter { includeSystem || !it.system }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    private fun PackageInfo.toInstalled(): InstalledApp? {
        val app = applicationInfo ?: return null
        val code = if (Build.VERSION.SDK_INT >= 28) longVersionCode else @Suppress("DEPRECATION") versionCode.toLong()
        val storage = Environment.getExternalStorageDirectory().absolutePath
        return InstalledApp(
            packageName = packageName,
            label = runCatching { app.loadLabel(pm).toString() }.getOrDefault(packageName),
            versionName = versionName.orEmpty(),
            versionCode = code,
            sourceDir = app.sourceDir.orEmpty(),
            splitSourceDirs = app.splitSourceDirs?.toList().orEmpty(),
            dataDir = app.dataDir,
            externalDataDir = "$storage/Android/data/$packageName",
            uid = app.uid,
            firstInstall = firstInstallTime,
            lastUpdate = lastUpdateTime,
            system = (app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0,
            enabled = app.enabled,
            launchable = pm.getLaunchIntentForPackage(packageName) != null,
        )
    }
}
