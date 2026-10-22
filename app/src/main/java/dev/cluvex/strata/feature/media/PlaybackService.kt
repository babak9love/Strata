package dev.cluvex.strata.feature.media

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import dev.cluvex.strata.MainActivity
import dev.cluvex.strata.core.data.SettingsRepository
import dev.cluvex.strata.core.media.ResumeKind
import dev.cluvex.strata.core.media.ResumeListener

class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private var resume: ResumeListener? = null
    @Volatile private var resumeEnabled = true
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private val saveTick = object : Runnable {
        override fun run() {
            val player = session?.player ?: return
            if (player.isPlaying) resume?.saveNow()
            handler.postDelayed(this, SAVE_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                 true,
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        scope.launch {
            SettingsRepository(applicationContext).settings.map { it.behavior.resumeAudio }.distinctUntilChanged()
                .collect { resumeEnabled = it }
        }
        resume = ResumeListener(this, ResumeKind.AUDIO, player) { resumeEnabled }.also { player.addListener(it) }
        handler.postDelayed(saveTick, SAVE_INTERVAL_MS)
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openApp)
            .setCallback(RestrictedCallback())
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    @OptIn(UnstableApi::class)
    override fun onTaskRemoved(rootIntent: Intent?) {
        pauseAllPlayersAndStopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(saveTick)
        resume?.saveNow()
        resume = null
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private companion object {
        const val SAVE_INTERVAL_MS = 5_000L
    }

    private inner class RestrictedCallback : MediaSession.Callback {
        @OptIn(UnstableApi::class)
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            if (controller.packageName == packageName || controller.isTrusted) {
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller).build()
            }
            val commands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .remove(Player.COMMAND_SET_MEDIA_ITEM)
                .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller).setAvailablePlayerCommands(commands).build()
        }
    }
}
