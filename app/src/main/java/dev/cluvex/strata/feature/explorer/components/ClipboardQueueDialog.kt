package dev.cluvex.strata.feature.explorer.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.cluvex.strata.R
import dev.cluvex.strata.feature.explorer.ClipboardState

/**
 * The small panel behind a long press on Paste: every queued copy/cut, newest first (the one a short press runs
 * next). Tap an entry to see its files, ▶ runs it in the open folder, ✕ cancels it.
 */
@Composable
fun ClipboardQueueDialog(
    clips: List<ClipboardState>,
    onRun: (ClipboardState) -> Unit,
    onRemove: (ClipboardState) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(clips.isEmpty()) { if (clips.isEmpty()) onDismiss() }
    val ordered = clips.asReversed()
    var expanded by remember { mutableStateOf(setOf<Long>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.explorer_clipboard_queue)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.explorer_clip_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(ordered, key = { it.id }) { clip ->
                        val index = ordered.indexOf(clip)
                        val open = clip.id in expanded
                        Column(Modifier.fillMaxWidth()) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { expanded = if (open) expanded - clip.id else expanded + clip.id }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    if (clip.move) Icons.Rounded.ContentCut else Icons.Rounded.ContentCopy,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(22.dp),
                                )
                                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                    val op = stringResource(
                                        when {
                                            clip.shareMode != null -> R.string.explorer_clip_op_shared
                                            clip.move -> R.string.explorer_clip_op_cut
                                            else -> R.string.explorer_clip_op_copy
                                        },
                                    )
                                    Text(
                                        "$op · " + pluralStringResource(R.plurals.explorer_clip_items, clip.items.size, clip.items.size),
                                        style = MaterialTheme.typography.titleSmall,
                                    )
                                    Text(
                                        "#${index + 1} · " + stringResource(if (index == 0) R.string.explorer_clip_next else R.string.explorer_clip_waiting),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (index == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        clip.sourceDir,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Icon(
                                    if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                IconButton(onClick = { onRun(clip) }) {
                                    Icon(
                                        Icons.Rounded.PlayArrow,
                                        contentDescription = stringResource(if (clip.move) R.string.explorer_move_here else R.string.explorer_paste_here),
                                    )
                                }
                                IconButton(onClick = { onRemove(clip) }) {
                                    Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.explorer_clip_remove))
                                }
                            }
                            if (open) {
                                Column(Modifier.padding(start = 32.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    clip.items.take(MAX_NAMES).forEach {
                                        Row(verticalAlignment = Alignment.Top) {
                                            Text(
                                                if (it.isDirectory) "📁 " else "• ",
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                            FileNameText(
                                                it.name,
                                                maxLines = 2,
                                                style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.weight(1f),
                                            )
                                        }
                                    }
                                    if (clip.items.size > MAX_NAMES) Text("…", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } },
        dismissButton = { TextButton(onClick = onClear) { Text(stringResource(R.string.explorer_clear_clipboard)) } },
    )
}

private const val MAX_NAMES = 100
