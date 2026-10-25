package dev.cluvex.strata.core.termux

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger

sealed interface TermuxRun {
    data class Done(val result: TermuxResult) : TermuxRun

    data object Opened : TermuxRun

    data object NotInstalled : TermuxRun

    data object NoPermission : TermuxRun

    data object TimedOut : TermuxRun
    data class Error(val message: String) : TermuxRun
}

object TermuxEvents {
    data class Event(val executionId: Int, val result: TermuxResult)

    private val _events = MutableSharedFlow<Event>(replay = 16, extraBufferCapacity = 16)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    fun publish(executionId: Int, result: TermuxResult) {
        _events.tryEmit(Event(executionId, result))
    }
}

class TermuxResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val id = intent.getIntExtra(EXTRA_EXECUTION_ID, -1)
        val bundle = intent.getBundleExtra(TermuxApi.RESULT_BUNDLE)
        val result = if (bundle == null) {
            TermuxResult("", "", null, 2, tr(R.string.termux_no_result), stdoutTruncated = false, stderrTruncated = false)
        } else {
            @Suppress("DEPRECATION")
            TermuxResult.parse { key -> bundle.get(key) }
        }
        TermuxEvents.publish(id, result)
    }

    companion object {
        const val ACTION = "dev.cluvex.strata.action.TERMUX_RESULT"
        const val EXTRA_EXECUTION_ID = "dev.cluvex.strata.extra.EXECUTION_ID"
    }
}

class AndroidTermux(context: Context) : TermuxPort {
    private val context = context.applicationContext

    override fun isInstalled(): Boolean = TermuxBridge.isInstalled(context)

    override fun hasPermission(): Boolean = TermuxBridge.hasPermission(context)

    override suspend fun run(command: TermuxCommand, timeoutMs: Long?): TermuxRun = TermuxBridge.run(context, command, timeoutMs)
}

object TermuxBridge {

    private val nextId = AtomicInteger(1000)

    fun isInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(TermuxApi.PACKAGE, 0)
        true
    }.getOrDefault(false)

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, TermuxApi.PERMISSION_RUN_COMMAND) == PackageManager.PERMISSION_GRANTED

    suspend fun run(context: Context, command: TermuxCommand, timeoutMs: Long? = null): TermuxRun {
        if (!isInstalled(context)) return TermuxRun.NotInstalled
        if (!hasPermission(context)) return TermuxRun.NoPermission
        val id = nextId.incrementAndGet()
        val intent = Intent(TermuxApi.ACTION_RUN_COMMAND).setClassName(TermuxApi.PACKAGE, TermuxApi.RUN_COMMAND_SERVICE)
        for ((key, value) in command.extras()) {
            when (value) {
                is String -> intent.putExtra(key, value)
                is Boolean -> intent.putExtra(key, value)
                is Array<*> -> intent.putExtra(key, value.map { it.toString() }.toTypedArray())
            }
        }
        if (command.background) {
            val reply = Intent(context, TermuxResultReceiver::class.java)
                .setAction(TermuxResultReceiver.ACTION)
                .putExtra(TermuxResultReceiver.EXTRA_EXECUTION_ID, id)
            val flags = PendingIntent.FLAG_ONE_SHOT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            intent.putExtra(TermuxApi.EXTRA_PENDING_INTENT, PendingIntent.getBroadcast(context, id, reply, flags))
        }
        try {
            start(context, intent)
        } catch (e: SecurityException) {
            return TermuxRun.NoPermission
        } catch (e: Exception) {
            return TermuxRun.Error(e.message ?: tr(R.string.termux_unreachable))
        }
        if (!command.background) {
            kotlinx.coroutines.delay(300)
            launch(context)
            return TermuxRun.Opened
        }
        val wait: suspend () -> TermuxResult = { TermuxEvents.events.first { it.executionId == id }.result }
        val result = if (timeoutMs != null) withTimeoutOrNull(timeoutMs) { wait() } else wait()
        return result?.let { TermuxRun.Done(it) } ?: TermuxRun.TimedOut
    }

    private fun start(context: Context, intent: Intent) {
        try {
            context.startService(intent)
        } catch (e: IllegalStateException) {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else throw e
        }
    }

    @Volatile
    var launchDisplayId: Int? = null

    fun launch(context: Context): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(TermuxApi.PACKAGE) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = launchDisplayId?.let { ActivityOptions.makeBasic().setLaunchDisplayId(it).toBundle() }
        context.startActivity(intent, options)
        return true
    }

    fun openInstallPage(context: Context, url: String) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
