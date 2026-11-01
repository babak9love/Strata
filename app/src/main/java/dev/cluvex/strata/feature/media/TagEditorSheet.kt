package dev.cluvex.strata.feature.media

import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.cluvex.strata.R
import dev.cluvex.strata.core.media.AudioTags
import dev.cluvex.strata.core.media.CoverChange
import dev.cluvex.strata.core.media.TagEditor
import dev.cluvex.strata.ui.isolated
import dev.cluvex.strata.ui.rememberSheetState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Tag editor for one audio file. [onSaved] runs after the file was rewritten, with the tags and the cover now in it (the player shows them at once),
 * then the sheet closes itself through [onDismiss].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagEditorSheet(path: String, onSaved: (tags: AudioTags, cover: ByteArray?) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var loaded by remember(path) { mutableStateOf(false) }
    var loadFailed by remember(path) { mutableStateOf(false) }
    var tags by remember(path) { mutableStateOf(AudioTags()) }
    var coverBytes by remember(path) { mutableStateOf<ByteArray?>(null) }
    var coverChange by remember(path) { mutableStateOf<CoverChange>(CoverChange.Keep) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(path) {
        withContext(Dispatchers.IO) {
            val t = TagEditor.read(path)
            if (t == null) loadFailed = true else {
                tags = t
                coverBytes = TagEditor.readCover(path)
            }
        }
        loaded = true
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val jpeg = withContext(Dispatchers.IO) { TagEditor.prepareCover(context, uri) }
            if (jpeg != null) {
                coverBytes = jpeg
                coverChange = CoverChange.Replace(jpeg)
            }
        }
    }
    val cover = remember(coverBytes) {
        coverBytes?.let { b -> runCatching { BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() }.getOrNull() }
    }

    ModalBottomSheet(onDismissRequest = { if (!saving) onDismiss() }, sheetState = rememberSheetState()) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.tag_editor_title), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(isolated(File(path).name), style = MaterialTheme.typography.titleMedium, maxLines = 2)

            when {
                !loaded -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                loadFailed -> Text(stringResource(R.string.tag_editor_read_failed), color = MaterialTheme.colorScheme.error)
                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier
                                    .size(112.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(MaterialTheme.colorScheme.secondaryContainer)
                                    .clickable(enabled = !saving) { picker.launch("image/*") },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (cover != null) Image(cover, stringResource(R.string.media_cover), contentScale = ContentScale.Crop, modifier = Modifier.size(112.dp))
                                else Text(
                                    stringResource(R.string.tag_editor_cover_hint),
                                    style = MaterialTheme.typography.labelMedium,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(8.dp),
                                )
                            }
                            if (coverBytes != null) {
                                TextButton(onClick = { coverBytes = null; coverChange = CoverChange.Remove }, enabled = !saving) {
                                    Text(stringResource(R.string.tag_editor_cover_remove))
                                }
                            }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Field(R.string.media_title, tags.title, !saving) { tags = tags.copy(title = it) }
                            Field(R.string.media_artist, tags.artist, !saving) { tags = tags.copy(artist = it) }
                        }
                    }
                    Field(R.string.media_album, tags.album, !saving) { tags = tags.copy(album = it) }
                    Field(R.string.media_album_artist, tags.albumArtist, !saving) { tags = tags.copy(albumArtist = it) }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Field(R.string.tag_editor_date, tags.year, !saving, Modifier.weight(1f), number = true) { tags = tags.copy(year = it) }
                        Field(R.string.media_track, tags.track, !saving, Modifier.weight(1f), hint = "3/12") { tags = tags.copy(track = it) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Field(R.string.media_disc, tags.disc, !saving, Modifier.weight(1f), hint = "1/2") { tags = tags.copy(disc = it) }
                        Field(R.string.media_genre, tags.genre, !saving, Modifier.weight(1f)) { tags = tags.copy(genre = it) }
                    }
                    Field(R.string.tag_editor_comment, tags.comment, !saving, singleLine = false) { tags = tags.copy(comment = it) }

                    error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.common_cancel)) }
                        Button(
                            enabled = !saving,
                            onClick = {
                                saving = true
                                error = null
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) { TagEditor.write(path, tags, coverChange) }
                                    saving = false
                                    if (result == null) {
                                        MediaScannerConnection.scanFile(context, arrayOf(path), null, null)
                                        onSaved(tags, coverBytes)
                                        onDismiss()
                                    } else {
                                        error = context.getString(R.string.tag_editor_save_failed, result)
                                    }
                                }
                            },
                        ) {
                            if (saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Text(stringResource(R.string.common_save))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Field(
    label: Int,
    value: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    number: Boolean = false,
    singleLine: Boolean = true,
    hint: String? = null,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        placeholder = hint?.let { { Text(it) } },
        singleLine = singleLine,
        enabled = enabled,
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        modifier = modifier.fillMaxWidth(),
    )
}
