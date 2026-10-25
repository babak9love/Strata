package dev.cluvex.strata.core.apk.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import dev.cluvex.strata.R
import dev.cluvex.strata.core.fs.FsUtil
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.root.RootManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipFile

class ApkInstaller(private val context: Context) {
    suspend fun installWithRoot(parts: List<ApkSource>, packageName: String?, allowDowngrade: Boolean = false): InstallResult =
        withContext(Dispatchers.IO) {
            val dir = PmInstall.stagingDir()
            try {
                when (val session = writeRootSession(parts, dir, allowDowngrade)) {
                    is RootSession.Failed -> InstallStatus.fromPm(session.output, packageName)
                    is RootSession.Ready -> InstallStatus.fromPm(shell(PmInstall.commit(session.id)), packageName)
                }
            } finally {
                shell(PmInstall.unstage(dir))
            }
        }

    sealed interface RootSession {
        data class Ready(val id: Int) : RootSession
        data class Failed(val output: String) : RootSession
    }

    internal suspend fun writeRootSession(parts: List<ApkSource>, dir: String, allowDowngrade: Boolean): RootSession {
        val localStage = File(context.cacheDir, "install/root-${System.nanoTime()}")
        try {
            val staging = PmInstall.stage(parts.map { it.materialize(localStage) }, dir)
            val copied = shell(staging.command)
            if (!PmInstall.staged(copied)) return RootSession.Failed("Failure [staging failed: ${copied.trim()}]")
            val created = shell(PmInstall.create(staging.parts, allowDowngrade))
            val session = PmInstall.parseSessionId(created) ?: return RootSession.Failed(created)
            for (command in PmInstall.write(session, staging.parts)) {
                val written = shell(command)
                if (!written.contains("Success")) {
                    shell(PmInstall.abandon(session))
                    return RootSession.Failed(written)
                }
            }
            return RootSession.Ready(session)
        } finally {
            FsUtil.deleteRecursively(localStage)
        }
    }

    suspend fun installWithSystem(parts: List<ApkSource>, packageName: String?): InstallResult {
        val installer = context.packageManager.packageInstaller
        val sessionId = withContext(Dispatchers.IO) {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                packageName?.let { setAppPackageName(it) }
                setSize(parts.sumOf { it.size.coerceAtLeast(0) })
                if (Build.VERSION.SDK_INT >= 26) setInstallReason(PackageManager.INSTALL_REASON_USER)
                if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                if (Build.VERSION.SDK_INT >= 33) setPackageSource(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE)
            }
            val id = installer.createSession(params)
            try {
                installer.openSession(id).use { session ->
                    parts.forEachIndexed { i, part ->
                        part.open().use { input ->
                            session.openWrite("${i}_${part.safeName}", 0, part.size).use { out ->
                                input.copyTo(out, BUFFER)
                                session.fsync(out)
                            }
                        }
                    }
                    session.commit(statusReceiver(id).intentSender)
                }
            } catch (e: Exception) {
                runCatching { installer.abandonSession(id) }
                throw e
            }
            id
        }
        return InstallEvents.events.first { it.sessionId == sessionId }.result
    }

    private fun statusReceiver(sessionId: Int): PendingIntent {
        val intent = Intent(context, InstallResultReceiver::class.java).setAction(InstallResultReceiver.ACTION)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(context, sessionId, intent, flags)
    }

    private suspend fun shell(command: String): String {
        val result = RootManager.exec("$command 2>&1")
        return result.out.joinToString("\n")
    }

    private companion object {
        const val BUFFER = 1 shl 16
    }
}

sealed interface ApkSource {
    val name: String
    val size: Long
    fun open(): InputStream

    fun materialize(dir: File): ApkPart

    val safeName: String get() = name.replace(Regex("[^A-Za-z0-9._-]"), "_")

    data class FileApk(val file: File) : ApkSource {
        override val name: String get() = file.name
        override val size: Long get() = file.length()
        override fun open(): InputStream = file.inputStream()
        override fun materialize(dir: File) = ApkPart(name, file.absolutePath, size)
    }

    data class BundleEntry(val bundle: File, val entry: String, override val size: Long) : ApkSource {
        override val name: String get() = entry.substringAfterLast('/')

        override fun open(): InputStream {
            val zip = ZipFile(bundle)
            val e = zip.getEntry(entry) ?: run { zip.close(); throw IOException(tr(R.string.apk_error_entry_missing, entry, bundle.name)) }
            val input = zip.getInputStream(e)
            return object : java.io.FilterInputStream(input) {
                override fun close() {
                    try { super.close() } finally { zip.close() }
                }
            }
        }

        override fun materialize(dir: File): ApkPart {
            dir.mkdirs()
            val out = File(dir, safeName)
            open().use { input -> out.outputStream().use { input.copyTo(it) } }
            return ApkPart(name, out.absolutePath, out.length())
        }
    }
}
