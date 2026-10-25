package dev.cluvex.strata.feature.apk

import android.app.Application
import android.content.res.Resources
import android.os.Build
import android.os.Environment
import androidx.core.os.ConfigurationCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.cluvex.strata.R
import dev.cluvex.strata.core.apk.BaseInfo
import dev.cluvex.strata.core.apk.DeviceConfig
import dev.cluvex.strata.core.apk.InstalledApp
import dev.cluvex.strata.core.apk.InstalledApps
import dev.cluvex.strata.core.apk.SplitBundle
import dev.cluvex.strata.core.apk.SplitBundles
import dev.cluvex.strata.core.apk.SplitKind
import dev.cluvex.strata.core.apk.install.ApkInstaller
import dev.cluvex.strata.core.apk.install.ApkSource
import dev.cluvex.strata.core.apk.install.InstallFailure
import dev.cluvex.strata.core.apk.install.InstallResult
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.root.RootManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

data class SplitInstallState(
    val path: String = "",
    val loading: Boolean = true,
    val bundle: SplitBundle? = null,
    val info: BaseInfo? = null,
    val installed: InstalledApp? = null,
    val selected: Set<String> = emptySet(),
    val install: InstallPhase = InstallPhase.Idle,
    val error: String? = null,
    val obbCopied: Int = 0,
) {
    val fileName: String get() = File(path).name
    val packageName: String? get() = info?.packageName ?: bundle?.packageName
    val selectedSize: Long get() = bundle?.apks?.filter { it.entry in selected }?.sumOf { it.size.coerceAtLeast(0) } ?: 0
}

class SplitInstallViewModel(app: Application) : AndroidViewModel(app) {

    private val installer = ApkInstaller(app)
    private val _state = MutableStateFlow(SplitInstallState())
    val state: StateFlow<SplitInstallState> = _state.asStateFlow()

    fun load(path: String) {
        if (_state.value.path == path && !_state.value.loading) return
        _state.value = SplitInstallState(path = path)
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val bundle = SplitBundles.read(File(path))
                    val info = bundle.base?.let { SplitBundles.readBaseInfo(File(path), it) }
                    val pkg = info?.packageName ?: bundle.packageName
                    val installed = pkg?.let { InstalledApps(getApplication<Application>().packageManager).find(it) }
                    Triple(bundle, info, installed)
                }
            }
            result.onSuccess { (bundle, info, installed) ->
                val selected = SplitBundles.select(bundle, deviceConfig()).mapTo(HashSet()) { it.entry }
                _state.update {
                    it.copy(
                        loading = false,
                        bundle = bundle,
                        info = info,
                        installed = installed,
                        selected = selected,
                        error = if (bundle.base == null) tr(R.string.apk_split_no_base) else null,
                    )
                }
            }.onFailure { e ->
                _state.update { it.copy(loading = false, error = e.message ?: e.javaClass.simpleName) }
            }
        }
    }

    fun toggle(entry: String) {
        val bundle = _state.value.bundle ?: return
        if (bundle.apks.firstOrNull { it.entry == entry }?.kind == SplitKind.Base) return
        _state.update { it.copy(selected = if (entry in it.selected) it.selected - entry else it.selected + entry) }
    }

    fun install(policy: InstallPolicy, allowDowngrade: Boolean = false) {
        val s = _state.value
        val bundle = s.bundle ?: return
        if (s.install is InstallPhase.Installing) return
        val file = File(s.path)
        val parts = bundle.apks.filter { it.entry in s.selected }
            .sortedBy { if (it.kind == SplitKind.Base) 0 else 1 }
            .map { ApkSource.BundleEntry(file, it.entry, it.size) }
        viewModelScope.launch {
            val viaRoot = policy.rootMode && policy.installWithRoot && RootManager.request()
            _state.update { it.copy(install = InstallPhase.Installing(viaRoot)) }
            val result = try {
                if (viaRoot) installer.installWithRoot(parts, s.packageName, allowDowngrade)
                else installer.installWithSystem(parts, s.packageName)
            } catch (e: Exception) {
                InstallResult.Failure(InstallFailure.OTHER, e.message ?: e.javaClass.simpleName)
            }
            val obbs = if (result is InstallResult.Success) withContext(Dispatchers.IO) { copyObbs(file, bundle) } else 0
            val installed = s.packageName?.let { pkg ->
                withContext(Dispatchers.IO) { InstalledApps(getApplication<Application>().packageManager).find(pkg) }
            }
            _state.update { it.copy(install = InstallPhase.Done(result, viaRoot), obbCopied = obbs, installed = installed) }
        }
    }

    fun resetInstall() = _state.update { it.copy(install = InstallPhase.Idle) }

    private fun copyObbs(bundle: File, info: SplitBundle): Int {
        if (info.obbEntries.isEmpty()) return 0
        val storage = Environment.getExternalStorageDirectory().absolutePath
        var copied = 0
        ZipFile(bundle).use { zip ->
            for (name in info.obbEntries) {
                val target = SplitBundles.obbTarget(storage, name) ?: continue
                val entry = zip.getEntry(name) ?: continue
                runCatching {
                    val out = File(target)
                    out.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
                    copied++
                }
            }
        }
        return copied
    }

    private fun deviceConfig(): DeviceConfig {
        val config = Resources.getSystem().configuration
        val locales = ConfigurationCompat.getLocales(config)
        val languages = (0 until locales.size()).mapNotNull { locales[it]?.language }
        return DeviceConfig(
            abis = Build.SUPPORTED_ABIS.toList(),
            densityDpi = Resources.getSystem().displayMetrics.densityDpi,
            languages = languages,
        )
    }
}
