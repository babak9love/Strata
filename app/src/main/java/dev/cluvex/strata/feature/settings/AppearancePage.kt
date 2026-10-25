package dev.cluvex.strata.feature.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.RoundedCorner
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.cluvex.strata.feature.explorer.components.rememberFolderTint
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.data.StrataSettings
import dev.cluvex.strata.core.model.AppearancePrefs
import dev.cluvex.strata.core.model.ContrastLevel
import dev.cluvex.strata.core.model.CornerStyle
import dev.cluvex.strata.core.model.FolderStyle
import dev.cluvex.strata.core.model.IconShape
import dev.cluvex.strata.core.model.MotionStyle
import dev.cluvex.strata.core.model.ThemeMode
import dev.cluvex.strata.core.model.ThemeSeeds
import dev.cluvex.strata.core.model.ThemeStyle
import dev.cluvex.strata.core.util.FileKind
import dev.cluvex.strata.core.util.Formatters
import dev.cluvex.strata.feature.color.ColorPickerDialog
import dev.cluvex.strata.ui.theme.KindPalette
import dev.cluvex.strata.ui.theme.StrataColors
import dev.cluvex.strata.ui.theme.toShape
import kotlin.math.roundToInt

@Composable
fun AppearancePage(settings: StrataSettings, act: SettingsActions, onBack: () -> Unit) {
    val a = settings.appearance
    val wallpaper = a.wallpaperColors && StrataColors.wallpaperAvailable
    var customColor by remember { mutableStateOf(false) }
    SettingsPageScaffold(stringResource(R.string.settings_page_appearance), onBack, subtitle = stringResource(R.string.settings_appearance_subtitle)) {
        ThemePreview(settings)
        SettingsGroup(stringResource(R.string.settings_group_theme)) {
            choice(tr(R.string.settings_mode), ThemeMode.entries, a.themeMode, { tr(it.label) }, { m -> act.appearance { it.copy(themeMode = m) } }, icon = Icons.Rounded.DarkMode)
            switch(
                tr(R.string.settings_pure_black),
                a.pureBlack,
                { v -> act.appearance { it.copy(pureBlack = v) } },
                summary = tr(R.string.settings_pure_black_summary),
                icon = Icons.Rounded.Contrast,
                enabled = a.themeMode != ThemeMode.LIGHT,
            )
        }
        SettingsGroup(stringResource(R.string.settings_group_colors)) {
            switch(
                tr(R.string.settings_wallpaper_colors),
                wallpaper,
                { v -> act.appearance { it.copy(wallpaperColors = v) } },
                summary = if (StrataColors.wallpaperAvailable) tr(R.string.settings_wallpaper_summary) else tr(R.string.settings_wallpaper_unavailable, 12),
                icon = Icons.Rounded.Wallpaper,
                enabled = StrataColors.wallpaperAvailable,
            )
            custom(
                title = tr(R.string.settings_theme_color),
                summary = if (wallpaper) tr(R.string.settings_theme_color_wallpaper) else ThemeSeeds.named(a.seed)?.let { tr(it) } ?: tr(R.string.settings_custom_color),
                icon = Icons.Rounded.Palette,
            ) {
                SeedSwatches(a, wallpaper, onSeed = { seed -> act.appearance { it.copy(seed = seed, wallpaperColors = false) } }, onCustom = { customColor = true })
            }
            custom(title = tr(R.string.settings_palette_style), summary = tr(a.style.label)) {
                StyleChips(a, wallpaper) { style -> act.appearance { it.copy(style = style) } }
            }
            choice(tr(R.string.settings_contrast), ContrastLevel.entries, a.contrast, { tr(it.label) }, { c -> act.appearance { it.copy(contrast = c) } })
        }
        SettingsGroup(stringResource(R.string.settings_group_text_shapes)) {
            slider(
                tr(R.string.settings_text_size),
                a.textScale,
                AppearancePrefs.MIN_TEXT_SCALE..AppearancePrefs.MAX_TEXT_SCALE,
                steps = 8,
                valueLabel = { tr(R.string.ui_percent, (it * 100).roundToInt()) },
                onChange = { v -> act.appearance { it.copy(textScale = AppearancePrefs.textScale(v)) } },
                icon = Icons.Rounded.FormatSize,
                preview = { live ->
                    Text(
                        stringResource(R.string.settings_text_size_sample),
                        fontSize = (16f * live / a.textScale).sp,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                },
            )
            custom(title = tr(R.string.settings_corners), summary = tr(R.string.settings_corners_summary), icon = Icons.Rounded.RoundedCorner) {
                ConnectedChoice(CornerStyle.entries, a.corners, { tr(it.short) }, { c -> act.appearance { it.copy(corners = c) } })
            }
        }
        val folderTint = rememberFolderTint()
        SettingsGroup(stringResource(R.string.settings_group_icons)) {
            switch(
                tr(R.string.settings_strata_file_icons),
                a.customFileIcons,
                { v -> act.appearance { it.copy(customFileIcons = v) } },
                summary = tr(R.string.settings_strata_file_icons_summary),
            )
            custom(title = tr(R.string.settings_icon_shape), summary = tr(a.iconShape.label)) {
                IconShapes(a) { shape -> act.appearance { it.copy(iconShape = shape) } }
            }
            switch(
                tr(R.string.settings_colorful_icons),
                a.colorfulIcons,
                { v -> act.appearance { it.copy(colorfulIcons = v) } },
                summary = tr(R.string.settings_colorful_icons_summary),
            )
        }
        SettingsGroup(stringResource(R.string.settings_group_folder_icons)) {
            switch(
                tr(R.string.settings_folder_shaped),
                a.folderShaped,
                { v -> act.appearance { it.copy(folderShaped = v) } },
                summary = tr(R.string.settings_folder_shaped_summary),
                leading = {
                    // Preview of the folder, tinted like the real ones when "Fixed folder color" is off.
                    androidx.compose.foundation.Image(
                        painter = androidx.compose.ui.res.painterResource(a.folderStyle.drawable),
                        contentDescription = null,
                        colorFilter = if (!a.folderFixedColor) folderTint else null,
                        modifier = Modifier.size(width = 40.dp, height = 31.dp),
                    )
                },
            )
            switch(
                tr(R.string.settings_folder_fixed_color),
                a.folderFixedColor,
                { v -> act.appearance { it.copy(folderFixedColor = v) } },
                summary = tr(R.string.settings_folder_fixed_color_summary),
                enabled = a.folderShaped,
            )
            if (a.folderShaped) {
                custom {
                    FolderStyles(a, folderTint) { style -> act.appearance { it.copy(folderStyle = style) } }
                }
            }
        }
        SettingsGroup(stringResource(R.string.settings_group_motion)) {
            choice(tr(R.string.settings_animations), MotionStyle.entries, a.motion, { tr(it.label) }, { m -> act.appearance { it.copy(motion = m) } }, icon = Icons.Rounded.Animation)
            switch(tr(R.string.settings_haptics), a.haptics, { v -> act.appearance { it.copy(haptics = v) } }, summary = tr(R.string.settings_haptics_summary), icon = Icons.Rounded.Vibration)
            switch(tr(R.string.settings_sounds), a.sounds, { v -> act.appearance { it.copy(sounds = v) } }, summary = tr(R.string.settings_sounds_summary), icon = Icons.Rounded.GraphicEq)
            if (a.sounds) {
                switch(
                    tr(R.string.settings_sounds_silent),
                    a.soundsInSilent,
                    { v -> act.appearance { it.copy(soundsInSilent = v) } },
                    summary = tr(R.string.settings_sounds_silent_summary),
                    icon = Icons.AutoMirrored.Rounded.VolumeUp,
                )
            }
        }
    }
    if (customColor) {
        ColorPickerDialog(
            initial = a.seed,
            title = stringResource(R.string.settings_theme_color),
            onPick = { picked ->
                customColor = false
                act.appearance { it.copy(seed = picked or (0xFF shl 24), wallpaperColors = false) }
            },
            onDismiss = { customColor = false },
        )
    }
}

@Composable
private fun darkNow(a: AppearancePrefs): Boolean = when (a.themeMode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SeedSwatches(a: AppearancePrefs, wallpaper: Boolean, onSeed: (Int) -> Unit, onCustom: () -> Unit) {
    val dark = darkNow(a)
    val custom = ThemeSeeds.named(a.seed) == null
    val customLabel = stringResource(R.string.settings_custom_color)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ThemeSeeds.presets.forEach { seed ->
            val scheme = remember(seed.argb, a.style, a.contrast, dark) { StrataColors.generate(seed.argb, a.style, a.contrast, dark) }
            Swatch(
                name = stringResource(R.string.settings_swatch, stringResource(seed.name)),
                selected = !wallpaper && a.seed == seed.argb,
                colors = listOf(scheme.primary, scheme.secondaryContainer, scheme.tertiary),
                onClick = { onSeed(seed.argb) },
            )
        }
        val scheme = remember(a.seed, a.style, a.contrast, dark) { StrataColors.generate(a.seed, a.style, a.contrast, dark) }
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(
                    if (custom && !wallpaper) Brush.linearGradient(listOf(scheme.primary, scheme.tertiary))
                    else Brush.sweepGradient(listOf(Color(0xFFE53935), Color(0xFFFDD835), Color(0xFF43A047), Color(0xFF1E88E5), Color(0xFF8E24AA), Color(0xFFE53935))),
                )
                .selectable(custom && !wallpaper, role = Role.RadioButton, onClick = onCustom)
                .semantics { contentDescription = customLabel },
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.size(26.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
                Icon(if (custom && !wallpaper) Icons.Rounded.Check else Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun Swatch(name: String, selected: Boolean, colors: List<Color>, onClick: () -> Unit) {
    val outline = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .then(if (selected) Modifier.border(3.dp, outline, CircleShape) else Modifier)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = name },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(if (selected) 38.dp else 48.dp)) {
            val r = size.minDimension
            drawArc(colors[0], 180f, 180f, useCenter = true, size = Size(r, r))
            drawArc(colors[1], 90f, 90f, useCenter = true, size = Size(r, r))
            drawArc(colors[2], 0f, 90f, useCenter = true, size = Size(r, r))
        }
        if (selected) {
            Box(Modifier.size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Check, contentDescription = null, tint = outline, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StyleChips(a: AppearancePrefs, wallpaper: Boolean, onStyle: (ThemeStyle) -> Unit) {
    val dark = darkNow(a)
    val seed = if (wallpaper) MaterialTheme.colorScheme.primary.toArgb() else a.seed
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ThemeStyle.entries.forEach { style ->
            val scheme = remember(seed, style, a.contrast, dark) { StrataColors.generate(seed, style, a.contrast, dark) }
            FilterChip(
                selected = a.style == style,
                onClick = { onStyle(style) },
                label = { Text(stringResource(style.label)) },
                leadingIcon = {
                    Row(horizontalArrangement = Arrangement.spacedBy((-4).dp)) {
                        listOf(scheme.primary, scheme.secondary, scheme.tertiary).forEach { c ->
                            Box(Modifier.size(12.dp).clip(CircleShape).background(c).border(1.dp, MaterialTheme.colorScheme.surface, CircleShape))
                        }
                    }
                },
            )
        }
    }
}

private const val PREVIEW_ROOT = "/storage/emulated/0"
private const val PREVIEW_FOLDER = "Download"
private const val PREVIEW_APK = "Strata.apk"

@Composable
fun ThemePreview(settings: StrataSettings) {
    val colors = MaterialTheme.colorScheme
    val a = settings.appearance
    val dark = darkNow(a)
    val now = remember { System.currentTimeMillis() }
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("theme-preview"),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = colors.surface),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.clip(MaterialTheme.shapes.small).background(colors.secondaryContainer).padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(PREVIEW_ROOT, style = MaterialTheme.typography.labelMedium, color = colors.onSecondaryContainer, maxLines = 1)
                }
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(28.dp).clip(CircleShape).background(colors.primary).testTag("preview-primary"))
            }
            Spacer(Modifier.size(12.dp))
            val rows = listOf(
                Triple(PREVIEW_FOLDER, FileKind.FOLDER, -1L),
                Triple(stringResource(R.string.settings_preview_photo), FileKind.IMAGE, 2_400_000L),
                Triple(PREVIEW_APK, FileKind.APK, 21_000_000L),
                Triple(stringResource(R.string.settings_preview_notes), FileKind.TEXT, 3_200L),
            )
            rows.forEachIndexed { i, (name, kind, size) ->
                val tile = KindPalette.colors(kind, a.colorfulIcons, colors, dark)
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(36.dp).clip(a.iconShape.toShape()).background(tile.container),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(kind.icon, contentDescription = null, tint = tile.content, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            Formatters.displayName(name, kind == FileKind.FOLDER, settings.list.showExtensions),
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val meta = listOfNotNull(
                            if (settings.list.showDate) Formatters.listDate(now - i * 3_600_000L * 7, settings.list.dateStyle, settings.list.use24Hour, now) else null,
                            if (settings.list.showSize && size >= 0) Formatters.size(size, settings.list.sizeUnits) else null,
                        ).joinToString("  ·  ")
                        if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.size(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {}) { Text(stringResource(R.string.settings_preview_paste)) }
                Spacer(Modifier.weight(1f))
                Switch(checked = true, onCheckedChange = null)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IconShapes(a: AppearancePrefs, onShape: (IconShape) -> Unit) {
    val colors = MaterialTheme.colorScheme
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        IconShape.entries.forEach { shape ->
            val selected = a.iconShape == shape
            val description = stringResource(R.string.settings_icon_shape_option, stringResource(shape.label))
            Box(
                Modifier
                    .size(52.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(if (selected) colors.secondaryContainer else Color.Transparent)
                    .selectable(selected, role = Role.RadioButton) { onShape(shape) }
                    .semantics { contentDescription = description },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.size(36.dp).clip(shape.toShape()).background(colors.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(FileKind.FOLDER.icon, contentDescription = null, tint = colors.onPrimaryContainer, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/** Picker for the folder artwork: the Strata folder plus the three alternatives. Follows "Fixed folder color" like the real folders do. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FolderStyles(a: AppearancePrefs, folderTint: androidx.compose.ui.graphics.ColorFilter, onStyle: (FolderStyle) -> Unit) {
    val colors = MaterialTheme.colorScheme
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        maxItemsInEachRow = 4,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        FolderStyle.entries.forEach { style ->
            val selected = a.folderStyle == style
            val description = stringResource(style.label)
            Box(
                Modifier
                    .weight(1f)
                    .height(60.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(if (selected) colors.secondaryContainer else Color.Transparent)
                    .border(1.dp, if (selected) colors.primary else colors.outlineVariant, MaterialTheme.shapes.medium)
                    .selectable(selected, role = Role.RadioButton) { onStyle(style) }
                    .semantics { contentDescription = description },
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(style.drawable),
                    contentDescription = null,
                    colorFilter = if (!a.folderFixedColor) folderTint else null,
                    modifier = Modifier.size(width = 44.dp, height = 34.dp),
                )
            }
        }
        // Fill the last row so its tiles keep the same width as the others.
        repeat((4 - FolderStyle.entries.size % 4) % 4) { androidx.compose.foundation.layout.Spacer(Modifier.weight(1f)) }
    }
}
