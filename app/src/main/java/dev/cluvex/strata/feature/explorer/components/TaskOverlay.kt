package dev.cluvex.strata.feature.explorer.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.cluvex.strata.feature.explorer.TaskState

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TaskOverlay(task: TaskState) {
    AlertDialog(
        onDismissRequest = {  },
        confirmButton = {},
        title = { Text(task.title) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                if (task.detail.isNotEmpty()) {
                    Text(
                        task.detail,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
                if (task.indeterminate) {
                    LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    LinearWavyProgressIndicator(
                        progress = { task.fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
    )
}
