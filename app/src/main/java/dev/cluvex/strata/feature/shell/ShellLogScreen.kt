package dev.cluvex.strata.feature.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.cluvex.strata.R
import dev.cluvex.strata.core.shell.LogEntry
import dev.cluvex.strata.core.shell.ShellLogSource
import dev.cluvex.strata.ui.expressive.ExpressiveDialog
import dev.cluvex.strata.ui.expressive.ExpressiveEmptyState
import dev.cluvex.strata.ui.rememberTextClipboard
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ShellLogScreen(onBack: () -> Unit, vm: ShellLogViewModel = viewModel()) {
    BackHandler(onBack = onBack)
    val ui by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.reload() }
    var confirmClear by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    val clipboard = rememberTextClipboard()
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM) }

    Scaffold(
        modifier = Modifier.testTag("shell-log-screen"),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.shell_log_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    if (ui.entries.isNotEmpty()) {
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(Icons.Rounded.DeleteSweep, contentDescription = stringResource(R.string.shell_log_clear))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                ui.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
                ui.entries.isEmpty() -> ExpressiveEmptyState(
                    title = stringResource(R.string.shell_log_empty),
                    body = stringResource(R.string.shell_log_empty_body),
                    icon = Icons.Rounded.History,
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    items(ui.entries, key = { it.id }) { e ->
                        val open = e.id in expanded
                        LogItem(
                            entry = e,
                            expanded = open,
                            dateText = dateFormat.format(Date(e.time)),
                            onToggle = { expanded = if (open) expanded - e.id else expanded + e.id },
                            onCopy = { clipboard.setText(logText(e)) },
                        )
                    }
                }
            }
        }
    }

    if (confirmClear) {
        ExpressiveDialog(
            onDismissRequest = { confirmClear = false },
            title = stringResource(R.string.shell_log_clear),
            icon = Icons.Rounded.DeleteSweep,
            confirmButton = {
                TextButton(onClick = { vm.clear(); confirmClear = false }) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        ) {
            Text(stringResource(R.string.shell_log_clear_confirm))
        }
    }
}

private fun logText(e: LogEntry): String = buildString {
    append(e.name).append('\n').append(e.command)
    if (e.output.isNotEmpty()) append("\n\n").append(e.output)
}

@Composable
private fun LogItem(entry: LogEntry, expanded: Boolean, dateText: String, onToggle: () -> Unit, onCopy: () -> Unit) {
    val sep = stringResource(R.string.common_separator)
    val status = stringResource(if (entry.ok) R.string.shell_log_success else R.string.shell_log_failed)
    val who = when (entry.backend) {
        "SHIZUKU" -> stringResource(R.string.shell_via_shizuku)
        "ROOT" -> stringResource(R.string.shell_via_root)
        else -> stringResource(R.string.shell_banner_no_backend)
    }
    val code = when {
        entry.backend == null -> null
        entry.timedOut -> stringResource(R.string.shell_timed_out)
        else -> stringResource(R.string.shell_exit_code, entry.exitCode)
    }
    val source = stringResource(if (entry.source == ShellLogSource.SHORTCUT) R.string.shell_log_source_shortcut else R.string.shell_log_source_app)
    val meta = listOfNotNull(status, who, code, duration(entry.durationMs), source).joinToString(sep)
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr)

    Column(Modifier.fillMaxWidth()) {
        ListItem(
            modifier = Modifier.clickable(onClick = onToggle),
            leadingContent = {
                Icon(
                    if (entry.ok) Icons.Rounded.CheckCircle else Icons.Rounded.Error,
                    contentDescription = status,
                    tint = if (entry.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                )
            },
            overlineContent = { Text(dateText) },
            headlineContent = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text(meta) },
            trailingContent = {
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
            },
        )
        if (expanded) {
            Column(
                Modifier.fillMaxWidth().padding(start = 72.dp, end = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(stringResource(R.string.shell_log_command), style = MaterialTheme.typography.labelMedium)
                SelectionContainer { Text(entry.command, style = mono) }
                Text(stringResource(R.string.shell_log_output), style = MaterialTheme.typography.labelMedium)
                Box(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    SelectionContainer {
                        Text(entry.output.ifEmpty { stringResource(R.string.shell_no_output) }, style = mono)
                    }
                }
                TextButton(onClick = onCopy) { Text(stringResource(R.string.common_copy)) }
            }
        }
    }
}

private fun duration(ms: Long): String =
    if (ms < 1_000) "$ms ms" else String.format(java.util.Locale.US, "%.1f s", ms / 1000.0)
