package dev.cluvex.strata.feature.media

import android.app.Activity
import android.app.Application
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
import androidx.compose.material.icons.rounded.Crop
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.material3.Player as PlayerView
import androidx.media3.ui.compose.material3.PlayerDefaults
import androidx.media3.ui.compose.material3.buttons.PlaybackSpeedToggleButton
import androidx.media3.ui.compose.material3.buttons.RepeatButton
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

    override fun onCleared() {
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
    vm: VideoPlayerViewModel = viewModel(),
) {
    SideEffect { vm.resumeEnabled = resume }
    LaunchedEffect(path) { vm.open(path, showHidden) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.player?.pause() }
    DisposableEffect(Unit) { onDispose { vm.savePosition() } }
    var controls by remember { mutableStateOf(true) }
    var fill by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    val rotate = rememberRotateAction()
    val player = vm.player
    BackHandler(onBack = onBack)

    if (player != null) AutoHideControls(player, controls && autoHide && !menu) { controls = false }
    ImmersiveWhile(!controls)
    LightSystemBarIcons()

    val direction = LocalLayoutDirection.current
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("video-player")) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            PlayerView(
                player = player,
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { controls = !controls },
                contentScale = if (fill) ContentScale.Crop else ContentScale.Fit,
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
                                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back), tint = Color.White) }
                                Text(
                                    vm.title,
                                    color = Color.White,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                CompositionLocalProvider(LocalContentColor provides Color.White) {
                                    PlaybackSpeedToggleButton(p, colors = ButtonDefaults.textButtonColors(contentColor = Color.White))
                                    RepeatButton(p)
                                }
                                IconButton(onClick = rotate) {
                                    Icon(Icons.Rounded.ScreenRotation, contentDescription = stringResource(R.string.media_rotate_screen), tint = Color.White)
                                }
                                IconButton(onClick = { fill = !fill }) {
                                    Icon(
                                        if (fill) Icons.Rounded.AspectRatio else Icons.Rounded.Crop,
                                        contentDescription = stringResource(if (fill) R.string.media_fit_screen else R.string.media_fill_screen),
                                        tint = Color.White,
                                    )
                                }
                                Box {
                                    IconButton(onClick = { menu = true }) {
                                        Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.common_more), tint = Color.White)
                                    }
                                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.model_sheet_video_info)) },
                                            leadingIcon = { Icon(Icons.Rounded.Info, contentDescription = null) },
                                            onClick = {
                                                menu = false
                                                info = p?.currentMediaItem?.mediaId ?: path
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
                                    }
                                }
                            }
                        }
                    }
                },
                centerControls = { _, _ -> },
                bottomControls = { p, visible ->
                    Column(Modifier.navigationBarsPadding()) {
                        AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
                            Row(
                                Modifier.fillMaxWidth().padding(bottom = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterHorizontally),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CrispButton(40.dp, { p?.seekToPrevious() }) { Icon(Icons.Rounded.SkipPrevious, null, Modifier.size(40.dp)) }
                                CrispButton(40.dp, { p?.seekBack() }) { SeekIcon(p?.seekBackIncrement ?: 0L, mirrored = false) }
                                val play = rememberPlayPauseButtonState(p)
                                CrispButton(80.dp, { play.onClick() }) {
                                    Icon(if (play.showPlay) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, null, Modifier.size(80.dp))
                                }
                                CrispButton(40.dp, { p?.seekForward() }) { SeekIcon(p?.seekForwardIncrement ?: 0L, mirrored = true) }
                                CrispButton(40.dp, { p?.seekToNext() }) { Icon(Icons.Rounded.SkipNext, null, Modifier.size(40.dp)) }
                            }
                        }
                        PlayerDefaults.BottomControls(p, visible)
                    }
                },
            )
        }
        info?.let { f -> MediaInfoSheet(item = FileItem.of(File(f)), kind = MediaInfoKind.VIDEO, onDismiss = { info = null }) }
    }
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
