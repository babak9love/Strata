package dev.cluvex.strata.core.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.topjohnwu.superuser.nio.FileSystemManager
import dev.cluvex.strata.core.root.RootFileAccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

object ShizukuManager {

    enum class Status { NOT_RUNNING, NEEDS_PERMISSION, READY }

    private val _state = MutableStateFlow(Status.NOT_RUNNING)
    val state: StateFlow<Status> = _state.asStateFlow()
    val isReady: Boolean get() = _state.value == Status.READY

    @Volatile private var appContext: Context? = null
    @Volatile private var remoteFs: FileSystemManager? = null
    private val bindMutex = Mutex()
    private const val PERMISSION_CODE = 4211

    private val onBinderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val onBinderDead = Shizuku.OnBinderDeadListener {
        remoteFs = null
        _state.value = Status.NOT_RUNNING
    }

    fun init(context: Context) {
        appContext = context.applicationContext
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(onBinderReceived)
            Shizuku.addBinderDeadListener(onBinderDead)
        }
        refresh()
    }

    fun refresh() {
        val next = when {
            !pingSafe() -> Status.NOT_RUNNING
            permittedSafe() -> Status.READY
            else -> Status.NEEDS_PERMISSION
        }
        if (next != Status.READY) remoteFs = null
        _state.value = next
    }

    val isInstalled: Boolean
        get() {
            val pm = appContext?.packageManager ?: return false
            return listOf("moe.shizuku.privileged.api", "moe.shizuku.privileged.api.shizuku").any { pkg ->
                runCatching { pm.getPackageInfo(pkg, 0); true }.getOrDefault(false)
            }
        }

    private fun pingSafe() = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    private fun permittedSafe() =
        runCatching { !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }.getOrDefault(false)

    suspend fun ensurePermission(): Boolean {
        if (!pingSafe()) return false
        if (permittedSafe()) return true
        if (Shizuku.isPreV11() || Shizuku.shouldShowRequestPermissionRationale()) return false
        val granted = suspendCancellableCoroutine { cont ->
            val listener = object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    if (requestCode != PERMISSION_CODE) return
                    Shizuku.removeRequestPermissionResultListener(this)
                    if (cont.isActive) cont.resume(grantResult == PackageManager.PERMISSION_GRANTED)
                }
            }
            Shizuku.addRequestPermissionResultListener(listener)
            cont.invokeOnCancellation { runCatching { Shizuku.removeRequestPermissionResultListener(listener) } }
            runCatching { Shizuku.requestPermission(PERMISSION_CODE) }.onFailure {
                Shizuku.removeRequestPermissionResultListener(listener)
                if (cont.isActive) cont.resume(false)
            }
        }
        refresh()
        return granted
    }

    suspend fun access(): RootFileAccess? = fileSystem()?.let { RootFileAccess(it) }

    private suspend fun fileSystem(): FileSystemManager? {
        remoteFs?.let { return it }
        if (!ensurePermission()) return null
        return bindMutex.withLock { remoteFs ?: bind()?.also { remoteFs = it; _state.value = Status.READY } }
    }

    private suspend fun bind(): FileSystemManager? {
        val ctx = appContext ?: return null
        val args = Shizuku.UserServiceArgs(ComponentName(ctx.packageName, ShizukuFsService::class.java.name))
            .daemon(false)
            .processNameSuffix("fs")
            .debuggable(false)
            .version(1)
        return withTimeoutOrNull(20_000) {
            suspendCancellableCoroutine { cont ->
                val connection = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        val fs = binder?.takeIf { it.pingBinder() }?.let {
                            runCatching {
                                FileSystemManager.getRemote(IShizukuFileService.Stub.asInterface(it).fileSystemService)
                            }.getOrNull()
                        }
                        if (cont.isActive) cont.resume(fs)
                    }

                    override fun onServiceDisconnected(name: ComponentName?) {
                        remoteFs = null
                        _state.value = Status.NOT_RUNNING
                    }
                }
                runCatching { Shizuku.bindUserService(args, connection) }.onFailure {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }
}
