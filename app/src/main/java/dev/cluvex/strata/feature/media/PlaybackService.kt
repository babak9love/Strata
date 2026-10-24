package dev.cluvex.strata.feature.media

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import dev.cluvex.strata.R
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
    @Volatile private var backgroundAudio = true
    @Volatile private var backgroundVideo = true
    @Volatile private var seekBackMs = 5_000L
    @Volatile private var seekForwardMs = 15_000L
    private var enhancer: android.media.audiofx.LoudnessEnhancer? = null
    @Volatile private var boostLevel = 1f
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())
    private val saveTick = object : Runnable {
        override fun run() {
            val player = session?.player ?: return
            if (player.isPlaying) resume?.saveNow()
            handler.postDelayed(this, SAVE_INTERVAL_MS)
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        instance = this
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
        scope.launch {
            SettingsRepository(applicationContext).settings.map { it.behavior.backgroundAudio to it.behavior.backgroundVideo }.distinctUntilChanged()
                .collect { (audio, video) ->
                    backgroundAudio = audio
                    backgroundVideo = video
                }
        }
        scope.launch {
            SettingsRepository(applicationContext).settings.map { it.behavior.seekBackSeconds to it.behavior.seekForwardSeconds }.distinctUntilChanged()
                .collect { (back, forward) ->
                    seekBackMs = back * 1000L
                    seekForwardMs = forward * 1000L
                }
        }
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) = rebuildEnhancer(audioSessionId)
        })
        rebuildEnhancer(player.audioSessionId)
        resume = ResumeListener(this, ResumeKind.AUDIO, player) { resumeEnabled }.also { player.addListener(it) }
        handler.postDelayed(saveTick, SAVE_INTERVAL_MS)
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, SkipLengthPlayer(player))
            .setSessionActivity(openApp)
            .setCallback(RestrictedCallback())
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) = updateNotificationButtons()
        })
        updateNotificationButtons()
    }

    /** Volume boost above 100% (1 to 2, up to +6 dB), done with a loudness enhancer on the player's audio session. */
    private fun setBoost(level: Float) {
        boostLevel = level.coerceIn(1f, 2f)
        applyBoost()
        session?.setSessionExtras(Bundle().apply { putFloat(EXTRA_BOOST, boostLevel) })
    }

    private fun applyBoost() {
        val e = enhancer ?: return
        val gainMb = if (boostLevel > 1f) (2000.0 * kotlin.math.log10(boostLevel.toDouble())).toInt() else 0
        runCatching {
            e.setTargetGain(gainMb)
            e.enabled = gainMb > 0
        }
    }

    private fun rebuildEnhancer(sessionId: Int) {
        runCatching { enhancer?.release() }
        enhancer = if (sessionId != C.AUDIO_SESSION_ID_UNSET) {
            runCatching { android.media.audiofx.LoudnessEnhancer(sessionId) }.getOrNull()
        } else {
            null
        }
        applyBoost()
    }

    /** Media keys, headset buttons and the notification skip by the lengths chosen in the video player. */
    private inner class SkipLengthPlayer(wrapped: Player) : ForwardingPlayer(wrapped) {
        override fun getSeekBackIncrement(): Long = seekBackMs
        override fun getSeekForwardIncrement(): Long = seekForwardMs
        override fun seekBack() {
            seekTo((currentPosition - seekBackMs).coerceAtLeast(0L))
        }
        override fun seekForward() {
            val end = duration
            val target = currentPosition + seekForwardMs
            seekTo(if (end != C.TIME_UNSET) target.coerceAtMost(end) else target)
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    @OptIn(UnstableApi::class)
    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app out of Recents keeps playing when Background Play is on for what is playing.
        val keepPlaying = if (isPlayingVideoAudio()) backgroundVideo else backgroundAudio
        if (!keepPlaying) pauseAllPlayersAndStopSelf()
    }

    /**
     * Extra notification buttons around previous / play / next: Speed on the left, and the X (exit) on the right.
     * Speed steps through the presets; the X is the only way to stop background playback once the app is gone.
     */
    @OptIn(UnstableApi::class)
    private fun updateNotificationButtons() {
        val current = session ?: return
        val speed = current.player.playbackParameters.speed
        val speedButton = CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setCustomIconResId(speedIcon(speed))
            .setDisplayName(getString(R.string.media_speed) + " " + (Math.round(speed * 100) / 100f) + "x")
            .setSessionCommand(SessionCommand(ACTION_SPEED, Bundle.EMPTY))
            .build()
        val exitButton = CommandButton.Builder(CommandButton.ICON_UNDEFINED)
            .setCustomIconResId(R.drawable.ic_notif_close)
            .setDisplayName(getString(R.string.media_exit))
            .setSessionCommand(SessionCommand(ACTION_EXIT, Bundle.EMPTY))
            .build()
        current.setMediaButtonPreferences(listOf(speedButton, exitButton))
    }

    /** Icon showing the speed as a number, picked by the nearest 0.05 step between 0.5x and 2x. */
    private fun speedIcon(speed: Float): Int {
        val step = Math.round((speed - 0.5f) / 0.05f).coerceIn(0, SPEED_ICONS.size - 1)
        return SPEED_ICONS[step]
    }

    private fun cycleSpeed() {
        val player = session?.player ?: return
        val current = player.playbackParameters.speed
        player.setPlaybackSpeed(SPEEDS.firstOrNull { it > current + 0.01f } ?: SPEEDS.first())
    }

    /** True while the media service is playing the audio of a video handed over from the video player. */
    private fun isPlayingVideoAudio(): Boolean =
        session?.player?.currentMediaItem?.mediaMetadata?.extras?.getBoolean(EXTRA_FROM_VIDEO) == true

    @OptIn(UnstableApi::class)
    private fun exitPlayback() {
        resume?.saveNow()
        // Stop and empty the player first: stopSelf() alone does nothing while the app still holds a controller
        // bound to the service, which left the notification on screen.
        session?.player?.run {
            pause()
            stop()
            clearMediaItems()
        }
        pauseAllPlayersAndStopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacks(saveTick)
        runCatching { enhancer?.release() }
        enhancer = null
        resume?.saveNow()
        resume = null
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        if (instance === this) instance = null
        super.onDestroy()
    }

    companion object {
        @Volatile private var instance: PlaybackService? = null

        /**
         * Stops whatever the service is playing in the background (music, or the audio of a video handed
         * over earlier) and removes its notification. Called when a new video is opened, so two things
         * never play at once. Does nothing if the service is not running. Must be called on the main thread.
         */
        fun stopPlayback() {
            instance?.exitPlayback()
        }

        private const val SAVE_INTERVAL_MS = 5_000L
        const val ACTION_EXIT = "dev.cluvex.strata.media.EXIT"
        private const val ACTION_SPEED = "dev.cluvex.strata.media.SPEED"
        private const val ACTION_BOOST = "dev.cluvex.strata.media.BOOST"

        /** Key of the boost level (1 to 2) in the command's arguments and in the session extras. */
        const val EXTRA_BOOST = "boost"

        /** Asks the service to boost the volume above 100% ([level] 1 = no boost, 2 = +6 dB). */
        @OptIn(UnstableApi::class)
        fun sendBoost(controller: MediaController, level: Float) {
            controller.sendCustomCommand(SessionCommand(ACTION_BOOST, Bundle.EMPTY), Bundle().apply { putFloat(EXTRA_BOOST, level) })
        }
        private val SPEED_ICONS = intArrayOf(
            R.drawable.ic_notif_speed_050,
            R.drawable.ic_notif_speed_055,
            R.drawable.ic_notif_speed_060,
            R.drawable.ic_notif_speed_065,
            R.drawable.ic_notif_speed_070,
            R.drawable.ic_notif_speed_075,
            R.drawable.ic_notif_speed_080,
            R.drawable.ic_notif_speed_085,
            R.drawable.ic_notif_speed_090,
            R.drawable.ic_notif_speed_095,
            R.drawable.ic_notif_speed_100,
            R.drawable.ic_notif_speed_105,
            R.drawable.ic_notif_speed_110,
            R.drawable.ic_notif_speed_115,
            R.drawable.ic_notif_speed_120,
            R.drawable.ic_notif_speed_125,
            R.drawable.ic_notif_speed_130,
            R.drawable.ic_notif_speed_135,
            R.drawable.ic_notif_speed_140,
            R.drawable.ic_notif_speed_145,
            R.drawable.ic_notif_speed_150,
            R.drawable.ic_notif_speed_155,
            R.drawable.ic_notif_speed_160,
            R.drawable.ic_notif_speed_165,
            R.drawable.ic_notif_speed_170,
            R.drawable.ic_notif_speed_175,
            R.drawable.ic_notif_speed_180,
            R.drawable.ic_notif_speed_185,
            R.drawable.ic_notif_speed_190,
            R.drawable.ic_notif_speed_195,
            R.drawable.ic_notif_speed_200,
        )
        private val SPEEDS = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)
        /** Marks media items that were handed over from the video player, so the video setting applies to them. */
        const val EXTRA_FROM_VIDEO = "strata_from_video"
    }

    private inner class RestrictedCallback : MediaSession.Callback {
        @OptIn(UnstableApi::class)
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            if (controller.packageName == packageName || controller.isTrusted) {
                val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                    .add(SessionCommand(ACTION_EXIT, Bundle.EMPTY))
                    .add(SessionCommand(ACTION_SPEED, Bundle.EMPTY))
                    .add(SessionCommand(ACTION_BOOST, Bundle.EMPTY))
                    .build()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
                    .setAvailableSessionCommands(sessionCommands)
                    .build()
            }
            val commands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .remove(Player.COMMAND_SET_MEDIA_ITEM)
                .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller).setAvailablePlayerCommands(commands).build()
        }

        /**
         * Swiping the notification away sends a "stop" media key. For video audio in the background that must not
         * end playback: ignore it and post the notification again, so only the notification's X (or Background Play
         * being switched off) stops it.
         */
        @OptIn(UnstableApi::class)
        override fun onMediaButtonEvent(session: MediaSession, controllerInfo: MediaSession.ControllerInfo, intent: Intent): Boolean {
            val key = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
            }
            if (key?.keyCode == KeyEvent.KEYCODE_MEDIA_STOP && isPlayingVideoAudio() && backgroundVideo) {
                handler.postDelayed({ this@PlaybackService.triggerNotificationUpdate() }, 300)
                return true
            }
            return false
        }

        @OptIn(UnstableApi::class)
        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == ACTION_EXIT) {
                exitPlayback()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == ACTION_BOOST) {
                setBoost(args.getFloat(EXTRA_BOOST, 1f))
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            if (customCommand.customAction == ACTION_SPEED) {
                cycleSpeed()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }
    }
}
