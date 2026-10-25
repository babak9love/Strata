package dev.cluvex.strata.feature.termux

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.cluvex.strata.R
import dev.cluvex.strata.core.termux.ProjectTemplate
import dev.cluvex.strata.core.termux.TermuxProjects
import dev.cluvex.strata.core.termux.TermuxSession
import dev.cluvex.strata.ui.rememberSheetState
import java.io.File

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NewProjectSheet(parentDir: String, session: TermuxSession, onCreated: (String) -> Unit, onDismiss: () -> Unit) {
    val state by session.state.collectAsStateWithLifecycle()
    TrackTermuxLaunchDisplay()
    var selected by remember { mutableStateOf<ProjectTemplate?>(null) }
    var name by remember { mutableStateOf("") }
    LifecycleResumeEffect(parentDir) {
        session.refresh()
        onPauseOrDispose { }
    }
    LaunchedEffect(state.createdProject) {
        state.createdProject?.let { path ->
            session.consumeCreatedProject()
            onCreated(path)
        }
    }
    DisposableEffect(Unit) { onDispose { session.clearCreateError() } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberSheetState()) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp)
                .verticalScroll(rememberScrollState()).testTag("new_project"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column {
                Text(stringResource(R.string.termux_new_project), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.termux_made_by_tool, File(parentDir).name.ifEmpty { parentDir }),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TermuxSetupStep(state, session)
            if (state.reachable && state.issue == null) {
                Column {
                    for (t in TermuxProjects.templates) {
                        val installed = state.isInstalled(t.language)
                        ListItem(
                            content = { Text(t.title) },
                            supportingContent = { Text(t.command, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr)) },
                            leadingContent = {
                                RadioButton(selected = selected == t, onClick = null, enabled = installed == true)
                            },
                            trailingContent = {
                                when (installed) {
                                    true -> Unit
                                    false -> TextButton(onClick = { session.install(t.language) }) { Text(stringResource(R.string.common_install)) }
                                    null -> Text(stringResource(R.string.termux_checking), style = MaterialTheme.typography.labelSmall)
                                }
                            },
                            colors = ListItemDefaults.colors(),
                            modifier = Modifier
                                .clickable(enabled = installed == true) {
                                    selected = t
                                    session.clearCreateError()
                                }
                                .testTag("template_${t.id}"),
                        )
                    }
                }
                selected?.let { t ->
                    val existing = remember(parentDir, state.creating) { File(parentDir).list()?.toList().orEmpty() }
                    val problem = if (name.isEmpty()) null else TermuxProjects.validate(t, name, existing)
                    val canCreate = name.isNotBlank() && problem == null && !state.creating
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.trim(); session.clearCreateError() },
                        label = { Text(stringResource(R.string.termux_project_name)) },
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                        isError = problem != null,
                        supportingText = { Text(problem ?: stringResource(t.nameHint)) },
                        enabled = !state.creating,
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (canCreate) session.createProject(t, parentDir, name) }),
                        modifier = Modifier.fillMaxWidth().testTag("project_name"),
                    )
                    Text(
                        "$ " + t.command.replace("<name>", name.ifBlank { "<name>" }),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.labelMedium.copy(textDirection = TextDirection.Ltr),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    state.createError?.let { error ->
                        Text(error, color = MaterialTheme.colorScheme.error, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (state.creating) {
                            LoadingIndicator(Modifier.size(32.dp))
                            Text(stringResource(R.string.termux_creating))
                        } else {
                            Button(
                                onClick = { session.createProject(t, parentDir, name) },
                                enabled = canCreate,
                                modifier = Modifier.testTag("create_project"),
                            ) { Text(stringResource(R.string.common_create)) }
                        }
                    }
                }
            }
        }
    }
}
