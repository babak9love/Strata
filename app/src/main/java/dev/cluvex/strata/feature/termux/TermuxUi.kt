package dev.cluvex.strata.feature.termux

import dev.cluvex.strata.core.i18n.separator
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.cluvex.strata.R
import dev.cluvex.strata.core.data.SettingsRepository
import dev.cluvex.strata.core.termux.Language
import dev.cluvex.strata.core.termux.Project
import dev.cluvex.strata.core.termux.RunCommands
import dev.cluvex.strata.core.termux.RunScope
import dev.cluvex.strata.core.termux.RunTarget
import dev.cluvex.strata.core.termux.TermuxApi
import dev.cluvex.strata.core.termux.TermuxBridge
import dev.cluvex.strata.core.termux.TermuxIssue
import dev.cluvex.strata.core.termux.TermuxLanguages
import dev.cluvex.strata.core.termux.TermuxSession
import dev.cluvex.strata.core.termux.TermuxUiState
import dev.cluvex.strata.ui.LocalSettings
import dev.cluvex.strata.ui.rememberSheetState
import dev.cluvex.strata.ui.rememberTextClipboard
import java.io.File
import kotlinx.coroutines.launch

@Composable
fun TermuxSetupStep(state: TermuxUiState, session: TermuxSession) {
    val context = LocalContext.current
    val clipboard = rememberTextClipboard()
    var settingsNeeded by remember { mutableStateOf(false) }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val activity = context.findActivity()
        settingsNeeded = !granted && activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, TermuxApi.PERMISSION_RUN_COMMAND)
        session.refresh()
    }
    when {
        !state.termuxInstalled -> StepCard(
            title = stringResource(R.string.termux_not_installed_title),
            body = stringResource(R.string.termux_not_installed_body),
        ) {
            Button(onClick = { TermuxBridge.openInstallPage(context, TermuxApi.FDROID_URL) }) { Text(stringResource(R.string.termux_get_fdroid)) }
            OutlinedButton(onClick = { TermuxBridge.openInstallPage(context, TermuxApi.GITHUB_URL) }) { Text(stringResource(R.string.termux_github_releases)) }
        }
        !state.permission -> StepCard(
            title = stringResource(R.string.termux_allow_title),
            body = stringResource(R.string.termux_allow_body),
        ) {
            if (settingsNeeded) {
                Button(onClick = {
                    context.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }) { Text(stringResource(R.string.termux_open_settings)) }
            } else {
                Button(onClick = { askPermission.launch(TermuxApi.PERMISSION_RUN_COMMAND) }, modifier = Modifier.testTag("termux_grant")) { Text(stringResource(R.string.termux_allow)) }
            }
        }
        state.issue == TermuxIssue.ExternalAppsDisabled -> StepCard(
            title = stringResource(R.string.termux_setup_title),
            body = stringResource(R.string.termux_setup_body),
        ) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectionContainer {
                    Text(
                        TermuxApi.ENABLE_EXTERNAL_APPS,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr),
                        modifier = Modifier.fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(8.dp))
                            .padding(10.dp),
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { clipboard.setText(TermuxApi.ENABLE_EXTERNAL_APPS) }) {
                        Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.common_copy), Modifier.padding(start = 6.dp))
                    }
                    Button(onClick = { clipboard.setText(TermuxApi.ENABLE_EXTERNAL_APPS); TermuxBridge.launch(context) }) { Text(stringResource(R.string.termux_copy_open)) }
                    TextButton(onClick = { session.clearIssue(); session.refresh() }) { Text(stringResource(R.string.termux_done_it)) }
                }
            }
        }
        state.issue == TermuxIssue.StorageNotGranted -> StepCard(
            title = stringResource(R.string.termux_storage_title),
            body = stringResource(R.string.termux_storage_body),
        ) {
            Button(onClick = { session.clearIssue(); session.setupStorage() }) { Text(stringResource(R.string.termux_allow_storage)) }
        }
        state.issue is TermuxIssue.Failed -> StepCard(title = stringResource(R.string.termux_failed_title), body = (state.issue as TermuxIssue.Failed).message) {
            TextButton(onClick = { session.clearIssue(); session.refresh() }) { Text(stringResource(R.string.common_retry)) }
        }
    }
}

@Composable
private fun StepCard(title: String, body: String, actions: @Composable () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RunOutputPanel(state: TermuxUiState, target: String, onStop: () -> Unit) {
    val output = state.outputFor(target)
    val clipboard = rememberTextClipboard()
    when {
        state.runningFor(target) -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LoadingIndicator(Modifier.size(32.dp))
            Text(stringResource(R.string.termux_running), Modifier.weight(1f))
            TextButton(onClick = onStop) { Text(stringResource(R.string.termux_stop_waiting)) }
        }
        output != null -> {
            val out = output
            val ok = out.exitCode == 0
            val code = out.exitCode
            val seconds = out.millis / 1000.0
            val exit = if (code != null) stringResource(R.string.termux_exit_status, code, seconds) else stringResource(R.string.termux_exit_status_unknown, seconds)
            val truncated = if (out.truncated) stringResource(R.string.termux_output_truncated, 100) else null
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        listOfNotNull(exit, truncated).joinToString(separator()),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { clipboard.setText(out.stdout + out.stderr) }) { Text(stringResource(R.string.common_copy)) }
                }
                SelectionContainer {
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 320.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(12.dp))
                            .verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())
                            .padding(12.dp)
                            .testTag("run_output"),
                    ) {
                        val console = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr)
                        if (out.stdout.isEmpty() && out.stderr.isEmpty()) {
                            Text(stringResource(R.string.termux_no_output), color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace)
                        }
                        if (out.stdout.isNotEmpty()) Text(out.stdout.trimEnd(), fontFamily = FontFamily.Monospace, style = console)
                        if (out.stderr.isNotEmpty()) Text(out.stderr.trimEnd(), fontFamily = FontFamily.Monospace, style = console, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
fun RunSheet(path: String, onRun: (interactive: Boolean, run: () -> Unit) -> Unit, onDismiss: () -> Unit, session: TermuxSession) {
    val language = remember(path) { TermuxLanguages.forFile(path) } ?: return
    val runTarget = remember(path, language) { RunTarget.forFile(language, File(path)) }
    RunSheetFrame(
        title = stringResource(R.string.termux_run_title, File(path).name),
        language = language,
        kindLabel = null,
        target = path,
        runTarget = runTarget,
        onRun = { interactive, command -> onRun(interactive) { session.run(path, interactive, command) } },
        onInstallLibraries = { names -> session.installLibraries(path, names) },
        onDismiss = onDismiss,
        session = session,
    )
}

@Composable
fun ProjectRunSheet(project: Project, onDismiss: () -> Unit, session: TermuxSession) {
    val dir = project.dir.path
    val runTarget = remember(project) { RunTarget.forProject(project) }
    RunSheetFrame(
        title = stringResource(R.string.termux_run_title, project.dir.name),
        language = project.kind.language,
        kindLabel = project.kind.label,
        target = dir,
        runTarget = runTarget,
        onRun = { interactive, command -> session.runProject(dir, interactive, command) },
        onInstallLibraries = { names -> session.installProjectLibraries(dir, names) },
        onDismiss = onDismiss,
        session = session,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RunSheetFrame(
    title: String,
    language: Language,
    kindLabel: String?,
    target: String,
    runTarget: RunTarget,
    onRun: (interactive: Boolean, command: String?) -> Unit,
    onInstallLibraries: (String) -> Unit,
    onDismiss: () -> Unit,
    session: TermuxSession,
) {
    val state by session.state.collectAsStateWithLifecycle()
    TrackTermuxLaunchDisplay()
    var libraries by remember { mutableStateOf("") }
    val context = LocalContext.current
    val repo = remember { SettingsRepository(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val commands = LocalSettings.current.runCommands
    val saved = remember(commands, runTarget) { RunCommands.saved(commands, runTarget) }
    val default = remember(runTarget) { RunCommands.defaultCommand(runTarget) }
    var editing by remember { mutableStateOf(false) }
    LifecycleResumeEffect(target) {
        session.refresh()
        onPauseOrDispose { }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberSheetState()) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge)
                val installed = state.isInstalled(language)
                val version = state.installed?.get(language.id)?.takeIf { it.isNotBlank() }
                val status = when {
                    !state.termuxInstalled -> stringResource(R.string.termux_status_needs, language.displayName)
                    !state.reachable -> stringResource(R.string.termux_status_via, language.displayName)
                    state.checking && installed == null -> stringResource(R.string.termux_status_checking, language.displayName)
                    installed == true -> version ?: language.displayName
                    installed == false -> stringResource(R.string.termux_status_not_installed, language.displayName)
                    else -> language.displayName
                }
                Text(
                    listOfNotNull(kindLabel, status).joinToString(separator()),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (installed == false) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TermuxSetupStep(state, session)
            if (state.reachable && state.isInstalled(language) == false && state.issue == null) {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val packages = language.packages.joinToString(" ")
                        Text(stringResource(R.string.termux_language_not_installed, language.displayName), style = MaterialTheme.typography.titleMedium)
                        Text(
                            stringResource(R.string.termux_install_with_pkg, packages),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(onClick = { session.install(language) }) { Text(stringResource(R.string.termux_install_packages, packages)) }
                    }
                }
            }
            if (state.canRun(language)) {
                CommandRow(saved?.second ?: default, saved?.first, runTarget, onEdit = { editing = true })
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { onRun(false, saved?.second) }, enabled = !state.runningFor(target), modifier = Modifier.testTag("run_button")) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.termux_run), Modifier.padding(start = 6.dp))
                    }
                    FilledTonalButton(onClick = { onRun(true, saved?.second) }, enabled = !state.runningFor(target), modifier = Modifier.testTag("run_in_termux")) {
                        Icon(Icons.Rounded.Terminal, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(R.string.termux_run_in_termux), Modifier.padding(start = 6.dp))
                    }
                }
                Text(
                    stringResource(R.string.termux_run_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RunOutputPanel(state, target = target, onStop = session::stopWaiting)
                language.libraries?.let { manager ->
                    HorizontalDivider()
                    Text(stringResource(R.string.termux_libraries, manager.label), style = MaterialTheme.typography.titleSmall)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = libraries,
                            onValueChange = { libraries = it },
                            placeholder = { Text(stringResource(R.string.termux_libraries_example, manager.example)) },
                            singleLine = true,
                            textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                            modifier = Modifier.weight(1f),
                        )
                        FilledTonalButton(onClick = { onInstallLibraries(libraries) }, enabled = libraries.isNotBlank()) { Text(stringResource(R.string.common_install)) }
                    }
                }
            }
            state.message?.let { m ->
                LaunchedEffect(m) { kotlinx.coroutines.delay(4000); session.consumeMessage() }
                Text(m, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
    if (editing) {
        RunCommandDialog(
            target = runTarget,
            initial = saved?.second ?: default,
            initialScope = saved?.first ?: RunScope.ONCE,
            savedScope = saved?.first,
            onRun = { command, chosen, interactive ->
                editing = false
                if (chosen != RunScope.ONCE) scope.launch { repo.editRunCommands { RunCommands.remember(it, runTarget, chosen, command) } }
                onRun(interactive, command.takeUnless { it.trim() == default.trim() })
            },
            onForget = {
                editing = false
                saved?.first?.let { savedScope -> scope.launch { repo.editRunCommands { RunCommands.forget(it, runTarget, savedScope) } } }
            },
            onDismiss = { editing = false },
        )
    }
}

@Composable
private fun scopeLabel(scope: RunScope, target: RunTarget): String = when (scope) {
    RunScope.ONCE -> stringResource(R.string.termux_scope_once)
    RunScope.FILE -> stringResource(R.string.termux_scope_file)
    RunScope.PROJECT -> stringResource(R.string.termux_scope_project)
    RunScope.TYPE -> stringResource(R.string.termux_scope_type, target.extension.orEmpty())
}

@Composable
private fun forgetLabel(scope: RunScope, target: RunTarget): String = when (scope) {
    RunScope.ONCE -> ""
    RunScope.FILE -> stringResource(R.string.termux_forget_file)
    RunScope.PROJECT -> stringResource(R.string.termux_forget_project)
    RunScope.TYPE -> stringResource(R.string.termux_forget_type, target.extension.orEmpty())
}

@Composable
private fun savedLabel(scope: RunScope, target: RunTarget): String = when (scope) {
    RunScope.ONCE -> ""
    RunScope.FILE -> stringResource(R.string.termux_saved_file)
    RunScope.PROJECT -> stringResource(R.string.termux_saved_project)
    RunScope.TYPE -> stringResource(R.string.termux_saved_type, target.extension.orEmpty())
}

@Composable
private fun CommandRow(command: String, savedScope: RunScope?, target: RunTarget, onEdit: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.surfaceContainerHigh, RoundedCornerShape(16.dp))
            .padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                if (savedScope != null) savedLabel(savedScope, target) else stringResource(R.string.termux_command),
                style = MaterialTheme.typography.labelMedium,
                color = if (savedScope != null) colors.primary else colors.onSurfaceVariant,
            )
            Text(command, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr), maxLines = 3, modifier = Modifier.testTag("run_command"))
        }
        IconButton(onClick = onEdit, modifier = Modifier.testTag("edit_command")) {
            Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.termux_edit_command))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RunCommandDialog(
    target: RunTarget,
    initial: String,
    initialScope: RunScope,
    savedScope: RunScope?,
    onRun: (command: String, scope: RunScope, interactive: Boolean) -> Unit,
    onForget: () -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(TextFieldValue(initial, TextRange(initial.length))) }
    var chosen by remember { mutableStateOf(initialScope) }
    val ready = text.text.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.termux_run_command)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr),
                    minLines = 2,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth().testTag("command_field"),
                )
                Text(
                    stringResource(R.string.termux_runs_in, target.dir.name.ifEmpty { "/" }, "DEBUG=1"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RunCommands.VARIABLES.filter { (name, _) -> target.file != null || name !in listOf("\$NAME", "\$FILE", "\$BASE") }.forEach { (name, meaning) ->
                        val description = stringResource(R.string.termux_variable_description, name, stringResource(meaning))
                        AssistChip(
                            onClick = {
                                val insert = "\"$name\""
                                val start = text.selection.start
                                val end = text.selection.end
                                val next = text.text.substring(0, start) + insert + text.text.substring(end)
                                text = TextFieldValue(next, TextRange(start + insert.length))
                            },
                            label = { Text(name, fontFamily = FontFamily.Monospace) },
                            modifier = Modifier.semantics { contentDescription = description },
                        )
                    }
                }
                Column(Modifier.selectableGroup()) {
                    target.scopes.forEach { option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(chosen == option, role = Role.RadioButton) { chosen = option }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = chosen == option, onClick = null)
                            Text(scopeLabel(option, target), Modifier.padding(start = 8.dp))
                        }
                    }
                }
                if (savedScope != null) {
                    TextButton(onClick = onForget, modifier = Modifier.testTag("restore_default")) { Text(forgetLabel(savedScope, target)) }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onRun(text.text.trim(), chosen, true) }, enabled = ready, modifier = Modifier.testTag("dialog_run_in_termux")) { Text(stringResource(R.string.termux_run_in_termux)) }
                Button(onClick = { onRun(text.text.trim(), chosen, false) }, enabled = ready, modifier = Modifier.testTag("dialog_run")) { Text(stringResource(R.string.termux_run)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
fun TerminalGate(session: TermuxSession, snackbar: SnackbarHostState) {
    val state by session.state.collectAsStateWithLifecycle()
    TrackTermuxLaunchDisplay()
    if (state.terminalBlocked) {
        LifecycleResumeEffect(Unit) {
            session.refresh()
            onPauseOrDispose { }
        }
        AlertDialog(
            onDismissRequest = session::cancelTerminal,
            title = { Text(if (state.terminalIsSsh) stringResource(R.string.termux_ssh_opens) else stringResource(R.string.termux_terminal_opens)) },
            text = { TermuxSetupStep(state, session) },
            confirmButton = { TextButton(onClick = session::cancelTerminal) { Text(stringResource(R.string.common_close)) } },
        )
    }
    if (state.sshMissing) SshMissingDialog(session)
    state.message?.let { m ->
        LaunchedEffect(m) {
            snackbar.showSnackbar(m)
            session.consumeMessage()
        }
    }
}

@Composable
private fun SshMissingDialog(session: TermuxSession) {
    AlertDialog(
        onDismissRequest = session::dismissSsh,
        title = { Text(stringResource(R.string.termux_openssh_missing)) },
        text = { Text(stringResource(R.string.termux_openssh_body)) },
        confirmButton = { TextButton(onClick = session::installSsh) { Text(stringResource(R.string.termux_install_openssh)) } },
        dismissButton = { TextButton(onClick = session::dismissSsh) { Text(stringResource(R.string.common_cancel)) } },
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun TrackTermuxLaunchDisplay() {
    val context = LocalContext.current
    DisposableEffect(context) {
        val activity = context.findActivity()
        @Suppress("DEPRECATION")
        TermuxBridge.launchDisplayId = when {
            activity == null -> null
            Build.VERSION.SDK_INT >= 30 -> activity.display?.displayId
            else -> activity.windowManager?.defaultDisplay?.displayId
        }
        onDispose { }
    }
}
