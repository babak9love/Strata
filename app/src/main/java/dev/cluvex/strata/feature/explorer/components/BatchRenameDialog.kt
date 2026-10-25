package dev.cluvex.strata.feature.explorer.components

import dev.cluvex.strata.core.i18n.separator
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import dev.cluvex.strata.R
import dev.cluvex.strata.core.model.FileItem
import dev.cluvex.strata.core.rename.RenameOrder
import dev.cluvex.strata.core.rename.RenamePlan
import dev.cluvex.strata.core.rename.RenamePreview
import dev.cluvex.strata.core.rename.RenameRule
import dev.cluvex.strata.core.rename.RenameStatus
import kotlinx.coroutines.delay

private data class PreviewKey(val rule: RenameRule, val order: RenameOrder, val descending: Boolean)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BatchRenameDialog(
    items: List<FileItem>,
    history: List<RenameRule>,
    preview: suspend (RenameRule, RenameOrder, Boolean) -> RenamePlan,
    onApply: (RenamePlan, RenameRule) -> Unit,
    onDismiss: () -> Unit,
) {
    var find by remember { mutableStateOf("") }
    var replace by remember { mutableStateOf(if (items.size > 1) "{P}_{1}{S}" else "{P}{S}") }
    var regex by remember { mutableStateOf(false) }
    var datePattern by remember { mutableStateOf(RenameRule.DEFAULT_DATE_PATTERN) }
    var order by remember { mutableStateOf(RenameOrder.CURRENT) }
    var descending by remember { mutableStateOf(false) }
    var computed by remember { mutableStateOf<Pair<PreviewKey, RenamePlan>?>(null) }
    var showHelp by remember { mutableStateOf(false) }
    var historyMenu by remember { mutableStateOf(false) }
    val rule = RenameRule(find, replace, regex, datePattern)
    val usesDates = Regex("""\{[TEte]\}""").containsMatchIn(replace)
    val key = PreviewKey(rule, order, descending)
    val ltr = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr)

    LaunchedEffect(key) {
        delay(120)
        computed = key to preview(rule, order, descending)
    }
    val plan = computed?.second
    val fresh = computed?.first == key

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        DialogSystemBarIcons()
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.common_close)) } },
                    title = { Text(stringResource(R.string.explorer_batch_rename)) },
                    actions = {
                        if (history.isNotEmpty()) {
                            Box {
                                IconButton(onClick = { historyMenu = true }) { Icon(Icons.Rounded.History, contentDescription = stringResource(R.string.explorer_history)) }
                                DropdownMenu(expanded = historyMenu, onDismissRequest = { historyMenu = false }) {
                                    history.forEach { h ->
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    (if (h.find.isEmpty()) "" else "${h.find} → ") + h.replace + (if (h.regex) "  (regex)" else ""),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    fontFamily = FontFamily.Monospace,
                                                )
                                            },
                                            onClick = {
                                                historyMenu = false
                                                find = h.find
                                                replace = h.replace
                                                regex = h.regex
                                                datePattern = h.datePattern
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        TextButton(onClick = { plan?.let { onApply(it, rule) } }, enabled = fresh && plan?.canApply == true) { Text(stringResource(R.string.common_rename)) }
                    },
                )
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                OutlinedTextField(
                    value = find,
                    onValueChange = { find = it },
                    label = { Text(stringResource(R.string.explorer_text_to_find)) },
                    singleLine = true,
                    textStyle = ltr,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = replace,
                    onValueChange = { replace = it },
                    label = { Text(stringResource(R.string.explorer_replace_with)) },
                    singleLine = true,
                    textStyle = ltr,
                    trailingIcon = {
                        IconButton(onClick = { showHelp = !showHelp }) { Icon(Icons.Rounded.Info, contentDescription = stringResource(R.string.explorer_variables)) }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("{P}", "{S}", "{1}", "{z1}", "{T}", "{E}").forEach { v ->
                        AssistChip(onClick = { replace += v }, label = { Text(v, fontFamily = FontFamily.Monospace) })
                    }
                }
                if (showHelp) {
                    Text(stringResource(R.string.explorer_rename_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(
                    Modifier.fillMaxWidth().toggleable(value = regex, onValueChange = { regex = it }).padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = regex, onCheckedChange = null)
                    Text(stringResource(R.string.explorer_use_regex), modifier = Modifier.padding(start = 8.dp))
                }
                if (usesDates) {
                    OutlinedTextField(
                        value = datePattern,
                        onValueChange = { datePattern = it },
                        label = { Text(stringResource(R.string.explorer_date_pattern)) },
                        singleLine = true,
                        textStyle = ltr,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text(stringResource(R.string.explorer_number_order), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        RenameOrder.CURRENT to R.string.explorer_order_listed,
                        RenameOrder.NAME to R.string.common_name,
                        RenameOrder.DATE to R.string.common_date,
                        RenameOrder.SIZE to R.string.common_size,
                    ).forEach { (o, label) -> FilterChip(selected = order == o, onClick = { order = o }, label = { Text(stringResource(label)) }) }
                    FilterChip(selected = descending, onClick = { descending = !descending }, label = { Text(stringResource(R.string.explorer_reverse)) })
                }
                Summary(plan)
                HorizontalDivider()
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    items(plan?.entries.orEmpty(), key = { it.item.path }) { PreviewRow(it) }
                }
            }
        }
    }
}

@Composable
internal fun DialogSystemBarIcons() {
    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window ?: return
    val lightSurface = MaterialTheme.colorScheme.surface.luminance() > 0.5f
    SideEffect {
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = lightSurface
            isAppearanceLightNavigationBars = lightSurface
        }
    }
}

@Composable
private fun Summary(plan: RenamePlan?) {
    val error = MaterialTheme.colorScheme.error
    val text = when {
        plan == null -> "…"
        plan.error != null -> plan.error
        else -> buildList {
            add(pluralStringResource(R.plurals.explorer_rename_count, plan.changes.size, plan.changes.size))
            val unchanged = plan.entries.count { it.status == RenameStatus.UNCHANGED }
            if (unchanged > 0) add(pluralStringResource(R.plurals.explorer_rename_unchanged, unchanged, unchanged))
            if (plan.conflicts > 0) add(pluralStringResource(R.plurals.explorer_rename_conflicts, plan.conflicts, plan.conflicts))
        }.joinToString(separator())
    }
    val bad = plan != null && (plan.error != null || plan.conflicts > 0)
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = if (bad) error else MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun PreviewRow(p: RenamePreview) {
    val conflict = p.status in setOf(RenameStatus.INVALID, RenameStatus.DUPLICATE, RenameStatus.EXISTS)
    val color: Color = when {
        conflict -> MaterialTheme.colorScheme.error
        p.status == RenameStatus.UNCHANGED -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.primary
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                p.item.name,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.padding(horizontal = 6.dp))
            Text(
                p.newName,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                style = MaterialTheme.typography.bodyMedium,
                color = color,
                modifier = Modifier.weight(1f),
            )
        }
        if (p.message != null) Text(p.message, style = MaterialTheme.typography.bodySmall, color = color)
    }
}
