package dev.cluvex.strata.core.apk.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.IntentCompat
import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object InstallEvents {
    data class Event(val sessionId: Int, val result: InstallResult)

    private val _events = MutableSharedFlow<Event>(replay = 16, extraBufferCapacity = 16)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    fun publish(sessionId: Int, result: InstallResult) {
        _events.tryEmit(Event(sessionId, result))
    }
}

class InstallResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
            if (confirm == null) {
                InstallEvents.publish(sessionId, InstallResult.Failure(InstallFailure.BLOCKED, tr(R.string.apk_no_confirmation_screen)))
                return
            }
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(confirm) }.onFailure {
                InstallEvents.publish(sessionId, InstallResult.Failure(InstallFailure.BLOCKED, it.message.orEmpty()))
            }
            return
        }
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
        InstallEvents.publish(sessionId, InstallStatus.fromSession(status, message, packageName))
    }

    companion object {
        const val ACTION = "dev.cluvex.strata.action.INSTALL_STATUS"
    }
}
