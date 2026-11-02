package dev.cluvex.strata.feature.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.cluvex.strata.R
import dev.cluvex.strata.core.shell.SavedCommand
import dev.cluvex.strata.core.shell.ShellBackend
import dev.cluvex.strata.ui.expressive.ExpressiveDialog
import dev.cluvex.strata.ui.expressive.ExpressiveEmptyState
import dev.cluvex.strata.ui.rememberTextClipboard

/** [command] null means "new command". */
private class EditTarget(val command: SavedCommand?)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShellCommandsScreen(onBack: () -> Unit, vm: ShellCommandsViewModel = viewModel()) {
    BackHandler(onBack = onBack)
    val ui by vm.state.collectAsState()
    var editing by remember { mutableStateOf<EditTarget?>(null) }

    Scaffold(
        modifier = Modifier.testTag("shell-commands-screen"),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.shell_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text(stringResource(R.string.shell_add)) },
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                onClick = { editing = EditTarget(null) },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                ui.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
                ui.commands.isEmpty() -> ExpressiveEmptyState(
                    title = stringResource(R.string.shell_empty),
                    body = stringResource(R.string.shell_empty_body),
                    icon = Icons.Rounded.Terminal,
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 96.dp),
                ) {
                    items(ui.commands, key = { it.id }) { c ->
                        val running = ui.runningId == c.id
                        ListItem(
                            modifier = Modifier.clickable(enabled = ui.runningId == null) { vm.run(c) },
                            leadingContent = {
                                if (running) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                else Icon(Icons.Rounded.PlayArrow, contentDescription = stringResource(R.string.shell_run))
                            },
                            headlineContent = { Text(c.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = {
                                Text(
                                    c.command,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        textDirection = TextDirection.Ltr,
                                    ),
                                )
                            },
                            trailingContent = {
                                IconButton(onClick = { editing = EditTarget(c) }) {
                                    Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.common_edit))
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    editing?.let { target ->
        EditCommandDialog(
            existing = target.command,
            onSave = { name, command -> vm.save(target.command?.id, name, command); editing = null },
            onDelete = target.command?.let { c -> { vm.delete(c.id); editing = null } },
            onDismiss = { editing = null },
        )
    }

    ui.run?.let { run -> RunResultDialog(run, onDismiss = vm::dismissRun) }
}

@Composable
private fun EditCommandDialog(
    existing: SavedCommand?,
    onSave: (name: String, command: String) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var command by remember { mutableStateOf(existing?.command.orEmpty()) }
    ExpressiveDialog(
        onDismissRequest = onDismiss,
        title = stringResource(if (existing == null) R.string.shell_add else R.string.shell_edit),
        icon = Icons.Rounded.Terminal,
        confirmButton = {
            TextButton(onClick = { onSave(name, command) }, enabled = command.isNotBlank()) {
                Text(stringResource(R.string.common_save))
            }
        },
        dismissButton = {
            Column {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text(stringResource(R.string.common_delete)) }
                }
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.common_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = command,
                onValueChange = { command = it },
                label = { Text(stringResource(R.string.shell_command)) },
                minLines = 3,
                maxLines = 8,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    textDirection = TextDirection.Ltr,
                ),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun RunResultDialog(run: ShellRun, onDismiss: () -> Unit) {
    val clipboard = rememberTextClipboard()
    val result = run.result
    val status = when {
        result.backend == null -> stringResource(R.string.shell_no_backend)
        else -> {
            val who = stringResource(if (result.backend == ShellBackend.SHIZUKU) R.string.shell_via_shizuku else R.string.shell_via_root)
            val code = if (result.timedOut) stringResource(R.string.shell_timed_out) else stringResource(R.string.shell_exit_code, result.exitCode)
            "$who · $code"
        }
    }
    val body = result.output.ifEmpty { if (result.backend == null) "" else stringResource(R.string.shell_no_output) }
    ExpressiveDialog(
        onDismissRequest = onDismiss,
        title = run.command.name,
        icon = Icons.Rounded.Terminal,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } },
        dismissButton = if (result.output.isNotEmpty()) {
            { TextButton(onClick = { clipboard.setText(result.output) }) { Text(stringResource(R.string.common_copy)) } }
        } else null,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(status, style = MaterialTheme.typography.labelLarge)
            if (body.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    SelectionContainer {
                        Text(
                            body,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                textDirection = TextDirection.Ltr,
                            ),
                        )
                    }
                }
            }
        }
    }
}
