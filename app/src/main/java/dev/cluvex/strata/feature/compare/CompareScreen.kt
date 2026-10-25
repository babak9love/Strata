package dev.cluvex.strata.feature.compare

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Difference
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.cluvex.strata.R
import dev.cluvex.strata.core.compare.ArchiveEntryDiff
import dev.cluvex.strata.core.compare.DiffLine
import dev.cluvex.strata.core.compare.DiffOp
import dev.cluvex.strata.core.compare.EntryDiff
import dev.cluvex.strata.core.util.Formatters

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CompareScreen(
    leftPath: String,
    rightPath: String,
    rootEnabled: Boolean,
    onBack: () -> Unit,
    vm: CompareViewModel = viewModel(),
) {
    val s by vm.state.collectAsStateWithLifecycle()
    vm.start(leftPath, rightPath, rootEnabled)
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.compare_title), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${s.leftName}  ↔  ${s.rightName}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back)) }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
            when {
                s.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { LoadingIndicator() }
                s.error != null -> Box(Modifier.fillMaxSize().padding(24.dp), Alignment.Center) {
                    Text(s.error!!, color = MaterialTheme.colorScheme.error)
                }
                else -> when (val r = s.result) {
                    is CompareResult.Text -> TextDiffView(r)
                    is CompareResult.Archive -> ArchiveDiffView(r)
                    is CompareResult.Binary -> BinaryView(r)
                    null -> Unit
                }
            }
        }
    }
}

@Composable
private fun TextDiffView(r: CompareResult.Text) {
    Column(Modifier.fillMaxSize()) {
        SummaryRow(
            identical = r.stats.identical,
            identicalLabel = stringResource(if (r.exact) R.string.compare_files_identical else R.string.compare_same_lines),
            chips = listOf(
                "+${r.stats.added}" to addedColor(),
                "−${r.stats.removed}" to removedColor(),
            ),
        )
        HorizontalDivider()
        val hScroll = rememberScrollState()
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            LazyColumn(Modifier.fillMaxSize().testTag("diff_lines")) {
                items(r.lines) { line -> DiffLineRow(line, hScroll) }
            }
        }
    }
}

@Composable
private fun DiffLineRow(line: DiffLine, hScroll: androidx.compose.foundation.ScrollState) {
    val bg = when (line.op) {
        DiffOp.ADDED -> addedColor().copy(alpha = 0.18f)
        DiffOp.REMOVED -> removedColor().copy(alpha = 0.18f)
        DiffOp.SAME -> Color.Transparent
    }
    val marker = when (line.op) {
        DiffOp.ADDED -> "+"
        DiffOp.REMOVED -> "−"
        DiffOp.SAME -> " "
    }
    Row(Modifier.fillMaxWidth().background(bg)) {
        Text(
            (line.leftNo?.toString() ?: "").padStart(5),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Text(
            (line.rightNo?.toString() ?: "").padStart(5),
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 4.dp),
        )
        Text(
            "$marker ${line.text}",
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            maxLines = 1,
            modifier = Modifier.weight(1f).horizontalScroll(hScroll).padding(end = 8.dp),
        )
    }
}

@Composable
private fun ArchiveDiffView(r: CompareResult.Archive) {
    val changed = r.entries.filter { it.diff != EntryDiff.SAME }
    val added = changed.count { it.diff == EntryDiff.ADDED }
    val removed = changed.count { it.diff == EntryDiff.REMOVED }
    val modified = changed.count { it.diff == EntryDiff.CHANGED }
    Column(Modifier.fillMaxSize()) {
        SummaryRow(
            identical = r.identical,
            identicalLabel = stringResource(R.string.compare_archives_identical),
            chips = listOf(
                "+$added" to addedColor(),
                "~$modified" to changedColor(),
                "−$removed" to removedColor(),
            ),
        )
        HorizontalDivider()
        LazyColumn(Modifier.fillMaxSize().testTag("archive_diff")) {
            items(changed) { e -> ArchiveEntryRow(e) }
        }
    }
}

@Composable
private fun ArchiveEntryRow(e: ArchiveEntryDiff) {
    val (marker, color) = when (e.diff) {
        EntryDiff.ADDED -> "+" to addedColor()
        EntryDiff.REMOVED -> "−" to removedColor()
        EntryDiff.CHANGED -> "~" to changedColor()
        EntryDiff.SAME -> " " to MaterialTheme.colorScheme.onSurface
    }
    val detail = when (e.diff) {
        EntryDiff.ADDED -> Formatters.size(e.rightSize)
        EntryDiff.REMOVED -> Formatters.size(e.leftSize)
        else -> stringResource(R.string.compare_size_change, Formatters.size(e.leftSize), Formatters.size(e.rightSize))
    }
    Row(
        Modifier.fillMaxWidth().background(color.copy(alpha = 0.10f)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(marker, fontFamily = FontFamily.Monospace, color = color, modifier = Modifier.width(16.dp))
        Text(
            e.path,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BinaryView(r: CompareResult.Binary) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SummaryRow(
            identical = r.identical,
            identicalLabel = stringResource(R.string.compare_files_identical),
            chips = if (r.identical) emptyList() else listOf(stringResource(R.string.compare_different) to removedColor()),
            padded = false,
        )
        HashBlock(stringResource(R.string.compare_left), r.leftSha, r.leftSize)
        HashBlock(stringResource(R.string.compare_right), r.rightSha, r.rightSize)
        if (!r.identical && r.leftSize == r.rightSize) {
            Text(
                stringResource(R.string.compare_same_size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HashBlock(label: String, sha: String, size: Long) {
    Column {
        Text("$label · ${Formatters.size(size)}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text("SHA-256", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(sha, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
}

@Composable
private fun SummaryRow(
    identical: Boolean,
    identicalLabel: String,
    chips: List<Pair<String, Color>>,
    padded: Boolean = true,
) {
    Row(
        Modifier.fillMaxWidth().then(if (padded) Modifier.padding(12.dp) else Modifier),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (identical) {
            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = addedColor())
            Text(identicalLabel, style = MaterialTheme.typography.titleSmall)
        } else {
            Icon(Icons.Rounded.Difference, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            chips.forEach { (label, color) ->
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text(label, fontFamily = FontFamily.Monospace, style = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr)) },
                    colors = AssistChipDefaults.assistChipColors(
                        disabledLabelColor = color,
                        disabledContainerColor = color.copy(alpha = 0.12f),
                    ),
                )
            }
        }
        Spacer(Modifier.weight(1f))
    }
}

@Composable private fun addedColor() = if (isDark()) Color(0xFF7EE787) else Color(0xFF1A7F37)
@Composable private fun removedColor() = if (isDark()) Color(0xFFFF7B72) else Color(0xFFCF222E)
@Composable private fun changedColor() = if (isDark()) Color(0xFFE3B341) else Color(0xFF9A6700)

@Composable private fun isDark() = MaterialTheme.colorScheme.surface.luminance() < 0.5f
private fun Color.luminance(): Float = 0.299f * red + 0.587f * green + 0.114f * blue
