package dev.cluvex.strata.feature.props

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.cluvex.strata.R
import dev.cluvex.strata.core.media.MediaInfoReader
import dev.cluvex.strata.core.media.MediaSection
import dev.cluvex.strata.core.model.FileItem
import dev.cluvex.strata.ui.asPath
import dev.cluvex.strata.ui.isolated
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Technical details and tags of an audio or video file ("Audio info" / "Video info" in the item menu). */
enum class MediaInfoKind { AUDIO, VIDEO }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaInfoSheet(item: FileItem, kind: MediaInfoKind, onDismiss: () -> Unit) {
    var sections by remember(item.path) { mutableStateOf<List<MediaSection>?>(null) }
    LaunchedEffect(item.path) {
        sections = withContext(Dispatchers.IO) { MediaInfoReader.read(item.path) }
            .filter { kind == MediaInfoKind.VIDEO || it.title != R.string.media_section_video }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
        ) {
            Text(
                stringResource(
                    when (kind) {
                        MediaInfoKind.VIDEO -> R.string.model_sheet_video_info
                        MediaInfoKind.AUDIO -> R.string.model_sheet_audio_info
                    },
                ),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                isolated(item.name),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
            )
            HorizontalDivider()
            val list = sections
            if (list == null) {
                Row(Modifier.padding(vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            } else {
                list.forEachIndexed { i, section ->
                    if (i > 0) HorizontalDivider(Modifier.padding(top = 8.dp))
                    Text(
                        stringResource(section.title),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                    section.rows.forEach { row ->
                        Column(Modifier.padding(vertical = 6.dp)) {
                            Text(stringResource(row.label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            SelectionContainer { Text(row.value, style = MaterialTheme.typography.bodyMedium.asPath()) }
                        }
                    }
                }
            }
        }
    }
}
