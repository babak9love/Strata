package dev.cluvex.strata.core.root

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.topjohnwu.superuser.Shell
import com.topjohnwu.superuser.ipc.RootService
import com.topjohnwu.superuser.nio.FileSystemManager
import dev.cluvex.strata.core.fs.FileAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

enum class RootState { UNKNOWN, GRANTED, DENIED }

object RootManager {

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var remoteFs: FileSystemManager? = null

    private val bindMutex = Mutex()
    private val _state = MutableStateFlow(RootState.UNKNOWN)
    val state: StateFlow<RootState> = _state.asStateFlow()

    val isGranted: Boolean get() = _state.value == RootState.GRANTED

    fun init(context: Context) {
        appContext = context.applicationContext
        Shell.enableVerboseLogging = false
        Shell.setDefaultBuilder(
            Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(20),
        )
    }

    suspend fun request(): Boolean = withContext(Dispatchers.IO) {
        val granted = runCatching { Shell.getShell().isRoot }.getOrDefault(false)
        _state.value = if (granted) RootState.GRANTED else RootState.DENIED
        granted
    }

    suspend fun access(): RootFileAccess? = fileSystem()?.let { RootFileAccess(it) }

    private suspend fun fileSystem(): FileSystemManager? {
        remoteFs?.let { return it }
        if (!request()) return null
        return bindMutex.withLock { remoteFs ?: bind()?.also { remoteFs = it } }
    }

    private suspend fun bind(): FileSystemManager? {
        val ctx = appContext ?: return null
        return withContext(Dispatchers.Main) {
            withTimeoutOrNull(20_000) {
                suspendCancellableCoroutine { cont ->
                    val connection = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                            val fs = service?.let { runCatching { FileSystemManager.getRemote(it) }.getOrNull() }
                            if (cont.isActive) cont.resume(fs)
                        }

                        override fun onServiceDisconnected(name: ComponentName?) {
                            remoteFs = null
                        }
                    }
                    RootService.bind(Intent(ctx, StrataRootService::class.java), connection)
                }
            }
        }
    }

    suspend fun exec(vararg commands: String): Shell.Result = withContext(Dispatchers.IO) { Shell.cmd(*commands).exec() }

    suspend fun accessIf(needsRoot: Boolean, rootEnabled: Boolean): FileAccess? =
        if (needsRoot && rootEnabled) access() else null
}
