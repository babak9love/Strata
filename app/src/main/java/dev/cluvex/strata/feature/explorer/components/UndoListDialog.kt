package dev.cluvex.strata.feature.explorer.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import dev.cluvex.strata.ui.expressive.AlertDialog
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.cluvex.strata.R
import dev.cluvex.strata.core.undo.UndoBlocker
import dev.cluvex.strata.core.undo.UndoEntry

/** The pending undos, newest first; picking one undoes exactly that one. */
@Composable
fun UndoListDialog(
    entries: List<UndoEntry>,
    blockers: suspend () -> Map<Long, UndoBlocker?>,
    onPick: (UndoEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    var blocked by remember { mutableStateOf<Map<Long, UndoBlocker?>>(emptyMap()) }
    LaunchedEffect(entries) { blocked = blockers() }
    val now = System.currentTimeMillis()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.explorer_undo_list_title)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(entries, key = { it.id }) { e ->
                    val blocker = blocked[e.id]
                    val seconds = ((now - e.createdAt) / 1000).coerceAtLeast(0)
                    val age = if (seconds < 60) stringResource(R.string.explorer_undo_just_now) else stringResource(R.string.explorer_undo_min_ago, (seconds / 60).toInt())
                    val reason = when (blocker) {
                        UndoBlocker.GONE -> stringResource(R.string.explorer_undo_gone)
                        UndoBlocker.OCCUPIED -> stringResource(R.string.explorer_undo_occupied)
                        null -> null
                    }
                    ListItem(
                        headlineContent = { Text(e.title, maxLines = 2) },
                        supportingContent = {
                            Text(
                                reason ?: age,
                                color = if (reason != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        modifier = Modifier
                            .alpha(if (blocker != null) 0.6f else 1f)
                            .clickable { onPick(e) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
