package dev.cluvex.strata.feature.shell

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.cluvex.strata.R
import dev.cluvex.strata.core.shell.ShellMode
import dev.cluvex.strata.core.shell.ShellRunTracker
import dev.cluvex.strata.core.shell.ShellRunner
import dev.cluvex.strata.ui.expressive.ExpressiveDialog
import dev.cluvex.strata.ui.rememberTextClipboard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How close to the end (in pixels) still counts as "at the end". */
private const val TAIL_SLACK_PX = 24

/**
 * A scroll state for a log: it shows the end of the content as soon as it is laid out, and keeps following the end
 * while the content grows. Once the user scrolls up it stops following, until they scroll back down to the end.
 */
@Composable
internal fun rememberTailScrollState(enabled: Boolean = true): ScrollState {
    val scroll = rememberScrollState()
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(scroll, enabled) {
        if (!enabled) return@LaunchedEffect
        // A log that is opened again starts at its end, whatever the user did the last time.
        follow = true
        launch {
            snapshotFlow { scroll.isScrollInProgress }.collect { moving ->
                if (!moving) follow = scroll.value >= scroll.maxValue - TAIL_SLACK_PX
            }
        }
        snapshotFlow { scroll.maxValue }.collect { end -> if (follow) scroll.scrollTo(end) }
    }
    return scroll
}

/** Selectable monospace log text in a scrolling box that follows its end. */
@Composable
internal fun LiveOutputText(text: String, modifier: Modifier = Modifier, maxHeight: Dp = 320.dp) {
    val scroll = rememberTailScrollState()
    Box(modifier.fillMaxWidth().heightIn(max = maxHeight).verticalScroll(scroll)) {
        SelectionContainer {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    textDirection = TextDirection.Ltr,
                ),
            )
        }
    }
}

/** The output of a command that is running right now. Closing it does not stop the command; Stop does. */
@Composable
internal fun LiveOutputDialog(commandId: String, onDismiss: () -> Unit) {
    val runs by ShellRunTracker.runs.collectAsState()
    val run = runs[commandId]
    val text = run?.output.orEmpty()
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
    ExpressiveDialog(
        onDismissRequest = onDismiss,
        title = run?.name.orEmpty(),
        icon = Icons.Rounded.Terminal,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) } },
        // Stop sits with Copy and Close, in the same row of the dialog.
        dismissButton = {
            Row {
                TextButton(
                    onClick = {
                        stopping = true
                        scope.launch { ShellRunner.stopRun(commandId, run?.mode ?: ShellMode.AUTO) }
                    },
                    enabled = run != null && !stopping,
                ) { Text(stringResource(R.string.shell_log_stop), color = MaterialTheme.colorScheme.error) }
                if (text.isNotEmpty()) {
                    TextButton(onClick = { clipboard.setText(text) }) { Text(stringResource(R.string.common_copy)) }
                }
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.shell_log_running), style = MaterialTheme.typography.labelLarge)
            }
            LiveOutputText(text.ifEmpty { "…" })
        }
    }
}
