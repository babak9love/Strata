package dev.cluvex.strata.feature.settings

import dev.cluvex.strata.core.i18n.separator
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.i18n.trPlural
import dev.cluvex.strata.core.util.Formatters
import dev.cluvex.strata.core.data.StrataSettings
import dev.cluvex.strata.core.model.ActionLayout
import dev.cluvex.strata.core.model.BrowseAction
import dev.cluvex.strata.core.model.SelectionAction
import dev.cluvex.strata.core.model.SheetAction
import dev.cluvex.strata.core.model.SheetGroup
import dev.cluvex.strata.core.model.Zoned
import dev.cluvex.strata.core.model.hint
import dev.cluvex.strata.ui.icon
import kotlinx.coroutines.launch

enum class ToolbarTab(@StringRes val label: Int) {
    BROWSE(R.string.settings_tab_bottom_bar),
    SELECTION(R.string.settings_tab_selection),
    SHEET(R.string.settings_tab_long_press),
}

private fun joinNames(names: List<String>): String = names.reduce { a, b -> tr(R.string.settings_toolbar_and, a, b) }

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ToolbarPage(settings: StrataSettings, act: SettingsActions, onBack: () -> Unit, startTab: ToolbarTab = ToolbarTab.BROWSE) {
    var tab by rememberSaveable { mutableStateOf(startTab) }
    val scroll = rememberScrollState()
    var layout by remember { mutableStateOf(settings.actions) }
    var pending by remember { mutableStateOf<ActionLayout?>(null) }
    LaunchedEffect(settings.actions) {
        val waiting = pending
        if (waiting == null || waiting == settings.actions) {
            layout = settings.actions
            pending = null
        }
    }
    fun commit(next: ActionLayout) {
        layout = next
        pending = next
        act.actions { next }
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun spilled(names: List<String>, @PluralsRes message: Int, slots: Int) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(trPlural(message, slots, slots, joinNames(names)))
        }
    }
    SettingsPageScaffold(stringResource(R.string.settings_page_toolbar), onBack, subtitle = stringResource(R.string.settings_toolbar_subtitle), snackbar = snackbar, scrollState = scroll) {
        ConnectedChoice(
            options = ToolbarTab.entries,
            selected = tab,
            label = { tr(it.label) },
            onSelect = { tab = it },
            modifier = Modifier.padding(top = 8.dp).testTag("toolbar-tabs"),
        )
        AnimatedContent(
            targetState = tab,
            transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.96f)).togetherWith(fadeOut() + scaleOut(targetScale = 0.96f)) },
        ) { current ->
            Column {
                when (current) {
                    ToolbarTab.BROWSE -> {
                        // Actions that are switched off (undo, or swapping panes with a single pane) are not offered here at all.
                        val hiddenActions = buildSet {
                            if (!settings.operations.undoEnabled) add(BrowseAction.UNDO)
                            if (!settings.dualPanePreferred) add(BrowseAction.SWAP)
                        }
                        val browseShown = if (hiddenActions.isEmpty()) {
                            layout.browse
                        } else {
                            dev.cluvex.strata.core.model.Zoned(layout.browse.zones.map { zone -> zone.filter { it !in hiddenActions } })
                        }
                        var preview by remember(browseShown) { mutableStateOf(browseShown) }
                        BarPreview(preview.zones[0].map { PreviewButton(it.icon, stringResource(it.label), it.hint != null) }, selecting = false)
                        SettingNote(pluralStringResource(R.plurals.settings_toolbar_bar_note, ActionLayout.BROWSE_SLOTS, ActionLayout.BROWSE_SLOTS))
                        ZoneEditor(
                            zoned = browseShown,
                            caps = ActionLayout.BROWSE_CAPS,
                            styles = barStyles(sheet = false),
                            buttons = { zone -> zoneButtons(zone, sheet = false) },
                            label = { tr(it.label) },
                            icon = { it.icon },
                            scroll = scroll,
                            onPreview = { preview = it },
                            onChange = { commit(layout.copy(browse = it)) },
                            hint = { it.hint?.let { h -> tr(h) } },
                            onSpill = { list -> spilled(list.map { tr(it.label) }, R.plurals.settings_toolbar_spill_bar, ActionLayout.BROWSE_SLOTS) },
                        )
                        ResetButton { commit(layout.copy(browse = ActionLayout.DEFAULT_BROWSE)) }
                    }
                    ToolbarTab.SELECTION -> {
                        var preview by remember { mutableStateOf(layout.selection) }
                        BarPreview(preview.zones[0].map { PreviewButton(it.icon, stringResource(it.label), it.hint != null) }, selecting = true)
                        SettingNote(pluralStringResource(R.plurals.settings_toolbar_selection_note, ActionLayout.SELECTION_SLOTS, ActionLayout.SELECTION_SLOTS))
                        ZoneEditor(
                            zoned = layout.selection,
                            caps = ActionLayout.SELECTION_CAPS,
                            styles = barStyles(sheet = false),
                            buttons = { zone -> zoneButtons(zone, sheet = false) },
                            label = { tr(it.label) },
                            icon = { it.icon },
                            scroll = scroll,
                            onPreview = { preview = it },
                            onChange = { commit(layout.copy(selection = it)) },
                            hint = { it.hint?.let { h -> tr(h) } },
                            onSpill = { list -> spilled(list.map { tr(it.label) }, R.plurals.settings_toolbar_spill_bar, ActionLayout.SELECTION_SLOTS) },
                        )
                        ResetButton { commit(layout.copy(selection = ActionLayout.DEFAULT_SELECTION)) }
                    }
                    ToolbarTab.SHEET -> {
                        var preview by remember { mutableStateOf(layout.sheet) }
                        SheetPreview(preview)
                        SettingNote(stringResource(R.string.settings_toolbar_sheet_note))
                        ZoneEditor(
                            zoned = layout.sheet,
                            caps = ActionLayout.SHEET_CAPS,
                            styles = barStyles(sheet = true),
                            buttons = { zone -> zoneButtons(zone, sheet = true) },
                            label = { tr(it.label) },
                            icon = { it.icon },
                            scroll = scroll,
                            onPreview = { preview = it },
                            onChange = { commit(layout.copy(sheet = it)) },
                            hint = { it.hint?.let { h -> tr(h) } },
                            onSpill = { list -> spilled(list.map { tr(it.label) }, R.plurals.settings_toolbar_spill_quick, ActionLayout.QUICK_SLOTS) },
                        )
                        ResetButton { commit(layout.copy(sheet = ActionLayout.DEFAULT_SHEET)) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
private fun barStyles(sheet: Boolean) = listOf(
    ZoneStyle(
        tr(if (sheet) R.string.settings_zone_quick else R.string.settings_zone_in_bar),
        tr(R.string.settings_zone_drop_here),
        MaterialShapes.Cookie9Sided,
        Tint.PRIMARY,
    ),
    ZoneStyle(
        tr(if (sheet) R.string.settings_zone_in_menu else R.string.settings_zone_in_more),
        tr(R.string.settings_zone_nothing),
        MaterialShapes.Square,
        Tint.SECONDARY,
    ),
    ZoneStyle(tr(R.string.settings_zone_hidden), tr(R.string.settings_zone_hidden_empty), MaterialShapes.Ghostish, Tint.NEUTRAL),
)

private fun zoneButtons(zone: Int, sheet: Boolean): List<ZoneButton> = when (zone) {
    0 -> listOf(
        ZoneButton(Icons.Rounded.RemoveCircleOutline, { tr(if (sheet) R.string.settings_zone_move_to_menu else R.string.settings_zone_move_to_more, it) }, zone = 1, atEnd = false),
    )
    1 -> listOf(
        ZoneButton(Icons.Rounded.AddCircleOutline, { tr(if (sheet) R.string.settings_zone_add_to_quick else R.string.settings_zone_add_to_bar, it) }, zone = 0, atEnd = true),
        ZoneButton(Icons.Rounded.VisibilityOff, { tr(R.string.settings_zone_hide, it) }, zone = 2, atEnd = true),
    )
    else -> listOf(
        ZoneButton(Icons.Rounded.Visibility, { tr(if (sheet) R.string.settings_zone_show_in_menu else R.string.settings_zone_show_in_more, it) }, zone = 1, atEnd = true),
    )
}

@Composable
private fun ResetButton(onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
        Icon(Icons.Rounded.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.settings_toolbar_reset))
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BarPreview(buttons: List<PreviewButton>, selecting: Boolean) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(32.dp),
        color = colors.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp).testTag("toolbar-preview"),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 12.dp)) {
            Text(
                stringResource(R.string.settings_toolbar_preview),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(start = 10.dp, bottom = 8.dp),
            )
            val clearLabel = stringResource(R.string.common_clear_selection)
            val moreLabel = stringResource(R.string.common_more)
            Surface(shape = CircleShape, color = colors.surfaceContainer, modifier = Modifier.fillMaxWidth().height(64.dp)) {
                val items = buildList {
                    if (selecting) {
                        add(PreviewItem("close", Icons.Rounded.Close, clearLabel))
                        add(PreviewItem("count", null, Formatters.number(3)))
                    }
                    buttons.forEach { b -> add(PreviewItem(b.label, b.icon, b.label, b.conditional)) }
                    add(PreviewItem("more", Icons.Rounded.MoreVert, moreLabel))
                }
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                    userScrollEnabled = false,
                ) {
                    items(items, key = { it.key }) { p ->
                        val description = stringResource(R.string.settings_toolbar_preview_item, p.label)
                        Box(
                            Modifier
                                .animateItem()
                                .size(44.dp)
                                .graphicsLayer { alpha = if (p.conditional) 0.4f else 1f }
                                .semantics { contentDescription = description },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (p.icon != null) {
                                Icon(p.icon, contentDescription = null, tint = colors.onSurface)
                            } else {
                                Text(p.label, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
            }
        }
    }
}

private class PreviewItem(val key: String, val icon: ImageVector?, val label: String, val conditional: Boolean = false)

class PreviewButton(val icon: ImageVector, val label: String, val conditional: Boolean)

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
private fun SheetPreview(sheet: Zoned<SheetAction>) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp, bottomStart = 20.dp, bottomEnd = 20.dp),
        color = colors.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp).testTag("toolbar-preview"),
    ) {
        Column(Modifier.padding(16.dp)) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(width = 32.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(colors.outlineVariant),
            )
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).clip(MaterialShapes.Cookie6Sided.toShape()).background(colors.tertiaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Description, contentDescription = null, tint = colors.onTertiaryContainer)
                }
                Column(Modifier.padding(start = 12.dp)) {
                    Text(stringResource(R.string.settings_toolbar_sample_file), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.unit_kib, Formatters.number(12)) + separator() + stringResource(R.string.settings_toolbar_today),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            val quick = sheet.zones[0]
            AnimatedContent(targetState = quick.isEmpty()) { empty ->
                if (empty) {
                    Text(
                        stringResource(R.string.settings_toolbar_no_quick),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                        textAlign = TextAlign.Center,
                    )
                } else {
                    LazyRow(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        userScrollEnabled = false,
                    ) {
                        items(quick, key = { it.name }) { a ->
                            Surface(
                                shape = RoundedCornerShape(18.dp),
                                color = colors.primaryContainer,
                                modifier = Modifier.animateItem().width(76.dp).height(76.dp),
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                    Icon(a.icon, contentDescription = null, tint = colors.onPrimaryContainer, modifier = Modifier.size(20.dp))
                                    Text(
                                        stringResource(a.label),
                                        style = MaterialTheme.typography.labelSmall,
                                        textAlign = TextAlign.Center,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            FlowRow(
                Modifier.padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SheetGroup.entries.forEach { g ->
                    val n = sheet.zones[1].count { it.group == g }
                    PreviewChip(stringResource(g.title), n)
                }
                val hidden = sheet.zones[2].size
                if (hidden > 0) PreviewChip(stringResource(R.string.settings_zone_hidden), hidden)
            }
        }
    }
}

@Composable
private fun PreviewChip(label: String, count: Int) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = CircleShape, color = colors.surfaceContainerHighest) {
        Text(
            "$label · ${Formatters.number(count.toLong())}",
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}
