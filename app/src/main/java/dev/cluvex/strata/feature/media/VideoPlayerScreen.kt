package dev.cluvex.strata.feature.media

import android.app.Activity
import android.app.Application
import android.media.AudioManager
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.ui.draw.rotate
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.rounded.BrightnessMedium
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import kotlin.math.roundToInt
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.media3.ui.compose.material3.buttons.NextButton
import androidx.media3.ui.compose.material3.buttons.PlayPauseButton
import androidx.media3.ui.compose.material3.buttons.PreviousButton
import androidx.media3.ui.compose.material3.buttons.SeekBackButton
import androidx.media3.ui.compose.material3.buttons.SeekForwardButton
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FastForward
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.widthIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import android.content.ComponentName
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.material3.Player as PlayerView
import androidx.media3.ui.compose.material3.PlayerDefaults
import androidx.media3.ui.compose.material3.buttons.PlaybackSpeedToggleButton
import androidx.media3.ui.compose.material3.buttons.RepeatButton
import androidx.media3.ui.compose.material3.buttons.ShuffleButton
import androidx.media3.ui.compose.state.rememberPlayPauseButtonState
import dev.cluvex.strata.R
import dev.cluvex.strata.core.media.MediaSiblings
import dev.cluvex.strata.core.media.PlaybackPositions
import dev.cluvex.strata.core.media.ResumeKind
import dev.cluvex.strata.core.media.ResumeListener
import dev.cluvex.strata.core.model.FileItem
import dev.cluvex.strata.feature.props.MediaInfoKind
import dev.cluvex.strata.feature.props.MediaInfoSheet
import dev.cluvex.strata.core.util.FileKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class VideoPlayerViewModel(app: Application) : AndroidViewModel(app) {

    var player by mutableStateOf<ExoPlayer?>(null)
        private set
    var title by mutableStateOf("")
        private set
    private var opened: String? = null
    private var resume: ResumeListener? = null
    @Volatile var resumeEnabled = true
    /**
     * The player's own volume level the last time it was audible; what un-mute goes back to.
     * 0 to 1 is the player's volume; there is no boost above 100%.
     */
    @Volatile var volumeLevel = 1f

    fun setLevel(level: Float) {
        val l = level.coerceIn(0f, MAX_LEVEL)
        player?.volume = minOf(l, 1f)
        if (l > 0f) volumeLevel = l
    }

    private var saveJob: kotlinx.coroutines.Job? = null

    private var playAlone = true

    /** "Play alone": take the audio focus (other players stop; this one pauses for other apps and calls) or share the sound. */
    fun setPlayAlone(on: Boolean) {
        playAlone = on
        player?.setAudioAttributes(VIDEO_AUDIO_ATTRIBUTES, on)
    }

    /** Called whenever the repeat or shuffle button changes, so the choice is kept for next time. */
    var onPlayModeChange: (repeatMode: Int, shuffle: Boolean) -> Unit = { _, _ -> }

    fun open(path: String, showHidden: Boolean) {
        if (opened == path) return
        opened = path
        // A song or an earlier video may still be playing in the background service: stop it first.
        // Opened from the notification: carry on from where the background audio got to.
        val start = startOverride?.takeIf { it.path == path }
        startOverride = null
        PlaybackService.stopPlayback()
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { MediaSiblings.of(File(path), FileKind.VIDEO, showHidden) }
            val skip = dev.cluvex.strata.core.data.SettingsRepository(getApplication()).settings.first().behavior
            playAlone = skip.playAloneVideo
            val p = ExoPlayer.Builder(getApplication())
                .setAudioAttributes(VIDEO_AUDIO_ATTRIBUTES, skip.playAloneVideo)
                .setSeekBackIncrementMs(skip.seekBackSeconds * 1000L)
                .setSeekForwardIncrementMs(skip.seekForwardSeconds * 1000L)
                .build()
            p.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    title = mediaItem?.mediaMetadata?.displayTitle?.toString().orEmpty()
                }
            })
            resume = ResumeListener(getApplication(), ResumeKind.VIDEO, p) { resumeEnabled }.also { p.addListener(it) }
            if (skip.saveSpeedVideo) p.setPlaybackSpeed(skip.speedVideo)
            // Repeat and shuffle come back the way they were left; the listener is added after, so this is not re-saved.
            p.repeatMode = skip.repeatVideo.coerceIn(0, 2)
            p.shuffleModeEnabled = skip.shuffleVideo
            p.addListener(object : Player.Listener {
                override fun onRepeatModeChanged(repeatMode: Int) = onPlayModeChange(p.repeatMode, p.shuffleModeEnabled)

                override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = onPlayModeChange(p.repeatMode, p.shuffleModeEnabled)
            })
            volumeLevel = minOf(skip.videoVolume.coerceIn(0.05f, MAX_LEVEL), SAFE_LEVEL)
            p.volume = minOf(volumeLevel, 1f)
            p.setMediaItems(list.files.map(::mediaItem), list.start, if (start != null) start.positionMs else if (resumeEnabled) PlaybackPositions.get(getApplication(), ResumeKind.VIDEO, path) else 0L)
            p.prepare()
            p.playWhenReady = start?.playing ?: true
            title = list.current.name
            player = p
            saveJob?.cancel()
            saveJob = viewModelScope.launch {
                while (true) {
                    delay(SAVE_INTERVAL_MS)
                    if (p.isPlaying) resume?.saveNow()
                }
            }
        }
    }

    fun savePosition() {
        resume?.saveNow()
    }

    // ---- Background play: the audio of the video keeps going in PlaybackService while the app is hidden.
    private var bgFuture: ListenableFuture<MediaController>? = null
    private var bgController: MediaController? = null
    private var handedOff = false

    /** App went to the background while playing: move playback (audio only) to the media service. */
    fun handOffToBackground(force: Boolean = false) {
        val p = player ?: return
        if (handedOff || (!force && !p.playWhenReady)) return
        if (force && p.volume == 0f) p.volume = volumeLevel.coerceIn(0.05f, 1f) // "audio only" was asked for: unmute
        val items = backgroundItems(p)
        val index = p.currentMediaItemIndex
        val position = p.currentPosition
        val repeat = p.repeatMode
        val shuffle = p.shuffleModeEnabled
        val mutedVolume = p.volume
        resume?.saveNow()
        p.pause()
        handedOff = true
        val app = getApplication<Application>()
        val future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
        bgFuture = future
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            if (!handedOff) {
                releaseBackground()
                return@addListener
            }
            bgController = c
            c.trackSelectionParameters = c.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
            c.volume = mutedVolume
            c.repeatMode = repeat
            c.shuffleModeEnabled = shuffle
            c.setMediaItems(items, index, position)
            c.prepare()
            c.play()
        }, ContextCompat.getMainExecutor(app))
    }

    private fun backgroundItems(p: ExoPlayer): List<MediaItem> = (0 until p.mediaItemCount).map { i ->
        val item = p.getMediaItemAt(i)
        item.buildUpon()
            .setMediaMetadata(
                item.mediaMetadata.buildUpon()
                    .setExtras(android.os.Bundle().apply { putBoolean(PlaybackService.EXTRA_FROM_VIDEO, true) })
                    .build(),
            )
            .build()
    }

    /**
     * The video screen is being closed (Back) while playing: leave the audio playing in the media service,
     * like the music player does when its sheet is dismissed. Nothing to take back afterwards.
     */
    fun handOffAndClose() {
        val p = player ?: return
        if (!p.playWhenReady) return
        if (p.volume == 0f) {
            // Muted: there is nothing to listen to in the background.
            resume?.saveNow()
            p.pause()
            return
        }
        val items = backgroundItems(p)
        val index = p.currentMediaItemIndex
        val position = p.currentPosition
        val repeat = p.repeatMode
        val shuffle = p.shuffleModeEnabled
        val closeVolume = p.volume
        resume?.saveNow()
        p.pause()
        val app = getApplication<Application>()
        val future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            c.volume = closeVolume
            c.trackSelectionParameters = c.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
            c.repeatMode = repeat
            c.shuffleModeEnabled = shuffle
            c.setMediaItems(items, index, position)
            c.prepare()
            c.play()
            // The service keeps playing without this controller.
            MediaController.releaseFuture(future)
        }, ContextCompat.getMainExecutor(app))
    }

    /** App is visible again: take playback back from the service, at wherever it got to. */
    fun reclaimFromBackground() {
        if (!handedOff) return
        handedOff = false
        val c = bgController
        val p = player
        if (c != null && c.isConnected && p != null && c.mediaItemCount > 0) {
            val index = c.currentMediaItemIndex
            val position = c.currentPosition
            val playing = c.playWhenReady
            // The notification may have changed repeat / shuffle meanwhile.
            p.repeatMode = c.repeatMode
            p.shuffleModeEnabled = c.shuffleModeEnabled
            c.pause()
            c.volume = 1f
            c.trackSelectionParameters = c.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false).build()
            c.clearMediaItems()
            p.seekTo(index, position)
            p.playWhenReady = playing
        }
        releaseBackground()
    }

    private fun releaseBackground() {
        bgFuture?.let { MediaController.releaseFuture(it) }
        bgFuture = null
        bgController = null
    }

    override fun onCleared() {
        releaseBackground()
        saveJob?.cancel()
        resume?.saveNow()
        player?.release()
        player = null
    }

    class StartAt(val path: String, val positionMs: Long, val playing: Boolean)

    companion object {
        private val VIDEO_AUDIO_ATTRIBUTES = androidx.media3.common.AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        /** Set just before the video screen is opened from the notification; consumed by [open]. */
        @Volatile var startOverride: StartAt? = null

        private const val SAVE_INTERVAL_MS = 5_000L
        const val MAX_LEVEL = 1f
        const val SAFE_LEVEL = 1f

        fun mediaItem(file: File): MediaItem = MediaItem.Builder()
            .setUri(Uri.fromFile(file))
            .setMediaId(file.path)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(file.nameWithoutExtension).setDisplayTitle(file.name).build())
            .build()
    }
}

@androidx.annotation.OptIn(ExperimentalApi::class, androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoPlayerScreen(
    path: String,
    showHidden: Boolean,
    onBack: () -> Unit,
    autoHide: Boolean = true,
    onAutoHideChange: (Boolean) -> Unit = {},
    resume: Boolean = true,
    onResumeChange: (Boolean) -> Unit = {},
    backToList: Boolean = true,
    onBackToListChange: (Boolean) -> Unit = {},
    backgroundPlay: Boolean = true,
    onBackgroundPlayChange: (Boolean) -> Unit = {},
    playAlone: Boolean = true,
    onPlayAloneChange: (Boolean) -> Unit = {},
    seekBackSeconds: Int = 5,
    onSeekBackChange: (Int) -> Unit = {},
    seekForwardSeconds: Int = 15,
    onSeekForwardChange: (Int) -> Unit = {},
    saveSpeed: Boolean = false,
    onSaveSpeedChange: (enabled: Boolean, speed: Float) -> Unit = { _, _ -> },
    onVolumeChange: (Float) -> Unit = {},
    onPlayModeChange: (repeatMode: Int, shuffle: Boolean) -> Unit = { _, _ -> },
    vm: VideoPlayerViewModel = viewModel(),
) {
    SideEffect {
        vm.resumeEnabled = resume
        vm.onPlayModeChange = onPlayModeChange
        vm.setPlayAlone(playAlone)
    }
    LaunchedEffect(path) { vm.open(path, showHidden) }
    val backgroundPlayNow by androidx.compose.runtime.rememberUpdatedState(backgroundPlay)
    val onVolumeChangeNow by androidx.compose.runtime.rememberUpdatedState(onVolumeChange)
    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.reclaimFromBackground() }
    DisposableEffect(Unit) { onDispose { vm.savePosition() } }
    var controls by remember { mutableStateOf(true) }
    val zoomState = remember { VideoZoomState() }
    var scaleMenu by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var seeking by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    var titleExpanded by remember { mutableStateOf(false) }
    var audioTracks by remember { mutableStateOf(false) }
    // Which skip button is being adjusted by a long press: false = rewind, true = forward, null = none.
    var seekDialog by remember { mutableStateOf<Boolean?>(null) }
    val rotate = rememberRotateAction()
    val view = LocalView.current
    val context = LocalContext.current
    val activity = remember(view) { view.context.findActivity() }
    // Set by the headphones button of the floating window: the next stop is not a "window closed".
    var audioOnlyFromPip by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (audioOnlyFromPip) {
            audioOnlyFromPip = false
            vm.handOffToBackground(force = true)
        // Stopping while in the floating window means the window was closed: stop the video too.
        } else if (activity?.isInPictureInPictureMode == true) vm.player?.pause()
        else if (backgroundPlayNow) vm.handOffToBackground() else vm.player?.pause()
    }
    var inPip by remember { mutableStateOf(activity?.isInPictureInPictureMode == true) }
    DisposableEffect(activity) {
        val host = activity as? androidx.activity.ComponentActivity
        val listener = androidx.core.util.Consumer<androidx.core.app.PictureInPictureModeChangedInfo> { inPip = it.isInPictureInPictureMode }
        host?.addOnPictureInPictureModeChangedListener(listener)
        onDispose { host?.removeOnPictureInPictureModeChangedListener(listener) }
    }
    var hud by remember { mutableStateOf(Hud(volume = true, fraction = 0f)) }
    var hudVisible by remember { mutableStateOf(false) }
    LaunchedEffect(hud) {
        if (hudVisible) {
            delay(800)
            hudVisible = false
        }
    }
    // Brightness set by the swipe gesture only lasts while the player is open.
    DisposableEffect(activity) {
        val original = activity?.window?.attributes?.screenBrightness
        onDispose {
            activity?.window?.let { w ->
                w.attributes = w.attributes.also { it.screenBrightness = original ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
            }
        }
    }
    val player = vm.player
    // Keeps the floating window's shape in step with the video.
    DisposableEffect(player) {
        if (player == null) {
            onDispose { }
        } else {
            VideoPip.update(player.videoSize)
            val pipListener = object : Player.Listener {
                override fun onVideoSizeChanged(size: VideoSize) = VideoPip.update(size)
            }
            player.addListener(pipListener)
            onDispose { player.removeListener(pipListener) }
        }
    }
    // Buttons inside the floating window (previous, play/pause, next, headphones): the system sends a
    // broadcast, the player reacts, and the buttons are refreshed whenever the state changes.
    DisposableEffect(player, activity) {
        if (player == null || activity == null) {
            onDispose { }
        } else {
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: android.content.Intent?) {
                    when (intent?.action) {
                        VideoPip.ACTION_TOGGLE -> if (player.playWhenReady) player.pause() else player.play()
                        VideoPip.ACTION_NEXT -> player.seekToNext()
                        VideoPip.ACTION_AUDIO_ONLY -> {
                            // Close the floating window; ON_STOP then moves the audio to the background service.
                            audioOnlyFromPip = true
                            activity.moveTaskToBack(true)
                        }
                    }
                }
            }
            val filter = android.content.IntentFilter().also { f -> VideoPip.ALL_ACTIONS.forEach(f::addAction) }
            androidx.core.content.ContextCompat.registerReceiver(
                activity,
                receiver,
                filter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            val stateListener = object : Player.Listener {
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = VideoPip.refresh(activity, player)
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = VideoPip.refresh(activity, player)
                override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) = VideoPip.refresh(activity, player)
            }
            player.addListener(stateListener)
            onDispose {
                player.removeListener(stateListener)
                runCatching { activity.unregisterReceiver(receiver) }
            }
        }
    }
    // In the floating window only the picture is shown, fitted to the window.
    var modeBeforePip by remember { mutableStateOf<VideoScaleMode?>(null) }
    LaunchedEffect(inPip) {
        if (inPip) {
            controls = false
            modeBeforePip = zoomState.mode
            zoomState.select(VideoScaleMode.FIT)
        } else {
            modeBeforePip?.let { zoomState.select(it) }
            modeBeforePip = null
        }
    }
    val exit = {
        if (backgroundPlayNow) vm.handOffAndClose()
        onBack()
    }
    BackHandler(onBack = exit)
    seekDialog?.let { forward ->
        SkipLengthDialog(
            title = stringResource(if (forward) R.string.media_seek_forward_title else R.string.media_seek_back_title),
            seconds = if (forward) seekForwardSeconds else seekBackSeconds,
            onDismiss = { seekDialog = null },
            onSave = { v ->
                if (forward) onSeekForwardChange(v) else onSeekBackChange(v)
                seekDialog = null
            },
        )
    }
    val backToListNow by androidx.compose.runtime.rememberUpdatedState(backToList)
    val onBackNow by androidx.compose.runtime.rememberUpdatedState(onBack)
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            // A video finished: either it moved on to the next one on its own, or it was the last.
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && backToListNow) {
                    player?.pause()
                    onBackNow()
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && backToListNow) onBackNow()
            }
        }
        player?.addListener(listener)
        onDispose { player?.removeListener(listener) }
    }

    if (player != null) AutoHideControls(player, controls && autoHide && !menu && !scaleMenu && !titleExpanded && !seeking) { controls = false }
    ImmersiveWhile(!controls)
    LightSystemBarIcons()

    // Press and hold on the left or right side plays at 2x until the finger lifts.
    var holdSpeed by remember { mutableStateOf(1f) }
    var holding by remember { mutableStateOf(false) }
    // A short tap anywhere (middle or sides) shows or hides the buttons and the seek bar.
    val onMiddleTap = androidx.compose.runtime.rememberUpdatedState<() -> Unit> { controls = !controls }
    val onSideTap = androidx.compose.runtime.rememberUpdatedState<() -> Unit> { controls = !controls }
    val onHoldStart = androidx.compose.runtime.rememberUpdatedState<() -> Unit> {
        player?.let {
            holdSpeed = it.playbackParameters.speed
            it.setPlaybackSpeed(2f)
            holding = true
        }
    }
    val onHoldEnd = androidx.compose.runtime.rememberUpdatedState<() -> Unit> {
        if (holding) {
            player?.setPlaybackSpeed(holdSpeed)
            holding = false
        }
    }

    val direction = LocalLayoutDirection.current
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("video-player")) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            ZoomablePlayer(
                player = player,
                state = zoomState,
                modifier = Modifier
                    .fillMaxSize()
                    .videoTapGestures(onMiddleTap, onSideTap, onHoldStart, onHoldEnd)
                    .pointerInput(Unit) {
                        // Vertical swipe: left half = brightness, right half = volume.
                        var volume = true
                        var fraction = 0f
                        detectVerticalDragGestures(
                            onDragStart = { offset ->
                                volume = offset.x >= size.width / 2f
                                // The right half sets the video's own volume, not the phone's.
                                fraction = if (volume) {
                                    if (vm.player?.volume == 0f) 0f else vm.volumeLevel
                                } else {
                                    currentBrightness(activity, context)
                                }
                                hud = Hud(volume, fraction)
                                hudVisible = true
                            },
                            onVerticalDrag = { change, dy ->
                                change.consume()
                                // Volume and brightness both go from 0 to 100%.
                                val top = if (volume) VideoPlayerViewModel.MAX_LEVEL else 1f
                                fraction = (fraction - dy / (size.height * 0.6f)).coerceIn(0f, top)
                                if (volume) {
                                    vm.setLevel(fraction)
                                } else {
                                    activity?.window?.let { w ->
                                        w.attributes = w.attributes.also { it.screenBrightness = fraction.coerceAtLeast(0.01f) }
                                    }
                                }
                                hud = Hud(volume, fraction)
                                hudVisible = true
                            },
                            onDragEnd = { if (volume) onVolumeChangeNow(vm.volumeLevel) },
                            onDragCancel = { if (volume) onVolumeChangeNow(vm.volumeLevel) },
                        )
                    },
                showControls = controls,
                topControls = { p, visible ->
                    CompositionLocalProvider(LocalLayoutDirection provides direction) {
                        PlayerDefaults.TopControls(p, visible, Modifier.fillMaxWidth()) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                                    .statusBarsPadding()
                                    .padding(horizontal = 4.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconButton(onClick = exit) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = Color.White) }
                                Row(
                                    Modifier
                                        .weight(1f)
                                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { titleExpanded = !titleExpanded },
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        vm.title,
                                        color = Color.White,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    Icon(
                                        Icons.Rounded.KeyboardArrowDown,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.rotate(if (titleExpanded) 180f else 0f),
                                    )
                                    if (titleExpanded) {
                                        // Full file name in a popup under the title, so the other top-bar buttons never move.
                                        val maxWidth = (LocalConfiguration.current.screenWidthDp - 24).dp
                                        Popup(
                                            popupPositionProvider = remember { TitlePopupPositionProvider() },
                                            onDismissRequest = { titleExpanded = false },
                                            properties = PopupProperties(focusable = true),
                                        ) {
                                            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                                                Surface(shape = RoundedCornerShape(12.dp), color = Color(0xF22B2629)) {
                                                    Text(
                                                        vm.title,
                                                        color = Color.White,
                                                        style = MaterialTheme.typography.bodyMedium,
                                                        modifier = Modifier.widthIn(max = maxWidth).padding(horizontal = 14.dp, vertical = 12.dp),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                CompositionLocalProvider(LocalContentColor provides Color.White) {
                                    SpeedButton(p, Color.White, save = SpeedSave(saveSpeed, onSaveSpeedChange))
                                }
                                if (activity != null && VideoPip.isSupported(activity)) {
                                    IconButton(onClick = { VideoPip.enter(activity, p) }) {
                                        Icon(Icons.Rounded.PictureInPictureAlt, contentDescription = stringResource(R.string.media_pip), tint = Color.White)
                                    }
                                }
                                IconButton(onClick = rotate) {
                                    Icon(Icons.Rounded.ScreenRotation, contentDescription = stringResource(R.string.media_rotate_screen), tint = Color.White)
                                }
                                Box {
                                    IconButton(onClick = { scaleMenu = true; controls = true }) {
                                        Icon(
                                            Icons.Rounded.AspectRatio,
                                            contentDescription = stringResource(R.string.media_screen_size),
                                            tint = Color.White,
                                        )
                                    }
                                    DropdownMenu(expanded = scaleMenu, onDismissRequest = { scaleMenu = false }) {
                                        listOf(
                                            VideoScaleMode.FIT to R.string.media_fit_screen,
                                            VideoScaleMode.FILL to R.string.media_fill_screen,
                                            VideoScaleMode.STRETCH to R.string.media_stretch,
                                            VideoScaleMode.RATIO_16_9 to R.string.media_ratio_16_9,
                                            VideoScaleMode.RATIO_4_3 to R.string.media_ratio_4_3,
                                            VideoScaleMode.RATIO_21_9 to R.string.media_ratio_21_9,
                                            VideoScaleMode.ORIGINAL to R.string.media_original_size,
                                        ).forEach { (mode, label) ->
                                            DropdownMenuItem(
                                                text = { Text(stringResource(label)) },
                                                trailingIcon = {
                                                    if (zoomState.mode == mode) Icon(Icons.Rounded.Check, contentDescription = null)
                                                },
                                                onClick = {
                                                    zoomState.select(mode)
                                                    scaleMenu = false
                                                },
                                            )
                                        }
                                    }
                                }
                                Box {
                                    IconButton(onClick = { menu = true }) {
                                        Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.common_more), tint = Color.White)
                                    }
                                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.model_sheet_video_info)) },
                                            leadingIcon = { Icon(Icons.Rounded.Movie, contentDescription = null) },
                                            onClick = {
                                                menu = false
                                                info = p?.currentMediaItem?.mediaId ?: path
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.media_audio_tracks)) },
                                            leadingIcon = { Icon(Icons.Rounded.Audiotrack, contentDescription = null) },
                                            onClick = {
                                                menu = false
                                                audioTracks = true
                                            },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.media_auto_hide_controls)) },
                                            trailingIcon = { Switch(checked = autoHide, onCheckedChange = null) },
                                            onClick = { onAutoHideChange(!autoHide) },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.media_resume_playback)) },
                                            trailingIcon = { Switch(checked = resume, onCheckedChange = null) },
                                            onClick = { onResumeChange(!resume) },
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text(stringResource(R.string.media_back_to_list))
                                                    Text(
                                                        stringResource(R.string.media_back_to_list_desc),
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            },
                                            trailingIcon = { Switch(checked = backToList, onCheckedChange = null) },
                                            onClick = { onBackToListChange(!backToList) },
                                        )
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.media_background_play)) },
                                            trailingIcon = { Switch(checked = backgroundPlay, onCheckedChange = null) },
                                            onClick = { onBackgroundPlayChange(!backgroundPlay) },
                                        )
                                        DropdownMenuItem(
                                            text = {
                                                Column {
                                                    Text(stringResource(R.string.media_play_alone))
                                                    Text(
                                                        stringResource(R.string.media_play_alone_desc),
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    )
                                                }
                                            },
                                            trailingIcon = { Switch(checked = playAlone, onCheckedChange = null) },
                                            onClick = { onPlayAloneChange(!playAlone) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                bottomControls = { p, visible ->
                    Column(Modifier.navigationBarsPadding()) {
                        AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
                            Box(Modifier.fillMaxWidth().padding(end = 22.dp, bottom = 8.dp), contentAlignment = Alignment.CenterEnd) {
                                MuteButton(p) { vm.volumeLevel }
                            }
                        }
                        AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
                            Row(
                                Modifier.fillMaxWidth().padding(bottom = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val hasNext = rememberHasNeighbors(p).second
                                CompositionLocalProvider(LocalContentColor provides Color.White) { ShuffleButton(p, Modifier.size(40.dp)) }
                                CrispButton(40.dp, { p?.seekToPrevious() }) { Icon(Icons.Rounded.SkipPrevious, null, Modifier.size(40.dp)) }
                                SkipButton(
                                    onClick = { p?.let { it.seekTo((it.currentPosition - seekBackSeconds * 1000L).coerceAtLeast(0L)) } },
                                    onLongClick = { seekDialog = false },
                                ) { SeekIcon(seekBackSeconds * 1000L, mirrored = false) }
                                val play = rememberPlayPauseButtonState(p)
                                CrispButton(80.dp, { play.onClick() }) {
                                    Icon(if (play.showPlay) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, null, Modifier.size(80.dp))
                                }
                                SkipButton(
                                    onClick = {
                                        p?.let {
                                            val target = it.currentPosition + seekForwardSeconds * 1000L
                                            val end = it.duration
                                            it.seekTo(if (end != androidx.media3.common.C.TIME_UNSET) target.coerceAtMost(end) else target)
                                        }
                                    },
                                    onLongClick = { seekDialog = true },
                                ) { SeekIcon(seekForwardSeconds * 1000L, mirrored = true) }
                                CrispButton(40.dp, { p?.seekToNext() }, enabled = hasNext) { Icon(Icons.Rounded.SkipNext, null, Modifier.size(40.dp)) }
                                CompositionLocalProvider(LocalContentColor provides Color.White) { RepeatButton(p, Modifier.size(40.dp)) }
                            }
                        }
                        AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
                            SeekBar(
                                p,
                                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 20.dp),
                                activeColor = MaterialTheme.colorScheme.primary,
                                inactiveColor = Color.White.copy(alpha = 0.3f),
                                textColor = Color.White,
                                onSeekingChange = { seeking = it },
                                compact = true,
                                livePreview = true,
                            )
                        }
                    }
                },
            )
        }
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Box(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                AnimatedVisibility(
                    visible = hudVisible,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(if (hud.volume) Alignment.CenterEnd else Alignment.CenterStart),
                ) {
                    GestureHud(hud)
                }
            }
        }
        AnimatedVisibility(
            visible = holding,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 72.dp),
        ) {
            // "2x" followed by a fast-forward icon, always in this order (the screen is laid out left to right).
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                Row(
                    Modifier.background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50)).padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("2x", color = Color.White, fontWeight = FontWeight.Bold)
                    Icon(Icons.Rounded.FastForward, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                }
            }
        }
        if (audioTracks && player != null) AudioTracksDialog(player) { audioTracks = false }
        info?.let { f -> MediaInfoSheet(item = FileItem.of(File(f)), kind = MediaInfoKind.VIDEO, onDismiss = { info = null }) }
    }
}

/** Places the popup just below the title row, kept inside the window horizontally. */
private class TitlePopupPositionProvider : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val margin = 12
        val x = anchorBounds.left.coerceAtMost(windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)
        return IntOffset(x, anchorBounds.bottom)
    }
}

private val HUD_LABEL_HEIGHT = 24.dp

private data class Hud(val volume: Boolean, val fraction: Float)

@Composable
private fun GestureHud(hud: Hud) {
    val icon = when {
        !hud.volume -> Icons.Rounded.BrightnessMedium
        hud.fraction <= 0f -> Icons.Rounded.VolumeOff
        else -> Icons.Rounded.VolumeUp
    }
    // The icon takes the colour of the bar at the current level.
    val iconTint = if (hud.volume) {
        volumeColorAt(hud.fraction)
    } else {
        androidx.compose.ui.graphics.lerp(Color(0xFFFFF1A8), Color(0xFFFFB300), hud.fraction.coerceIn(0f, 1f))
    }
    if (hud.volume) {
        // Volume: the speaker on top (so it never sits next to the mute button below), then the percentage and the
        // bar (light blue at low volume, deepening to a strong blue at 100%).
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(6.dp))
            Box(Modifier.height(HUD_LABEL_HEIGHT), contentAlignment = Alignment.Center) {
                Text("${(hud.fraction * 100).roundToInt()}%", color = iconTint, style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            androidx.compose.foundation.Canvas(Modifier.width(6.dp).height(140.dp)) {
                val corner = androidx.compose.ui.geometry.CornerRadius(size.width / 2f)
                drawRoundRect(Color.White.copy(alpha = 0.3f), cornerRadius = corner)
                val frac = (hud.fraction / VideoPlayerViewModel.MAX_LEVEL).coerceIn(0f, 1f)
                clipRect(top = size.height * (1f - frac)) {
                    drawRoundRect(volumeBrush(0f, size.height), cornerRadius = corner)
                }
            }
        }
    } else {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(6.dp))
            // Same layout as the volume indicator (a label row, then the bar) so both bars line up exactly.
            Box(Modifier.height(HUD_LABEL_HEIGHT), contentAlignment = Alignment.Center) {
                Text("${(hud.fraction.coerceIn(0f, 1f) * 100).roundToInt()}%", color = iconTint, style = MaterialTheme.typography.titleMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            // Pale yellow at the bottom, getting richer towards the top.
            androidx.compose.foundation.Canvas(Modifier.width(6.dp).height(140.dp)) {
                val corner = androidx.compose.ui.geometry.CornerRadius(size.width / 2f)
                drawRoundRect(Color.White.copy(alpha = 0.3f), cornerRadius = corner)
                val frac = hud.fraction.coerceIn(0f, 1f)
                clipRect(top = size.height * (1f - frac)) {
                    drawRoundRect(
                        androidx.compose.ui.graphics.Brush.verticalGradient(
                            colors = listOf(Color(0xFFFFB300), Color(0xFFFFF1A8)),
                            startY = 0f,
                            endY = size.height,
                        ),
                        cornerRadius = corner,
                    )
                }
            }
        }
    }
}

private fun currentBrightness(activity: Activity?, context: Context): Float {
    val window = activity?.window?.attributes?.screenBrightness ?: -1f
    if (window >= 0f) return window
    return runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f }
        .getOrDefault(0.5f)
        .coerceIn(0f, 1f)
}

/** Toggles the screen between landscape and portrait; the original orientation is restored when the player closes. */
@Composable
private fun rememberRotateAction(): () -> Unit {
    val view = LocalView.current
    val activity = remember(view) { view.context.findActivity() }
    DisposableEffect(activity) {
        val original = activity?.requestedOrientation
        onDispose { if (activity != null && original != null) activity.requestedOrientation = original }
    }
    return {
        activity?.let {
            val landscape = it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            it.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/** A 40dp skip button that also reacts to a long press (used to change the skip length). */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SkipButton(onClick: () -> Unit, onLongClick: () -> Unit, content: @Composable () -> Unit) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .combinedClickable(
                role = androidx.compose.ui.semantics.Role.Button,
                onClick = onClick,
                onLongClick = {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    onLongClick()
                },
            ),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Slider from 1 second to 1 minute for the length of one skip. */
@Composable
private fun SkipLengthDialog(title: String, seconds: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var value by remember(seconds) { androidx.compose.runtime.mutableFloatStateOf(seconds.toFloat()) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(
                    stringResource(R.string.media_seek_seconds, value.roundToInt()),
                    style = MaterialTheme.typography.headlineSmall,
                )
                androidx.compose.material3.Slider(
                    value = value,
                    onValueChange = { value = it },
                    valueRange = 1f..60f,
                    steps = 58,
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { onSave(value.roundToInt().coerceIn(1, 60)) }) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

// Real-size vector icons (no scaling), so they stay sharp.
@Composable
private fun CrispButton(slot: Dp, onClick: () -> Unit, enabled: Boolean = true, content: @Composable () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(slot)) { content() }
}

/** Round mute / unmute button for the video itself (the phone's volume is not touched). */
@Composable
private fun MuteButton(player: Player?, restoreVolume: () -> Float) {
    var muted by remember(player) { mutableStateOf(player?.volume == 0f) }
    DisposableEffect(player) {
        if (player == null) {
            onDispose { }
        } else {
            val listener = object : Player.Listener {
                override fun onVolumeChanged(volume: Float) {
                    muted = volume == 0f
                }
            }
            muted = player.volume == 0f
            player.addListener(listener)
            onDispose { player.removeListener(listener) }
        }
    }
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color(0xCC1B1B1B))
            .clickable(enabled = player != null) { player?.volume = if (muted) restoreVolume().coerceIn(0.05f, 1f) else 0f },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (muted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
            contentDescription = stringResource(if (muted) R.string.media_unmute else R.string.media_mute),
            tint = Color.White,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** Whether there is a previous / next video to go to; follows the playlist, repeat and shuffle changes. */
@Composable
private fun rememberHasNeighbors(player: Player?): Pair<Boolean, Boolean> {
    var state by remember(player) { mutableStateOf((player?.hasPreviousMediaItem() == true) to (player?.hasNextMediaItem() == true)) }
    DisposableEffect(player) {
        if (player == null) {
            onDispose { }
        } else {
            val listener = object : Player.Listener {
                override fun onEvents(p: Player, events: Player.Events) {
                    state = p.hasPreviousMediaItem() to p.hasNextMediaItem()
                }
            }
            state = player.hasPreviousMediaItem() to player.hasNextMediaItem()
            player.addListener(listener)
            onDispose { player.removeListener(listener) }
        }
    }
    return state
}

@Composable
private fun SeekIcon(ms: Long, mirrored: Boolean) {
    Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        Icon(
            Icons.Rounded.Replay,
            contentDescription = null,
            modifier = Modifier.size(40.dp).graphicsLayer { scaleX = if (mirrored) -1f else 1f },
        )
        // The Replay glyph's arrow sits at the top (on the open side of the circle), so nudge the number
        // down and away from the arrowhead, and keep it a bit smaller so it doesn't touch the ring.
        Text(
            (ms / 1000).toString(),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = LocalContentColor.current,
            modifier = Modifier.offset(x = if (mirrored) (-0.5).dp else 0.5.dp, y = 2.5.dp),
        )
    }
}

@Composable
private fun AutoHideControls(player: Player, visible: Boolean, onHide: () -> Unit) {
    val playing = !rememberPlayPauseButtonState(player).showPlay
    LaunchedEffect(visible, playing) {
        if (visible && playing) {
            delay(3500)
            onHide()
        }
    }
}

@Composable
private fun ImmersiveWhile(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(on) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (controller != null) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            if (on) controller.hide(WindowInsetsCompat.Type.systemBars()) else controller.show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}
