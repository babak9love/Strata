package dev.cluvex.strata.feature.media

import android.app.Activity
import android.app.Application
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AspectRatio
import androidx.compose.material.icons.rounded.Crop
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
            p.setMediaItems(list.files.map(::mediaItem), list.start, 0L)
            p.prepare()
            p.playWhenReady = true
            title = list.current.name
            player = p
        }
    }

    override fun onCleared() {
        player?.release()
        player = null
    }

    companion object {
        fun mediaItem(file: File): MediaItem = MediaItem.Builder()
            .setUri(Uri.fromFile(file))
            .setMediaId(file.path)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(file.nameWithoutExtension).setDisplayTitle(file.name).build())
            .build()
    }
}

@androidx.annotation.OptIn(ExperimentalApi::class, androidx.media3.common.util.UnstableApi::class)
@Composable
fun VideoPlayerScreen(path: String, showHidden: Boolean, onBack: () -> Unit, vm: VideoPlayerViewModel = viewModel()) {
    LaunchedEffect(path) { vm.open(path, showHidden) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.player?.pause() }
    var controls by remember { mutableStateOf(true) }
    var fill by remember { mutableStateOf(false) }
    val player = vm.player
    BackHandler(onBack = onBack)

    if (player != null) AutoHideControls(player, controls) { controls = false }
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
                                IconButton(onClick = { fill = !fill }) {
                                    Icon(
                                        if (fill) Icons.Rounded.AspectRatio else Icons.Rounded.Crop,
                                        contentDescription = stringResource(if (fill) R.string.media_fit_screen else R.string.media_fill_screen),
                                        tint = Color.White,
                                    )
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
                                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                PreviousButton(p, Modifier.size(48.dp))
                                SeekBackButton(p, Modifier.size(48.dp))
                                PlayPauseButton(p, Modifier.size(64.dp))
                                SeekForwardButton(p, Modifier.size(48.dp))
                                NextButton(p, Modifier.size(48.dp))
                            }
                        }
                        PlayerDefaults.BottomControls(p, visible)
                    }
                },
            )
        }
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
