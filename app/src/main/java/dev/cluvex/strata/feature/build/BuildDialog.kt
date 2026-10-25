package dev.cluvex.strata.feature.build

import dev.cluvex.strata.core.i18n.separator
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.PriorityHigh
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.cluvex.strata.R
import dev.cluvex.strata.core.build.BuildRun
import dev.cluvex.strata.core.build.BuildStep
import dev.cluvex.strata.core.build.LogLevel
import dev.cluvex.strata.core.build.StepStatus
import dev.cluvex.strata.ui.rememberTextClipboard
import kotlinx.coroutines.delay

const val BUILD_DIALOG_TAG = "build-dialog"

private const val PROGRESS_ANIMATION = "build-progress"

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BuildDialog(run: BuildRun, onClose: () -> Unit, autoCloseMillis: Long = 1400L) {
    val colors = MaterialTheme.colorScheme
    var touched by remember { mutableStateOf(false) }
    var showLog by remember { mutableStateOf(true) }
    var copied by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val clipboard = rememberTextClipboard()

    LaunchedEffect(run.finished) {
        while (!run.finished) {
            now = System.currentTimeMillis()
            delay(100)
        }
    }
    LaunchedEffect(run.finished, run.failed, touched) {
        if (run.finished && !run.failed && !touched && autoCloseMillis > 0) {
            delay(autoCloseMillis)
            onClose()
        }
    }

    Dialog(
        onDismissRequest = { if (run.finished) onClose() },
        properties = DialogProperties(dismissOnBackPress = run.finished, dismissOnClickOutside = false, usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = colors.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .widthIn(max = 600.dp)
                .testTag(BUILD_DIALOG_TAG)
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial)
                            touched = true
                        }
                    }
                },
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp).animateContentSize()) {
                Header(run, now)
                Spacer(Modifier.size(14.dp))
                val progress by animateFloatAsState(run.progress, label = PROGRESS_ANIMATION)
                when {
                    run.failed -> LinearProgressIndicator(
                        progress = { 1f },
                        color = colors.error,
                        trackColor = colors.errorContainer,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    run.finished -> LinearProgressIndicator(progress = { 1f }, modifier = Modifier.fillMaxWidth())
                    else -> LinearWavyProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().testTag("build-progress"))
                }
                Spacer(Modifier.size(12.dp))
                Column(Modifier.fillMaxWidth().testTag("build-steps")) {
                    run.steps.forEach { StepRow(it, now) }
                }
                run.failure?.let { failure ->
                    Surface(
                        color = colors.errorContainer,
                        contentColor = colors.onErrorContainer,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).semantics(mergeDescendants = true) {}.testTag("build-error"),
                    ) {
                        Row(Modifier.padding(12.dp)) {
                            Icon(Icons.Rounded.Error, contentDescription = null, modifier = Modifier.size(20.dp))
                            SelectionContainer {
                                Text(failure, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 10.dp))
                            }
                        }
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { showLog = !showLog; touched = true }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.build_log), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 4.dp))
                    if (run.warnings > 0) {
                        Text(
                            pluralStringResource(R.plurals.build_warnings, run.warnings, BuildRun.number(run.warnings)),
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.tertiary,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Icon(
                        if (showLog) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = stringResource(if (showLog) R.string.build_hide_log else R.string.build_show_log),
                    )
                }
                AnimatedVisibility(showLog) { Console(run) }
                if (run.finished) {
                    Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = {
                            clipboard.setText(run.text())
                            copied = true
                            touched = true
                        }) {
                            Icon(if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(if (copied) R.string.common_copied else R.string.build_copy_log))
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = onClose,
                            colors = if (run.failed) ButtonDefaults.buttonColors(containerColor = colors.error, contentColor = colors.onError) else ButtonDefaults.buttonColors(),
                        ) { Text(stringResource(if (run.failed) R.string.common_close else R.string.common_done)) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Header(run: BuildRun, now: Long) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when {
        run.failed -> colors.errorContainer to colors.onErrorContainer
        run.finished -> colors.primaryContainer to colors.onPrimaryContainer
        else -> colors.secondaryContainer to colors.onSecondaryContainer
    }
    val shape = when {
        run.failed -> MaterialShapes.Cookie9Sided.toShape()
        run.finished -> MaterialShapes.Sunny.toShape()
        else -> MaterialShapes.Cookie12Sided.toShape()
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(52.dp).clip(shape).background(container), contentAlignment = Alignment.Center) {
            when {
                run.failed -> Icon(Icons.Rounded.PriorityHigh, contentDescription = stringResource(R.string.build_status_failed), tint = content)
                run.finished -> Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.build_status_done), tint = content)
                else -> Icon(Icons.Rounded.Build, contentDescription = stringResource(R.string.build_status_working), tint = content)
            }
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(run.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("build-title"))
            val elapsed = BuildRun.seconds(run.elapsed(now))
            val doneLabel = stringResource(R.string.build_status_done)
            val status = when {
                run.failed -> stringResource(R.string.build_failed_after, elapsed)
                run.finished -> run.summary.ifEmpty { doneLabel } + separator() + elapsed
                else -> {
                    val step = run.steps.getOrNull(run.running)
                    val n = run.running + 1
                    if (step != null) stringResource(R.string.build_step_of, BuildRun.number(n), BuildRun.number(run.steps.size)) + separator() + elapsed else elapsed
                }
            }
            Text(
                status,
                style = MaterialTheme.typography.bodyMedium,
                color = if (run.failed) colors.error else colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("build-status"),
            )
            if (run.subject.isNotEmpty()) {
                Text(run.subject, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StepRow(step: BuildStep, now: Long) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp).testTag("build-step"), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
            when (step.status) {
                StepStatus.RUNNING -> LoadingIndicator(Modifier.size(28.dp))
                StepStatus.DONE -> Icon(Icons.Rounded.CheckCircle, contentDescription = stringResource(R.string.build_status_done), tint = colors.primary)
                StepStatus.FAILED -> Icon(Icons.Rounded.Error, contentDescription = stringResource(R.string.build_status_failed), tint = colors.error)
                StepStatus.SKIPPED -> Icon(Icons.Rounded.RemoveCircleOutline, contentDescription = stringResource(R.string.build_status_skipped), tint = colors.outline)
                StepStatus.WAITING -> Icon(Icons.Rounded.RadioButtonUnchecked, contentDescription = stringResource(R.string.build_status_waiting), tint = colors.outlineVariant)
            }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                step.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (step.status == StepStatus.RUNNING) FontWeight.SemiBold else FontWeight.Normal,
                color = when (step.status) {
                    StepStatus.WAITING, StepStatus.SKIPPED -> colors.onSurfaceVariant
                    StepStatus.FAILED -> colors.error
                    else -> colors.onSurface
                },
            )
            if (step.detail.isNotEmpty() && step.status != StepStatus.WAITING) {
                Text(
                    step.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (step.status == StepStatus.FAILED) colors.error else colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (step.status == StepStatus.RUNNING && step.total > 0) {
                LinearProgressIndicator(
                    progress = { step.done.toFloat() / step.total },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
        val right = when {
            step.status == StepStatus.RUNNING && step.total > 0 -> "${step.done}/${step.total}"
            step.status == StepStatus.RUNNING -> BuildRun.seconds((now - step.startedAt).coerceAtLeast(0L))
            step.millis > 0 || step.status == StepStatus.DONE || step.status == StepStatus.FAILED -> BuildRun.seconds(step.millis)
            else -> ""
        }
        if (right.isNotEmpty()) Text(right, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun Console(run: BuildRun) {
    val colors = MaterialTheme.colorScheme
    val state = rememberLazyListState()
    LaunchedEffect(run.lines.size) {
        if (run.lines.isNotEmpty()) state.scrollToItem(run.lines.lastIndex)
    }
    Surface(color = colors.surfaceContainerLowest, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        SelectionContainer {
            LazyColumn(
                state = state,
                modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp, max = 240.dp).padding(horizontal = 12.dp, vertical = 10.dp).testTag("build-log"),
            ) {
                if (run.dropped > 0) {
                    item {
                        Text(
                            isolated(pluralStringResource(R.plurals.build_earlier_lines, run.dropped, BuildRun.number(run.dropped))),
                            style = MaterialTheme.typography.labelSmall.copy(textDirection = TextDirection.Ltr),
                            fontFamily = FontFamily.Monospace,
                            color = colors.outline,
                        )
                    }
                }
                itemsIndexed(run.lines) { _, line ->
                    val body = when (line.level) {
                        LogLevel.INFO -> line.text
                        LogLevel.WARN -> stringResource(R.string.build_log_warning, line.text)
                        LogLevel.ERROR -> stringResource(R.string.build_log_error, line.text)
                    }
                    val text = buildAnnotatedString {
                        withStyle(SpanStyle(color = colors.outline)) { append(BuildRun.stamp(line.at - run.startedAt) + "  ") }
                        val color = when (line.level) {
                            LogLevel.INFO -> if (line.text.startsWith("▸ ")) colors.primary else colors.onSurface
                            LogLevel.WARN -> colors.tertiary
                            LogLevel.ERROR -> colors.error
                        }
                        withStyle(SpanStyle(color = color, fontWeight = if (line.text.startsWith("▸ ")) FontWeight.SemiBold else FontWeight.Normal)) {
                            append(isolated(body))
                        }
                    }
                    Text(text, style = MaterialTheme.typography.bodySmall.copy(textDirection = TextDirection.Ltr), fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

private fun isolated(text: String): String {
    val rtl = text.any {
        val d = Character.getDirectionality(it)
        d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
    }
    return if (rtl) "\u2068$text\u2069" else text
}
