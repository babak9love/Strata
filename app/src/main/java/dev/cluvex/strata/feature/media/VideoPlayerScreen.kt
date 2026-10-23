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
    private var saveJob: kotlinx.coroutines.Job? = null

    fun open(path: String, showHidden: Boolean) {
        if (opened == path) return
        opened = path
        // A song or an earlier video may still be playing in the background service: stop it first.
        PlaybackService.stopPlayback()
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { MediaSiblings.of(File(path), FileKind.VIDEO, showHidden) }
            val p = ExoPlayer.Builder(getApplication()).build()
            p.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    title = mediaItem?.mediaMetadata?.displayTitle?.toString().orEmpty()
                }
            })
            resume = ResumeListener(getApplication(), ResumeKind.VIDEO, p) { resumeEnabled }.also { p.addListener(it) }
            p.setMediaItems(list.files.map(::mediaItem), list.start, if (resumeEnabled) PlaybackPositions.get(getApplication(), ResumeKind.VIDEO, path) else 0L)
            p.prepare()
            p.playWhenReady = true
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
    fun handOffToBackground() {
        val p = player ?: return
        if (handedOff || !p.playWhenReady) return
        val items = backgroundItems(p)
        val index = p.currentMediaItemIndex
        val position = p.currentPosition
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
        val items = backgroundItems(p)
        val index = p.currentMediaItemIndex
        val position = p.currentPosition
        resume?.saveNow()
        p.pause()
        val app = getApplication<Application>()
        val future = MediaController.Builder(app, SessionToken(app, ComponentName(app, PlaybackService::class.java))).buildAsync()
        future.addListener({
            val c = runCatching { future.get() }.getOrNull() ?: return@addListener
            c.trackSelectionParameters = c.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
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
            c.pause()
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

    companion object {
        private const val SAVE_INTERVAL_MS = 5_000L

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
    vm: VideoPlayerViewModel = viewModel(),
) {
    SideEffect { vm.resumeEnabled = resume }
    LaunchedEffect(path) { vm.open(path, showHidden) }
    val backgroundPlayNow by androidx.compose.runtime.rememberUpdatedState(backgroundPlay)
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
    val rotate = rememberRotateAction()
    val view = LocalView.current
    val context = LocalContext.current
    val activity = remember(view) { view.context.findActivity() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        // Stopping while in the floating window means the window was closed: stop the video too.
        if (activity?.isInPictureInPictureMode == true) vm.player?.pause()
        else if (backgroundPlayNow) vm.handOffToBackground() else vm.player?.pause()
    }
    var inPip by remember { mutableStateOf(activity?.isInPictureInPictureMode == true) }
    DisposableEffect(activity) {
        val host = activity as? androidx.activity.ComponentActivity
        val listener = androidx.core.util.Consumer<androidx.core.app.PictureInPictureModeChangedInfo> { inPip = it.isInPictureInPictureMode }
        host?.addOnPictureInPictureModeChangedListener(listener)
        onDispose { host?.removeOnPictureInPictureModeChangedListener(listener) }
    }
    val audio = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
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
    val onMiddleTap = androidx.compose.runtime.rememberUpdatedState<() -> Unit> {
        player?.let { if (it.playWhenReady) it.pause() else it.play() }
    }
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
                                fraction = if (volume) {
                                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                    audio.getStreamVolume(AudioManager.STREAM_MUSIC) / max.toFloat()
                                } else {
                                    currentBrightness(activity, context)
                                }
                                hud = Hud(volume, fraction)
                                hudVisible = true
                            },
                            onVerticalDrag = { change, dy ->
                                change.consume()
                                fraction = (fraction - dy / (size.height * 0.6f)).coerceIn(0f, 1f)
                                if (volume) {
                                    val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                    runCatching { audio.setStreamVolume(AudioManager.STREAM_MUSIC, (fraction * max).roundToInt(), 0) }
                                } else {
                                    activity?.window?.let { w ->
                                        w.attributes = w.attributes.also { it.screenBrightness = fraction.coerceAtLeast(0.01f) }
                                    }
                                }
                                hud = Hud(volume, fraction)
                                hudVisible = true
                            },
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
                                    SpeedButton(p, Color.White)
                                }
                                if (activity != null && VideoPip.isSupported(activity)) {
                                    IconButton(onClick = { VideoPip.enter(activity) }) {
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
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.media_reset_zoom)) },
                                            enabled = zoomState.zoomed,
                                            onClick = {
                                                zoomState.resetZoom()
                                                scaleMenu = false
                                            },
                                        )
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
                                    }
                                }
                            }
                        }
                    }
                },
                bottomControls = { p, visible ->
                    Column(Modifier.navigationBarsPadding()) {
                        AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
                            Row(
                                Modifier.fillMaxWidth().padding(bottom = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CompositionLocalProvider(LocalContentColor provides Color.White) { ShuffleButton(p, Modifier.size(40.dp)) }
                                CrispButton(40.dp, { p?.seekToPrevious() }) { Icon(Icons.Rounded.SkipPrevious, null, Modifier.size(40.dp)) }
                                CrispButton(40.dp, { p?.seekBack() }) { SeekIcon(p?.seekBackIncrement ?: 0L, mirrored = false) }
                                val play = rememberPlayPauseButtonState(p)
                                CrispButton(80.dp, { play.onClick() }) {
                                    Icon(if (play.showPlay) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, null, Modifier.size(80.dp))
                                }
                                CrispButton(40.dp, { p?.seekForward() }) { SeekIcon(p?.seekForwardIncrement ?: 0L, mirrored = true) }
                                CrispButton(40.dp, { p?.seekToNext() }) { Icon(Icons.Rounded.SkipNext, null, Modifier.size(40.dp)) }
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

private data class Hud(val volume: Boolean, val fraction: Float)

@Composable
private fun GestureHud(hud: Hud) {
    val icon = when {
        !hud.volume -> Icons.Rounded.BrightnessMedium
        hud.fraction <= 0f -> Icons.Rounded.VolumeOff
        else -> Icons.Rounded.VolumeUp
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier.width(5.dp).height(140.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.3f)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(Modifier.fillMaxHeight(hud.fraction.coerceIn(0f, 1f)).width(5.dp).background(Color.White))
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

// Real-size vector icons (no scaling), so they stay sharp.
@Composable
private fun CrispButton(slot: Dp, onClick: () -> Unit, content: @Composable () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(slot)) { content() }
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
