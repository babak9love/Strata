package dev.cluvex.strata.feature.media

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.cluvex.strata.R
import kotlinx.coroutines.delay

/** Asks for a duration like `3h 5m` (or a quick preset) and starts [SleepTimer]; also shows and stops a running one. */
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
    var text by remember { mutableStateOf("") }
    val parsed = SleepTimer.parse(text)
    val invalid = text.isNotBlank() && parsed == null
    val start = {
        parsed?.let {
            SleepTimer.start(it)
            onDismiss()
        }
        Unit
    }

    AlertDialog(
        onDismissRequest = onDismiss,
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
                // "3h 5m" always reads left to right, even in the Persian layout.
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.take(12) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = invalid,
                        label = { Text(stringResource(R.string.media_sleep_timer_label)) },
                        placeholder = { Text("3h 5m") },
                        supportingText = if (invalid) {
                            { Text(stringResource(R.string.media_sleep_timer_invalid)) }
                        } else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { start() }),
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
