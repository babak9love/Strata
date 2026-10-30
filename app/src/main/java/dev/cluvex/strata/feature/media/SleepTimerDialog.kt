package dev.cluvex.strata.feature.media

import android.os.SystemClock
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.cluvex.strata.R
import kotlinx.coroutines.delay

/** Asks for a duration in a `0h 00m` boxes (or a quick preset) and starts [SleepTimer]; also shows and stops a running one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SleepTimerDialog(onDismiss: () -> Unit) {
    val endsAt by SleepTimer.endsAt.collectAsState()
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(endsAt) {
        while (endsAt != null) {
            now = SystemClock.elapsedRealtime()
            delay(1_000)
        }
    }
    var hours by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("") }
    val parsed = SleepTimer.fromParts(hours, minutes)
    val invalid = (hours.isNotEmpty() || minutes.isNotEmpty()) && parsed == null
    val start = {
        parsed?.let {
            SleepTimer.start(it)
            onDismiss()
        }
        Unit
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.width(fixedDialogWidth()),
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = PANEL_ALPHA),
        title = { Text(stringResource(R.string.media_sleep_timer)) },
        text = {
            Column {
                endsAt?.let { end ->
                    Text(
                        stringResource(R.string.media_sleep_timer_left, SleepTimer.format(end - now)),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(12.dp))
                }
                // Two boxes, each tapped on its own; the "h" and "m" stay inside them. Empty boxes read 0h 00m.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)) {
                        TimeBox(
                            value = hours,
                            onChange = { hours = SleepTimer.digitsOnly(it, 2) },
                            placeholder = "0",
                            unit = "h",
                            invalid = invalid,
                            imeAction = ImeAction.Next,
                            onDone = {},
                            modifier = Modifier.width(88.dp).height(44.dp),
                        )
                        TimeBox(
                            value = minutes,
                            onChange = { minutes = SleepTimer.digitsOnly(it, 2) },
                            placeholder = "00",
                            unit = "m",
                            invalid = invalid,
                            imeAction = ImeAction.Done,
                            onDone = { start() },
                            modifier = Modifier.width(88.dp).height(44.dp),
                        )
                    }
                }
                if (invalid) {
                    Text(
                        stringResource(R.string.media_sleep_timer_invalid),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 16.dp, top = 4.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("15m", "30m", "45m", "1h", "2h").forEach { preset ->
                        AssistChip(
                            onClick = {
                                SleepTimer.parse(preset)?.let { SleepTimer.start(it) }
                                onDismiss()
                            },
                            label = { Text(preset) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { start() }, enabled = parsed != null) {
                Text(stringResource(R.string.media_sleep_timer_start))
            }
        },
        dismissButton = {
            if (endsAt != null) {
                TextButton(onClick = {
                    SleepTimer.cancel()
                    onDismiss()
                }) { Text(stringResource(R.string.media_sleep_timer_off)) }
            } else {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
            }
        },
    )
}

@Composable
private fun TimeBox(
    value: String,
    onChange: (String) -> Unit,
    placeholder: String,
    unit: String,
    invalid: Boolean,
    imeAction: ImeAction,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val focus = remember { FocusRequester() }
    val borderColor = when {
        invalid -> MaterialTheme.colorScheme.error
        focused -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.outline
    }
    val textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.End, color = MaterialTheme.colorScheme.onSurface)
    // The number and its unit ("0h", "00m") are one group, centred in the box; tapping anywhere in the box edits it.
    Box(
        modifier
            .border(if (focused) 2.dp else 1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { focus.requestFocus() },
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(26.dp), contentAlignment = Alignment.CenterEnd) {
                if (value.isEmpty()) {
                    Text(placeholder, style = MaterialTheme.typography.bodyLarge.copy(color = hint, textAlign = TextAlign.End))
                }
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    singleLine = true,
                    textStyle = textStyle,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = imeAction),
                    keyboardActions = KeyboardActions(onDone = { onDone() }),
                    interactionSource = interaction,
                )
            }
            Text(unit, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
