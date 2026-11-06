package dev.cluvex.strata.feature.shell

import androidx.compose.material.icons.rounded.Shortcut
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.Placeholder
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.InlineTextContent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
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
import dev.cluvex.strata.core.shell.ActiveRun
import dev.cluvex.strata.core.shell.LogEntry
import dev.cluvex.strata.core.shell.ShellLogSource
import dev.cluvex.strata.core.shell.ShellMode
import dev.cluvex.strata.core.shell.ShellRunTracker
import dev.cluvex.strata.core.shell.ShellRunner
import dev.cluvex.strata.ui.expressive.ExpressiveDialog
import dev.cluvex.strata.ui.expressive.ExpressiveEmptyState
import dev.cluvex.strata.ui.rememberTextClipboard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ShellLogScreen(onBack: () -> Unit, vm: ShellLogViewModel = viewModel()) {
    BackHandler(onBack = onBack)
    val ui by vm.state.collectAsState()
    // Commands that are running now (from the app or a shortcut) are listed on top and followed live. The saved
    // entries are read again whenever one starts or ends, so a finished run appears right away.
    val active by ShellRunTracker.runs.collectAsState()
    LaunchedEffect(active.keys) { vm.reload() }
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
                ui.entries.isEmpty() && active.isEmpty() -> ExpressiveEmptyState(
                    title = stringResource(R.string.shell_log_empty),
                    body = stringResource(R.string.shell_log_empty_body),
                    icon = Icons.Rounded.History,
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                )
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    // A running item and its saved entry share one key (the entry id), so when Stop ends the run the
                    // item stays open and just turns into the finished entry instead of collapsing.
                    items(active.values.toList(), key = { it.entryId.ifEmpty { "run:" + it.commandId } }) { r ->
                        val key = r.entryId.ifEmpty { "run:" + r.commandId }
                        val open = key in expanded
                        ActiveRunItem(
                            run = r,
                            expanded = open,
                            dateText = dateFormat.format(Date(r.startedAt)),
                            onToggle = { expanded = if (open) expanded - key else expanded + key },
                        )
                    }
                    val runningIds = active.values.map { it.entryId }.toSet()
                    items(ui.entries.filter { it.id !in runningIds }, key = { it.id }) { e ->
                        val open = e.id in expanded
                        LogItem(
                            entry = e,
                            // A background command whose process is alive shows a spinner instead of its start result.
                            backgroundRunning = e.id in ui.runningEntryIds,
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

private fun logText(e: LogEntry): String = e.output

@Composable
private fun LogItem(entry: LogEntry, backgroundRunning: Boolean, expanded: Boolean, dateText: String, onToggle: () -> Unit, onCopy: () -> Unit) {
    val sep = stringResource(R.string.common_separator)
    val outcome = shellOutcome(backgroundRunning, entry.timedOut, entry.stopped, entry.exitCode)
    val source = stringResource(if (entry.source == ShellLogSource.SHORTCUT) R.string.shell_log_source_shortcut else R.string.shell_log_source_app)
    val fromShortcut = entry.source == ShellLogSource.SHORTCUT
    val metaBase = shellStatusLine(outcome, entry.backend, entry.exitCode, entry.durationMs)
    // The run source is shown as a small icon plus a short word: play = started in the app, arrow = started from a shortcut.
    val meta = buildAnnotatedString {
        append(shellStatusAnnotated(outcome, metaBase))
        append(sep)
        appendInlineContent("source", "*")
        append(" ")
        append(source)
    }
    val sourceInline = mapOf(
        "source" to InlineTextContent(Placeholder(14.sp, 14.sp, PlaceholderVerticalAlign.TextCenter)) {
            Icon(if (fromShortcut) Icons.Rounded.Shortcut else Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
        },
    )
    val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, textDirection = TextDirection.Ltr)
    val clipboard = rememberTextClipboard()
    var reload by remember { mutableIntStateOf(0) }
    var fileText by remember { mutableStateOf<String?>(null) }
    var fileLoading by remember { mutableStateOf(false) }
    val logFile = entry.logFile
    // A background log opens at its last line and follows it while the process runs.
    val fileScroll = rememberTailScrollState(enabled = expanded && logFile != null)
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf<Boolean?>(null) }
    var stopping by remember { mutableStateOf(false) }
    val readMode = when (entry.backend) {
        "SHIZUKU" -> ShellMode.SHIZUKU
        "ROOT" -> ShellMode.ROOT
        else -> ShellMode.AUTO
    }
    if (expanded && logFile != null) {
        // Reads the log once, then keeps following it every few seconds for as long as the process runs.
        // `reload` only changes after Stop, to pick up the final state straight away.
        LaunchedEffect(entry.id, reload) {
            fileLoading = true
            fileText = ShellRunner.readFile(logFile, readMode)
            running = ShellRunner.isDetachedRunning(entry.commandId, readMode)
            fileLoading = false
            while (running == true) {
                delay(LOG_FOLLOW_MS)
                // A failed read keeps the text that is already shown.
                ShellRunner.readFile(logFile, readMode)?.let { fileText = it }
                running = ShellRunner.isDetachedRunning(entry.commandId, readMode)
            }
        }
    }

    Column(Modifier.fillMaxWidth()) {
        ListItem(
            modifier = Modifier.clickable(onClick = onToggle),
            leadingContent = { ShellStatusIcon(outcome) },
            overlineContent = { Text(dateText) },
            headlineContent = { Text(entry.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = { Text(meta, inlineContent = sourceInline) },
            trailingContent = {
                Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
            },
        )
        if (expanded) {
            Column(
                Modifier.fillMaxWidth().padding(start = 72.dp, end = 16.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (logFile == null) {
                    Text(stringResource(R.string.shell_log_output), style = MaterialTheme.typography.labelMedium)
                    Box(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                        SelectionContainer {
                            Text(entry.output.ifEmpty { stringResource(R.string.shell_no_output) }, style = mono)
                        }
                    }
                }
                if (logFile != null) {
                    Text(stringResource(R.string.shell_log_file), style = MaterialTheme.typography.labelMedium)
                    Text(logFile, style = mono, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(fileScroll)) {
                        SelectionContainer {
                            Text(
                                when {
                                    fileLoading && fileText == null -> "…"
                                    fileText == null -> stringResource(R.string.shell_log_file_missing)
                                    fileText!!.isEmpty() -> stringResource(R.string.shell_no_output)
                                    else -> fileText!!
                                },
                                style = mono,
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        // Stop only makes sense while the background process is alive.
                        if (running == true) {
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        stopping = true
                                        ShellRunner.stopDetached(entry.commandId, readMode)
                                        stopping = false
                                        reload++
                                    }
                                },
                                enabled = !stopping,
                            ) { Text(stringResource(R.string.shell_log_stop), color = MaterialTheme.colorScheme.error) }
                        }
                        TextButton(onClick = { clipboard.setText(fileText.orEmpty()) }, enabled = !fileText.isNullOrEmpty()) {
                            Text(stringResource(R.string.common_copy))
                        }
                    }
                }
                if (logFile == null) {
                    TextButton(onClick = onCopy) { Text(stringResource(R.string.common_copy)) }
                }
            }
        }
    }
}

/** A command that is running right now: its output so far, following the end, with Stop. */
@Composable
private fun ActiveRunItem(run: ActiveRun, expanded: Boolean, dateText: String, onToggle: () -> Unit) {
    val sep = stringResource(R.string.common_separator)
    val source = stringResource(if (run.source == ShellLogSource.SHORTCUT) R.string.shell_log_source_shortcut else R.string.shell_log_source_app)
    val primary = MaterialTheme.colorScheme.primary
    val runningWord = stringResource(R.string.shell_log_running)
    val meta = buildAnnotatedString {
        withStyle(SpanStyle(color = primary)) { append(runningWord) }
        append(sep)
        append(source)
    }
    val clipboard = rememberTextClipboard()
    val scope = rememberCoroutineScope()
    var stopping by remember { mutableStateOf(false) }
    // If the stop did not work, the button comes back after a few seconds.
    LaunchedEffect(stopping) {
        if (stopping) {
            delay(4_000)
            stopping = false
        }
    }
    Column(Modifier.fillMaxWidth()) {
        ListItem(
            modifier = Modifier.clickable(onClick = onToggle),
            leadingContent = { ShellStatusIcon(ShellOutcome.RUNNING) },
            overlineContent = { Text(dateText) },
            headlineContent = { Text(run.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
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
                LiveOutputText(run.output.ifEmpty { "…" }, maxHeight = 360.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            stopping = true
                            scope.launch { ShellRunner.stopRun(run.commandId, run.mode) }
                        },
                        enabled = !stopping,
                    ) { Text(stringResource(R.string.shell_log_stop), color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = { clipboard.setText(run.output) }, enabled = run.output.isNotEmpty()) {
                        Text(stringResource(R.string.common_copy))
                    }
                }
            }
        }
    }
}

internal fun duration(ms: Long): String =
    if (ms < 1_000) "$ms ms" else String.format(java.util.Locale.US, "%.1f s", ms / 1000.0)


/** How a run ended, in the words the person sees; the same everywhere a run is shown. */
internal enum class ShellOutcome { RUNNING, SUCCESS, FAILED, TIMED_OUT, STOPPED }

internal fun shellOutcome(running: Boolean, timedOut: Boolean, stopped: Boolean, exitCode: Int): ShellOutcome = when {
    running -> ShellOutcome.RUNNING
    timedOut -> ShellOutcome.TIMED_OUT
    stopped -> ShellOutcome.STOPPED
    exitCode == 0 -> ShellOutcome.SUCCESS
    else -> ShellOutcome.FAILED
}

@Composable
internal fun shellOutcomeText(outcome: ShellOutcome): String = stringResource(
    when (outcome) {
        ShellOutcome.RUNNING -> R.string.shell_log_running
        ShellOutcome.SUCCESS -> R.string.shell_log_success
        ShellOutcome.FAILED -> R.string.shell_log_failed
        ShellOutcome.TIMED_OUT -> R.string.shell_log_timed_out
        ShellOutcome.STOPPED -> R.string.shell_log_stopped
    },
)

/**
 * The status line of a run: "Failed · Shizuku · exit code 1 · 158 ms". Used by the history and by the result dialog,
 * so both always say the same. The exit code is only shown for a failure, where it helps; a success, a timeout and a
 * stop already say it all. While a run is going, its time and code are not final, so they are left out.
 */
@Composable
internal fun shellStatusLine(outcome: ShellOutcome, backend: String?, exitCode: Int, durationMs: Long?): String {
    val sep = stringResource(R.string.common_separator)
    val who = when (backend) {
        "SHIZUKU" -> stringResource(R.string.shell_via_shizuku)
        "ROOT" -> stringResource(R.string.shell_via_root)
        else -> stringResource(R.string.shell_banner_no_backend)
    }
    val status = shellOutcomeText(outcome)
    if (outcome == ShellOutcome.RUNNING) return listOf(status, who).joinToString(sep)
    val code = if (backend != null && outcome == ShellOutcome.FAILED) stringResource(R.string.shell_exit_code, exitCode) else null
    return listOfNotNull(status, who, code, durationMs?.takeIf { it > 0 }?.let { duration(it) }).joinToString(sep)
}

/**
 * The status line with the first word colored: "Running" in the theme color (primary), "Failed" in red (error),
 * "Stopped" in tertiary, "Timed out" in the same amber as its icon, "Success" in green.
 */
@Composable
internal fun shellStatusAnnotated(outcome: ShellOutcome, line: String): AnnotatedString {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    val word = shellOutcomeText(outcome)
    val color = when (outcome) {
        ShellOutcome.RUNNING -> scheme.primary
        ShellOutcome.FAILED -> scheme.error
        ShellOutcome.STOPPED -> scheme.tertiary
        ShellOutcome.TIMED_OUT -> if (dark) Color(0xFFF2C94C) else Color(0xFF8A6100)
        ShellOutcome.SUCCESS -> if (dark) Color(0xFF7BE08B) else Color(0xFF1B7F3A)
    }
    return buildAnnotatedString {
        append(line)
        if (color != null && line.startsWith(word)) {
            addStyle(SpanStyle(color = color), 0, word.length)
        }
    }
}

/** The round status icon of a run; all outcomes share one size and shape. */
@Composable
internal fun ShellStatusIcon(outcome: ShellOutcome) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val scheme = MaterialTheme.colorScheme
    val text = shellOutcomeText(outcome)
    if (outcome == ShellOutcome.RUNNING) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
        return
    }
    val (icon, bg, fg) = when (outcome) {
        ShellOutcome.SUCCESS -> Triple(Icons.Rounded.Check, scheme.primaryContainer, scheme.onPrimaryContainer)
        ShellOutcome.FAILED -> Triple(Icons.Rounded.Close, scheme.errorContainer, scheme.onErrorContainer)
        // A timeout and a stop are not errors, so they are not red: amber and neutral.
        ShellOutcome.TIMED_OUT -> Triple(
            Icons.Rounded.Schedule,
            if (dark) Color(0xFF4A3A12) else Color(0xFFFCEFC7),
            if (dark) Color(0xFFF2C94C) else Color(0xFF8A6100),
        )
        else -> Triple(Icons.Rounded.Stop, scheme.surfaceVariant, scheme.onSurfaceVariant)
    }
    Box(Modifier.size(32.dp).background(bg, CircleShape), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = text, tint = fg, modifier = Modifier.size(20.dp))
    }
}

/** How often the log of a background command is re-read while it runs. */
private const val LOG_FOLLOW_MS = 2_500L
