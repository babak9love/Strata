package dev.cluvex.strata.feature.media

import dev.cluvex.strata.core.i18n.separator
import android.content.ComponentName
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Slider
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Headphones
import android.content.ContextWrapper
import android.content.Context
import android.app.Activity
import androidx.compose.runtime.collectAsState
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.compose.material3.buttons.NextButton
import androidx.media3.ui.compose.material3.buttons.PlayPauseButton
import androidx.media3.ui.compose.material3.buttons.PlaybackSpeedToggleButton
import androidx.media3.ui.compose.material3.buttons.PreviousButton
import androidx.media3.ui.compose.material3.buttons.RepeatButton
import androidx.media3.ui.compose.material3.buttons.ShuffleButton
import dev.cluvex.strata.R
import dev.cluvex.strata.core.media.MediaSiblings
import dev.cluvex.strata.core.model.SortOrder
import dev.cluvex.strata.core.media.PlaybackPositions
import dev.cluvex.strata.core.media.ResumeKind
import dev.cluvex.strata.core.model.FileItem
import dev.cluvex.strata.core.util.FileKind
import dev.cluvex.strata.feature.props.MediaInfoKind
import dev.cluvex.strata.feature.props.MediaInfoSheet
import dev.cluvex.strata.ui.rememberSheetState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun rememberMediaController(onDisconnected: () -> Unit = {}): MediaController? {
    val context = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    val disconnected by androidx.compose.runtime.rememberUpdatedState(onDisconnected)
    DisposableEffect(Unit) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token)
            .setListener(object : MediaController.Listener {
                // The service went away (e.g. the notification's X): let the UI close with it.
                override fun onDisconnected(controller: MediaController) = disconnected()
            })
            .buildAsync()
        future.addListener({ controller = runCatching { future.get() }.getOrNull() }, ContextCompat.getMainExecutor(context))
        onDispose {
            controller = null
            MediaController.releaseFuture(future)
        }
    }
    return controller
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun MusicPlayerSheet(
    path: String,
    showHidden: Boolean,
    sortOrder: SortOrder? = null,
    resume: Boolean = true,
    onResumeChange: (Boolean) -> Unit = {},
    backToList: Boolean = true,
    onBackToListChange: (Boolean) -> Unit = {},
    backgroundPlay: Boolean = true,
    onBackgroundPlayChange: (Boolean) -> Unit = {},
    playAlone: Boolean = true,
    onPlayAloneChange: (Boolean) -> Unit = {},
    saveSpeed: Boolean = false,
    onSaveSpeedChange: (enabled: Boolean, speed: Float) -> Unit = { _, _ -> },
    onVolumeChange: (Float) -> Unit = {},
    onPlayModeChange: (repeatMode: Int, shuffle: Boolean) -> Unit = { _, _ -> },
    onDismiss: () -> Unit,
) {
    val onPlayModeChangeNow by androidx.compose.runtime.rememberUpdatedState(onPlayModeChange)
    val backToListNow by androidx.compose.runtime.rememberUpdatedState(backToList)
    val onDismissNow by androidx.compose.runtime.rememberUpdatedState(onDismiss)
    // The headphones button turns background play on for this playback only (see BackgroundSession): the saved
    // setting is never touched, so once the playback is over the setting is exactly as it was (on or off).
    val backgroundSetting by androidx.compose.runtime.rememberUpdatedState(backgroundPlay)
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    var sleepDialog by remember { mutableStateOf(false) }
    val abRepeat = remember { AbRepeatState() }
    var abPanel by remember { mutableStateOf(false) }
    val sleepEndsAt by SleepTimer.endsAt.collectAsState()
    val direction = LocalLayoutDirection.current
    val controller = rememberMediaController(onDisconnected = { onDismissNow() })
    var metadata by remember { mutableStateOf(MediaMetadata.EMPTY) }
    var artwork by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(controller, path) {
        val c = controller ?: return@LaunchedEffect
        if (c.currentMediaItem?.mediaId == path) return@LaunchedEffect
        val list = withContext(Dispatchers.IO) { MediaSiblings.of(File(path), FileKind.AUDIO, showHidden, sortOrder) }
        val behavior = dev.cluvex.strata.core.data.SettingsRepository(context).settings.first().behavior
        if (behavior.saveSpeedAudio) c.setPlaybackSpeed(behavior.speedAudio)
        // Repeat and shuffle come back the way they were left.
        c.repeatMode = behavior.repeatAudio.coerceIn(0, 2)
        c.shuffleModeEnabled = behavior.shuffleAudio
        // The player's own volume (not the phone's), kept from last time.
        c.volume = behavior.audioVolume.coerceIn(0.05f, 1f)
        c.setMediaItems(list.files.map(::songItem), list.start, if (resume) PlaybackPositions.get(context, ResumeKind.AUDIO, path) else 0L)
        c.prepare()
        c.play()
    }
    // With Background Play off, the music stops when the app is left (home, another app, screen off).
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (!backgroundSetting && !BackgroundSession.active) controller?.pause()
    }
    // Closing the player (swipe down, tap outside, back) with Background Play off stops the music instead of
    // leaving it playing in the service; with it on, the music carries on in the background.
    val closeSheet = {
        if (!backgroundSetting && !BackgroundSession.active) {
            controller?.run {
                stop()
                clearMediaItems()
            }
        }
        onDismiss()
    }
    DisposableEffect(controller) {
        val c = controller
        var hadItems = false
        val listener = object : Player.Listener {
            // The playlist was emptied from outside (the notification's X): close the sheet too.
            override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                if (!timeline.isEmpty) hadItems = true
                else if (hadItems && reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) onDismissNow()
            }

            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                metadata = mediaMetadata
                artwork = mediaMetadata.artworkData?.let { bytes ->
                    runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
                }
            }

            // A track finished: either it moved on to the next one on its own, or it was the last.
            // With "repeat all" on, the playlist is meant to keep going, so "back to list" is not applied.
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && backToListNow &&
                    c?.repeatMode != Player.REPEAT_MODE_ALL
                ) {
                    c?.pause()
                    onDismissNow()
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && backToListNow && c?.repeatMode != Player.REPEAT_MODE_ALL) onDismissNow()
            }

            // Remember the repeat / shuffle buttons for next time.
            override fun onRepeatModeChanged(repeatMode: Int) {
                c?.let { onPlayModeChangeNow(it.repeatMode, it.shuffleModeEnabled) }
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                c?.let { onPlayModeChangeNow(it.repeatMode, it.shuffleModeEnabled) }
            }
        }
        c?.addListener(listener)
        c?.let { listener.onMediaMetadataChanged(it.mediaMetadata) }
        onDispose { c?.removeListener(listener) }
    }

    ModalBottomSheet(
        onDismissRequest = closeSheet,
        sheetState = rememberSheetState(),
        modifier = Modifier.testTag("music-player"),
    ) {
        Box(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.size(200.dp).clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                val art = artwork
                if (art != null) {
                    Image(art, contentDescription = stringResource(R.string.media_cover), contentScale = ContentScale.Crop, modifier = Modifier.size(200.dp))
                } else {
                    Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(72.dp))
                }
            }
            val c = controller
            if (c == null) {
                LoadingIndicator()
                return@Column
            }
            Text(
                metadata.title?.toString() ?: metadata.displayTitle?.toString() ?: File(path).nameWithoutExtension,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = listOfNotNull(metadata.artist, metadata.albumTitle).joinToString(separator())
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                SeekBar(c, Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ShuffleButton(c)
                    PreviousButton(c)
                    PlayPauseButton(c, Modifier.size(72.dp))
                    NextButton(c)
                    RepeatButton(c)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SpeedButton(c, save = SpeedSave(saveSpeed, onSaveSpeedChange))
                // A-B repeat: when it is on, a tap turns it off (clears the points); otherwise it opens the card to set them.
                IconButton(onClick = { if (abRepeat.armed) { abRepeat.clear(); abPanel = false } else abPanel = true }) {
                    Icon(
                        AbRepeatIcon,
                        contentDescription = stringResource(R.string.media_ab_repeat),
                        tint = if (abRepeat.armed) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                    )
                }
                // Headphones: switch Background Play on if it is off, then leave the app with the music carrying on.
                IconButton(onClick = {
                    BackgroundSession.start()
                    var a: Context? = context
                    while (a is ContextWrapper && a !is Activity) a = a.baseContext
                    (a as? Activity)?.moveTaskToBack(true)
                }) {
                    Icon(
                        Icons.Rounded.Headphones,
                        contentDescription = stringResource(R.string.media_background_play),
                        tint = if (backgroundSetting || BackgroundSession.active) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                    )
                }
                IconButton(onClick = { sleepDialog = true }) {
                    Icon(
                        Icons.Rounded.Alarm,
                        contentDescription = stringResource(R.string.media_sleep_timer),
                        tint = if (sleepEndsAt != null) MaterialTheme.colorScheme.primary else LocalContentColor.current,
                    )
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { BackgroundSession.end(); c.stop(); c.clearMediaItems(); onDismiss() }) { Text(stringResource(R.string.common_stop)) }
            }
            AbRepeatEffect(c, abRepeat)
            if (abPanel) {
                AbRepeatPanel(
                    c,
                    abRepeat,
                    onClose = { abPanel = false },
                    modifier = Modifier.fillMaxWidth(),
                    container = MaterialTheme.colorScheme.surfaceContainerHigh,
                    content = MaterialTheme.colorScheme.onSurface,
                    button = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
            }
        }
        // The player's own volume: a small vertical bar in the free space to the left of the cover.
        controller?.let { vc ->
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                VolumeBar(vc, onVolumeChange, Modifier.align(Alignment.TopStart).padding(start = 14.dp, top = 8.dp))
            }
        }
        // Menu pinned to the physical top-right corner of the sheet (regardless of language direction).
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Box(Modifier.align(Alignment.TopEnd).padding(end = 8.dp)) {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.common_more))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    CompositionLocalProvider(LocalLayoutDirection provides direction) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.model_sheet_audio_info)) },
                            leadingIcon = { Icon(Icons.Rounded.Audiotrack, contentDescription = null) },
                            onClick = {
                                menu = false
                                info = controller?.currentMediaItem?.mediaId ?: path
                            },
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
    }
    if (sleepDialog) SleepTimerDialog { sleepDialog = false }
    info?.let { f -> MediaInfoSheet(item = FileItem.of(File(f)), kind = MediaInfoKind.AUDIO, onDismiss = { info = null }) }
}

private fun songItem(file: File): MediaItem = MediaItem.Builder()
    .setUri(android.net.Uri.fromFile(file))
    .setMediaId(file.path)
    .setMediaMetadata(MediaMetadata.Builder().setDisplayTitle(file.nameWithoutExtension).build())
    .build()
