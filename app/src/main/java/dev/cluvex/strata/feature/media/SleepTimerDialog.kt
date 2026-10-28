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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.cluvex.strata.R
import kotlinx.coroutines.delay

/** Asks for a duration in a fixed `0h 00m` box (or a quick preset) and starts [SleepTimer]; also shows and stops a running one. */
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
    val parsed = SleepTimer.fromDigits(text)
    val invalid = text.isNotEmpty() && parsed == null
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
                // The "h" and "m" stay in the box; only digits are typed and they fill it from the right (0h 00m).
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = SleepTimer.cleanDigits(it) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = invalid,
                        textStyle = MaterialTheme.typography.headlineSmall.copy(textAlign = TextAlign.Center),
                        visualTransformation = SleepTimerMask,
                        supportingText = if (invalid) {
                            { Text(stringResource(R.string.media_sleep_timer_invalid)) }
                        } else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
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

/** Shows the typed digits inside the fixed `0h 00m` shape; the caret always stays at the end. */
private object SleepTimerMask : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val shown = SleepTimer.maskText(text.text)
        return TransformedText(
            AnnotatedString(shown),
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int) = shown.length
                override fun transformedToOriginal(offset: Int) = text.length
            },
        )
    }
}
