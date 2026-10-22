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
import androidx.compose.material.icons.rounded.Audiotrack
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
import dev.cluvex.strata.core.media.PlaybackPositions
import dev.cluvex.strata.core.media.ResumeKind
import dev.cluvex.strata.core.model.FileItem
import dev.cluvex.strata.core.util.FileKind
import dev.cluvex.strata.feature.props.MediaInfoKind
import dev.cluvex.strata.feature.props.MediaInfoSheet
import dev.cluvex.strata.ui.rememberSheetState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun rememberMediaController(): MediaController? {
    val context = LocalContext.current
    var controller by remember { mutableStateOf<MediaController?>(null) }
    DisposableEffect(Unit) {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
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
    resume: Boolean = true,
    onResumeChange: (Boolean) -> Unit = {},
    backToList: Boolean = true,
    onBackToListChange: (Boolean) -> Unit = {},
    onDismiss: () -> Unit,
) {
    val backToListNow by androidx.compose.runtime.rememberUpdatedState(backToList)
    val onDismissNow by androidx.compose.runtime.rememberUpdatedState(onDismiss)
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }
    val direction = LocalLayoutDirection.current
    val controller = rememberMediaController()
    var metadata by remember { mutableStateOf(MediaMetadata.EMPTY) }
    var artwork by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(controller, path) {
        val c = controller ?: return@LaunchedEffect
        if (c.currentMediaItem?.mediaId == path) return@LaunchedEffect
        val list = withContext(Dispatchers.IO) { MediaSiblings.of(File(path), FileKind.AUDIO, showHidden) }
        c.setMediaItems(list.files.map(::songItem), list.start, if (resume) PlaybackPositions.get(context, ResumeKind.AUDIO, path) else 0L)
        c.prepare()
        c.play()
    }
    DisposableEffect(controller) {
        val c = controller
        val listener = object : Player.Listener {
            override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                metadata = mediaMetadata
                artwork = mediaMetadata.artworkData?.let { bytes ->
                    runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
                }
            }

            // A track finished: either it moved on to the next one on its own, or it was the last.
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && backToListNow) {
                    c?.pause()
                    onDismissNow()
                }
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && backToListNow) onDismissNow()
            }
        }
        c?.addListener(listener)
        c?.let { listener.onMediaMetadataChanged(it.mediaMetadata) }
        onDispose { c?.removeListener(listener) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
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
                SpeedButton(c)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { c.stop(); c.clearMediaItems(); onDismiss() }) { Text(stringResource(R.string.common_stop)) }
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
                    }
                }
            }
        }
        }
    }
    info?.let { f -> MediaInfoSheet(item = FileItem.of(File(f)), kind = MediaInfoKind.AUDIO, onDismiss = { info = null }) }
}

private fun songItem(file: File): MediaItem = MediaItem.Builder()
    .setUri(android.net.Uri.fromFile(file))
    .setMediaId(file.path)
    .setMediaMetadata(MediaMetadata.Builder().setDisplayTitle(file.nameWithoutExtension).build())
    .build()
