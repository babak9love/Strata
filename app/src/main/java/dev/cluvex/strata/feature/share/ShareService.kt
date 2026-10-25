package dev.cluvex.strata.feature.share

import dev.cluvex.strata.core.i18n.separator
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.cluvex.strata.MainActivity
import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.share.FtpShareConfig
import dev.cluvex.strata.core.share.LanFtpServer
import dev.cluvex.strata.core.share.LanHttpServer
import dev.cluvex.strata.core.share.SafeRoot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.Executors

data class RunningShare(val url: String, val folder: String, val readOnly: Boolean, val user: String?, val password: String, val guests: Boolean = false)

data class ShareStatus(val ftp: RunningShare? = null, val web: RunningShare? = null, val error: String? = null) {
    val any: Boolean get() = ftp != null || web != null
}

sealed interface ShareRequest {
    data class Ftp(val folder: String, val port: Int, val user: String, val password: String, val guests: Boolean, val readOnly: Boolean) : ShareRequest
    data class Web(val folder: String, val port: Int, val password: String, val readOnly: Boolean) : ShareRequest
}

object LanAddress {
    fun current(context: Context): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        val fromNetworks = cm.allNetworks.asSequence().filter { n ->
            cm.getNetworkCapabilities(n)?.let { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) } == true
        }.mapNotNull { n -> cm.getLinkProperties(n)?.linkAddresses?.map { it.address }?.filterIsInstance<Inet4Address>()?.firstOrNull() }.firstOrNull()
        if (fromNetworks != null) return fromNetworks.hostAddress
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && (it.name.startsWith("wlan") || it.name.startsWith("ap") || it.name.startsWith("swlan") || it.name.startsWith("eth")) }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
        }.getOrNull()
    }
}

class ShareService : Service() {

    private val worker = Executors.newSingleThreadExecutor()
    private var ftp: LanFtpServer? = null
    private var web: LanHttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        val request = pending
        pending = null
        when (intent?.action) {
            ACTION_START -> request?.let { r -> worker.execute { start(r) } }
            ACTION_STOP_FTP -> worker.execute { stopFtp(); afterChange() }
            ACTION_STOP_WEB -> worker.execute { stopWeb(); afterChange() }
            ACTION_STOP_ALL -> worker.execute { stopFtp(); stopWeb(); afterChange() }
        }
        return START_NOT_STICKY
    }

    private fun start(r: ShareRequest) {
        val ip = LanAddress.current(this)
        if (ip == null) {
            status.update { it.copy(error = tr(R.string.share_error_no_wifi)) }
            afterChange()
            return
        }
        try {
            when (r) {
                is ShareRequest.Ftp -> {
                    stopFtp()
                    val server = LanFtpServer(FtpShareConfig(File(r.folder), r.port, r.user, r.password, r.guests, r.readOnly, bindAddress = ip))
                    val port = server.start()
                    ftp = server
                    status.update { it.copy(error = null, ftp = RunningShare("ftp://$ip:$port", r.folder, r.readOnly, r.user, r.password, r.guests)) }
                }
                is ShareRequest.Web -> {
                    stopWeb()
                    val server = LanHttpServer(SafeRoot(File(r.folder)), r.password, LanHttpServer.page(), r.readOnly)
                    val port = server.start(ip, r.port)
                    web = server
                    status.update { it.copy(error = null, web = RunningShare("http://$ip:$port", r.folder, r.readOnly, null, r.password)) }
                }
            }
        } catch (e: Exception) {
            val why = e.message ?: e.javaClass.simpleName
            status.update { it.copy(error = if ("bind" in why.lowercase() || "in use" in why.lowercase()) tr(R.string.share_error_port_taken) else tr(R.string.share_error_start, why)) }
        }
        afterChange()
    }

    private fun stopFtp() {
        runCatching { ftp?.stop() }
        ftp = null
        status.update { it.copy(ftp = null) }
    }

    private fun stopWeb() {
        runCatching { web?.stop() }
        web = null
        status.update { it.copy(web = null) }
    }

    private fun afterChange() {
        if (status.value.any) {
            if (wakeLock?.isHeld != true) {
                wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "strata:share").apply { acquire() }
            }
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
        } else {
            wakeLock?.takeIf { it.isHeld }?.release()
            wakeLock = null
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        worker.execute { stopFtp(); stopWeb() }
        worker.shutdown()
        wakeLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, tr(R.string.share_title), NotificationManager.IMPORTANCE_LOW))
        }
        val s = status.value
        val lines = listOfNotNull(s.web?.let { tr(R.string.share_notif_browser, it.url) }, s.ftp?.let { tr(R.string.share_notif_ftp, it.url) })
        val starting = tr(R.string.share_starting)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ShareService::class.java).setAction(ACTION_STOP_ALL), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_share)
            .setContentTitle(tr(R.string.share_notif_title))
            .setContentText(lines.joinToString(separator()).ifEmpty { starting })
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n").ifEmpty { starting }))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, tr(R.string.common_stop), stop)
            .build()
    }

    companion object {
        private const val CHANNEL = "share"
        private const val NOTIFICATION_ID = 7301
        private const val ACTION_START = "dev.cluvex.strata.share.START"
        private const val ACTION_STOP_FTP = "dev.cluvex.strata.share.STOP_FTP"
        private const val ACTION_STOP_WEB = "dev.cluvex.strata.share.STOP_WEB"
        private const val ACTION_STOP_ALL = "dev.cluvex.strata.share.STOP_ALL"

        @Volatile
        private var pending: ShareRequest? = null

        private val status = MutableStateFlow(ShareStatus())
        val state: StateFlow<ShareStatus> = status.asStateFlow()

        fun start(context: Context, request: ShareRequest) {
            pending = request
            ContextCompat.startForegroundService(context, Intent(context, ShareService::class.java).setAction(ACTION_START))
        }

        fun stopFtp(context: Context) = send(context, ACTION_STOP_FTP)
        fun stopWeb(context: Context) = send(context, ACTION_STOP_WEB)
        fun stopAll(context: Context) = send(context, ACTION_STOP_ALL)

        fun clearError() = status.update { it.copy(error = null) }

        private fun send(context: Context, action: String) {
            if (!status.value.any) return
            ContextCompat.startForegroundService(context, Intent(context, ShareService::class.java).setAction(action))
        }
    }
}
