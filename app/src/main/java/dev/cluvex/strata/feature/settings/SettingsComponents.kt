package dev.cluvex.strata.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import kotlin.math.roundToInt
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemColors
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.ListItemShapes
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.cluvex.strata.R

enum class Tint { PRIMARY, SECONDARY, TERTIARY, ERROR, NEUTRAL }

@Composable
private fun Tint.colors(): Pair<Color, Color> {
    val c = MaterialTheme.colorScheme
    return when (this) {
        Tint.PRIMARY -> c.primaryContainer to c.onPrimaryContainer
        Tint.SECONDARY -> c.secondaryContainer to c.onSecondaryContainer
        Tint.TERTIARY -> c.tertiaryContainer to c.onTertiaryContainer
        Tint.ERROR -> c.errorContainer to c.onErrorContainer
        Tint.NEUTRAL -> c.surfaceContainerHighest to c.onSurfaceVariant
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingIcon(icon: ImageVector, tint: Tint = Tint.SECONDARY, size: Int = 40) {
    val (container, content) = tint.colors()
    Box(
        Modifier.size(size.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size((size * 0.55f).dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsPageScaffold(
    title: String,
    onBack: () -> Unit,
    subtitle: String? = null,
    snackbar: SnackbarHostState? = null,
    actions: @Composable RowScope.() -> Unit = {},
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val behavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val background = MaterialTheme.colorScheme.surfaceContainer
    Scaffold(
        modifier = Modifier.nestedScroll(behavior.nestedScrollConnection).testTag("settings-page"),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                subtitle = subtitle?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back)) }
                },
                actions = actions,
                scrollBehavior = behavior,
                colors = TopAppBarDefaults.topAppBarColors(containerColor = background, scrolledContainerColor = background),
            )
        },
        snackbarHost = { if (snackbar != null) SnackbarHost(snackbar) },
        containerColor = background,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(start = 16.dp, end = 16.dp, bottom = 32.dp),
            content = content,
        )
    }
}

class SettingsGroupScope {
    internal val items = mutableListOf<@Composable (ListItemShapes) -> Unit>()

    fun item(content: @Composable (ListItemShapes) -> Unit) {
        items += content
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsGroup(title: String? = null, footer: String? = null, content: SettingsGroupScope.() -> Unit) {
    val scope = SettingsGroupScope().apply(content)
    if (scope.items.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 10.dp).semantics { heading() },
            )
        } else {
            Box(Modifier.padding(top = 16.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            scope.items.forEachIndexed { i, item -> item(ListItemDefaults.segmentedShapes(i, scope.items.size)) }
        }
        if (footer != null) {
            Text(
                footer,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun settingColors(): ListItemColors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceBright)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun SettingsGroupScope.switch(
    title: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    summary: String? = null,
    icon: ImageVector? = null,
    tint: Tint = Tint.SECONDARY,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) = item { shapes ->
    SegmentedListItem(
        checked = checked,
        onCheckedChange = onChange,
        shapes = shapes,
        enabled = enabled,
        colors = settingColors(),
        leadingContent = leading ?: icon?.let { { SettingIcon(it, tint) } },
        supportingContent = summary?.let { { Text(it) } },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
                thumbContent = if (checked) {
                    { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize)) }
                } else {
                    null
                },
            )
        },
    ) { Text(title) }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun SettingsGroupScope.link(
    title: String,
    summary: String? = null,
    icon: ImageVector? = null,
    tint: Tint = Tint.SECONDARY,
    enabled: Boolean = true,
    chevron: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) = item { shapes ->
    SegmentedListItem(
        onClick = onClick,
        shapes = shapes,
        enabled = enabled,
        colors = settingColors(),
        leadingContent = icon?.let { { SettingIcon(it, tint) } },
        supportingContent = summary?.let { { Text(it) } },
        trailingContent = trailing ?: if (chevron) {
            { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) }
        } else {
            null
        },
    ) { Text(title) }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
fun SettingsGroupScope.custom(
    title: String? = null,
    summary: String? = null,
    icon: ImageVector? = null,
    tint: Tint = Tint.SECONDARY,
    body: @Composable ColumnScope.() -> Unit,
) = item { shapes ->
    SegmentedListItem(
        shapes = shapes,
        colors = settingColors(),
        leadingContent = icon?.let { { SettingIcon(it, tint) } },
        supportingContent = {
            Column {
                if (summary != null) Text(summary)
                Column(Modifier.padding(top = if (title != null || summary != null) 10.dp else 0.dp), content = body)
            }
        },
    ) { if (title != null) Text(title) }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun <T> ConnectedChoice(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ((T) -> ImageVector)? = null,
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { i, option ->
            val shapes = when (i) {
                0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
            }
            ToggleButton(
                checked = option == selected,
                onCheckedChange = { onSelect(option) },
                enabled = enabled,
                shapes = shapes,
                modifier = Modifier.weight(1f).semantics { role = Role.RadioButton },
            ) {
                if (icon != null && option == selected) {
                    Icon(icon(option), contentDescription = null, modifier = Modifier.size(18.dp).padding(end = 2.dp))
                }
                Text(label(option), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

fun <T> SettingsGroupScope.choice(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    icon: ImageVector? = null,
    tint: Tint = Tint.SECONDARY,
    summary: String? = null,
    enabled: Boolean = true,
) = custom(title = title, summary = summary, icon = icon, tint = tint) {
    ConnectedChoice(options, selected, label, onSelect, enabled = enabled)
}

fun <T> SettingsGroupScope.pick(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    icon: ImageVector? = null,
    tint: Tint = Tint.SECONDARY,
    enabled: Boolean = true,
    detail: ((T) -> String?)? = null,
) = item { shapes ->
    var open by remember { mutableStateOf(false) }
    PickItem(title, label(selected), icon, tint, enabled, shapes) { open = true }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    options.forEach { option ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(MaterialTheme.shapes.medium)
                                .selectable(option == selected, role = Role.RadioButton) { onSelect(option); open = false }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = option == selected, onClick = null)
                            Column(Modifier.padding(start = 12.dp)) {
                                Text(label(option), style = MaterialTheme.typography.bodyLarge)
                                detail?.invoke(option)?.let {
                                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.common_close)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PickItem(title: String, value: String, icon: ImageVector?, tint: Tint, enabled: Boolean, shapes: ListItemShapes, onClick: () -> Unit) {
    SegmentedListItem(
        onClick = onClick,
        shapes = shapes,
        enabled = enabled,
        colors = settingColors(),
        leadingContent = icon?.let { { SettingIcon(it, tint) } },
        supportingContent = { Text(value) },
        trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null) },
    ) { Text(title) }
}

fun SettingsGroupScope.slider(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueLabel: (Float) -> String,
    onChange: (Float) -> Unit,
    icon: ImageVector? = null,
    tint: Tint = Tint.SECONDARY,
    preview: (@Composable (Float) -> Unit)? = null,
) = custom(title = title, icon = icon, tint = tint) {
    var live by remember(value) { mutableFloatStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Slider(
            value = live,
            onValueChange = { live = it },
            onValueChangeFinished = { onChange(live) },
            valueRange = range,
            steps = steps,
            modifier = Modifier.weight(1f).testTag("setting-slider"),
        )
        Text(valueLabel(live), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 12.dp))
    }
    preview?.invoke(live)
}

/**
 * Whole-number percent slider: drag for a quick change, or tap the number to type an exact value in [min]..[max].
 */
fun SettingsGroupScope.percentSlider(
    title: String,
    value: Int,
    min: Int,
    max: Int,
    onChange: (Int) -> Unit,
    icon: ImageVector? = null,
    tint: Tint = Tint.SECONDARY,
) = custom(title = title, icon = icon, tint = tint) {
    var live by remember(value) { mutableFloatStateOf(value.toFloat()) }
    var editing by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Slider(
            value = live,
            onValueChange = { live = it },
            onValueChangeFinished = {
                val v = live.roundToInt().coerceIn(min, max)
                live = v.toFloat()
                onChange(v)
            },
            valueRange = min.toFloat()..max.toFloat(),
            steps = 0,
            modifier = Modifier.weight(1f).testTag("setting-slider"),
        )
        Text(
            "${live.roundToInt()}%",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(start = 8.dp)
                .clip(MaterialTheme.shapes.small)
                .clickable { editing = true }
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .testTag("setting-slider-value"),
        )
    }
    if (editing) {
        var text by remember { mutableStateOf(live.roundToInt().toString()) }
        // Accept any script's digits (e.g. Persian/Arabic keyboards) as well as ASCII.
        val number = text.map { c -> if (Character.isDigit(c)) Character.digit(c, 10).digitToChar() else c }.joinToString("").toIntOrNull()
        val valid = number != null && number in min..max
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(title) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(4) },
                    singleLine = true,
                    isError = !valid,
                    suffix = { Text("%") },
                    supportingText = { Text("$min – $max") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = valid,
                    onClick = {
                        val v = number!!.coerceIn(min, max)
                        live = v.toFloat()
                        onChange(v)
                        editing = false
                    },
                ) { Text(stringResource(R.string.common_ok)) }
            },
            dismissButton = { TextButton(onClick = { editing = false }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
}

@Composable
fun SettingNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
