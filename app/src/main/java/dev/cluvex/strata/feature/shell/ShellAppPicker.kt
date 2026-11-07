package dev.cluvex.strata.feature.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.cluvex.strata.R
import dev.cluvex.strata.core.apk.InstalledApp
import dev.cluvex.strata.core.apk.InstalledApps
import dev.cluvex.strata.core.shell.ShellParams
import dev.cluvex.strata.ui.expressive.ExpressiveDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Asks which app goes into the {package} of a command; [onPick] gets a well-formed package name. */
@Composable
internal fun ShellAppPickerDialog(title: String, lastPackage: String?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    ExpressiveDialog(
        onDismissRequest = onDismiss,
        title = title,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
        text = { ShellAppPickerContent(lastPackage, onPick) },
    )
}

/** The same picker in a plain dialog, for the launcher shortcut's window, which has no app theme of its own. */
@Composable
internal fun ShellAppPickerPlainDialog(title: String, lastPackage: String?, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = { ShellAppPickerContent(lastPackage, onPick) },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun ShellAppPickerContent(lastPackage: String?, onPick: (String) -> Unit) {
    val context = LocalContext.current
    val apps by produceState<List<InstalledApp>?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching { InstalledApps(context.packageManager).list(includeSystem = true) }.getOrDefault(emptyList())
        }
    }
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    val q = query.trim()
    val shown = remember(apps, q, showSystem, lastPackage) {
        apps.orEmpty()
            .filter { a ->
                if (q.isEmpty()) showSystem || !a.system || a.packageName == lastPackage
                else a.label.contains(q, ignoreCase = true) || a.packageName.contains(q, ignoreCase = true)
            }
            .sortedByDescending { it.packageName == lastPackage }
    }
    // A package that is not in the list (hidden from this app, or not installed) can still be typed in.
    val typed = q.takeIf { ShellParams.isValidPackage(it) && shown.none { a -> a.packageName == it } }
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.shell_param_search)) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth(),
        )
        FilterChip(selected = showSystem, onClick = { showSystem = !showSystem }, label = { Text(stringResource(R.string.shell_param_system)) })
        Box(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
            if (apps == null) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            } else {
                LazyColumn {
                    if (typed != null) {
                        item(key = "typed") {
                            Text(
                                stringResource(R.string.shell_param_use, typed),
                                style = mono,
                                modifier = Modifier.fillMaxWidth().clickable { onPick(typed) }.padding(vertical = 12.dp, horizontal = 4.dp),
                            )
                        }
                    }
                    if (shown.isEmpty() && typed == null) {
                        item(key = "empty") {
                            Text(
                                stringResource(R.string.shell_param_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 12.dp, horizontal = 4.dp),
                            )
                        }
                    }
                    items(shown, key = { it.packageName }) { a ->
                        val last = a.packageName == lastPackage
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { onPick(a.packageName) }.padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Box(
                                Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    a.label.firstOrNull()?.uppercase() ?: "?",
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    style = MaterialTheme.typography.labelLarge,
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (last) a.label + " · " + stringResource(R.string.shell_param_last) else a.label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    a.packageName,
                                    style = mono,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
