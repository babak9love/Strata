package dev.cluvex.strata.feature.explorer.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.cluvex.strata.R
import dev.cluvex.strata.core.model.ProtectedFolders
import dev.cluvex.strata.ui.expressive.ArtTone
import dev.cluvex.strata.ui.expressive.ExpressiveEmptyState
import java.io.File

class ProtectedFolderActions(
    val onOpen: (path: String) -> Unit,
    val onRemove: (path: String) -> Unit,
    /** Null when the current folder can't be protected (remote or vault). */
    val onAddCurrent: (() -> Unit)?,
)

/** The list of folders protected from deletion: open one, protect the current folder, or remove protection. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProtectedFoldersSheet(
    folders: List<String>,
    actions: ProtectedFolderActions,
    currentPath: String,
    onDismiss: () -> Unit,
) {
    var removing by remember { mutableStateOf<String?>(null) }
    val currentProtected = ProtectedFolders.canonical(currentPath) in folders

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stringResource(R.string.settings_group_protected),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            val addCurrent = actions.onAddCurrent
            if (addCurrent != null && !currentProtected) {
                TextButton(onClick = addCurrent) {
                    Icon(Icons.Rounded.Lock, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text(stringResource(R.string.explorer_add_current_folder))
                }
            }
        }
        if (folders.isEmpty()) {
            ExpressiveEmptyState(
                stringResource(R.string.settings_protected_empty),
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                icon = Icons.Rounded.Lock,
                tone = ArtTone.TERTIARY,
                body = stringResource(R.string.settings_protected_footer),
                maxHero = 104.dp,
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(bottom = 16.dp)) {
                items(folders, key = { it }) { path ->
                    ListItem(
                        headlineContent = {
                            Text(File(path).name.ifEmpty { path }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            Text(path, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        },
                        leadingContent = { Icon(Icons.Rounded.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = {
                            IconButton(onClick = { removing = path }) {
                                Icon(Icons.Rounded.LockOpen, contentDescription = stringResource(R.string.model_unprotect_folder))
                            }
                        },
                        modifier = Modifier.clickable { actions.onOpen(path) },
                    )
                }
            }
        }
    }

    removing?.let { path ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.explorer_protected_remove_title)) },
            text = { Text(stringResource(R.string.explorer_protected_remove_text, File(path).name.ifEmpty { path })) },
            confirmButton = {
                TextButton(onClick = { actions.onRemove(path); removing = null }) {
                    Text(stringResource(R.string.explorer_protected_remove_confirm))
                }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}
