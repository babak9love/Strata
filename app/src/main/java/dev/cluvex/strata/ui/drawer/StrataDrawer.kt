package dev.cluvex.strata.ui.drawer

import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.EnhancedEncryption
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.res.stringResource
import dev.cluvex.strata.R
import dev.cluvex.strata.core.vault.Vault
import dev.cluvex.strata.core.vault.VaultStore
import android.os.StatFs
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutBack
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.ButtonShapes
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import com.materialkolor.hct.Hct
import com.materialkolor.ktx.toColor
import com.materialkolor.ktx.toHct
import com.materialkolor.temperature.TemperatureCache
import dev.cluvex.strata.core.bookmarks.Bookmark
import dev.cluvex.strata.core.model.MotionStyle
import dev.cluvex.strata.core.remote.RemoteConnection
import dev.cluvex.strata.core.util.Formatters
import dev.cluvex.strata.ui.expressive.MorphShape
import dev.cluvex.strata.ui.expressive.rememberPolygonMorph
import dev.cluvex.strata.ui.theme.LocalAppearance
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

class DrawerActions(
    val onShortcut: (String) -> Unit,
    val onBookmark: (Bookmark) -> Unit,
    val onRecycleBin: () -> Unit,
    val onApps: () -> Unit,
    val onNetwork: () -> Unit,
    val onStorage: (RemoteConnection) -> Unit,
    val onLanguages: () -> Unit,
    val onSettings: () -> Unit,
    val onShare: () -> Unit,
    val onColorPicker: () -> Unit,
    val onSigningKeys: () -> Unit,
    val onPlugins: () -> Unit,
    val onVault: () -> Unit = {},
)

private class Place(@StringRes val label: Int, val icon: ImageVector, val folder: String, val shape: RoundedPolygon)

private class DrawerLink(val label: String, val icon: ImageVector, val onClick: () -> Unit)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun StrataDrawerContent(
    root: String,
    rootMode: Boolean,
    bookmarks: List<Bookmark>,
    storages: List<RemoteConnection>,
    open: Boolean,
    closed: Boolean,
    actions: DrawerActions,
) {
    val reduced = LocalAppearance.current.motion == MotionStyle.REDUCED
    val entrance = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(open, closed, reduced) {
        when {
            reduced -> entrance.snapTo(1f)
            open -> entrance.animateTo(1f, tween(900, easing = LinearEasing))
            closed -> entrance.snapTo(0f)
        }
    }
    var section = 0
    fun Modifier.stagger(): Modifier {
        val index = section++
        return graphicsLayer {
            val local = ((entrance.value - index * 0.04f) / 0.4f).coerceIn(0f, 1f)
            val eased = EaseOutBack.transform(local)
            alpha = local
            translationY = (1f - eased) * 28.dp.toPx()
            val s = 0.94f + 0.06f * eased
            scaleX = s
            scaleY = s
        }
    }

    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = 18.dp, bottom = 20.dp)
            .testTag("strata-drawer"),
    ) {
        Brand(reduced, Modifier.stagger())
        StorageStrata(root, !closed, reduced, Modifier.stagger().padding(top = 16.dp)) { actions.onShortcut(root) }

        SectionTitle(stringResource(R.string.drawer_places), Modifier.stagger())
        val places = remember(root) {
            listOf(
                Place(R.string.drawer_downloads, Icons.Rounded.Download, "$root/Download", MaterialShapes.Arrow),
                Place(R.string.drawer_images, Icons.Rounded.Image, "$root/Pictures", MaterialShapes.Flower),
                Place(R.string.drawer_camera, Icons.Rounded.CameraAlt, "$root/DCIM", MaterialShapes.Cookie12Sided),
                Place(R.string.drawer_movies, Icons.Rounded.Movie, "$root/Movies", MaterialShapes.Clover8Leaf),
                Place(R.string.drawer_music, Icons.Rounded.Audiotrack, "$root/Music", MaterialShapes.SoftBurst),
                Place(R.string.drawer_documents, Icons.Rounded.Description, "$root/Documents", MaterialShapes.Bun),
            )
        }
        val palette = rememberPlacePalette(places.size)
        places.chunked(3).forEachIndexed { row, three ->
            Row(Modifier.fillMaxWidth().stagger().padding(top = if (row == 0) 0.dp else 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                three.forEachIndexed { i, place ->
                    val colors = palette[row * 3 + i]
                    PlaceTile(place, colors.first, colors.second, reduced, Modifier.weight(1f)) { actions.onShortcut(place.folder) }
                }
            }
        }

        if (bookmarks.isNotEmpty()) {
            SectionTitle(stringResource(R.string.common_bookmarks), Modifier.stagger())
            LazyRow(
                modifier = Modifier.fillMaxWidth().stagger(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(end = 8.dp),
            ) {
                items(bookmarks, key = { it.path }) { b -> BookmarkChip(b) { actions.onBookmark(b) } }
            }
        }

        SectionTitle(stringResource(R.string.drawer_system), Modifier.stagger())
        val rootLabel = stringResource(R.string.drawer_root)
        val dataLabel = stringResource(R.string.drawer_data)
        val systemLabel = stringResource(R.string.drawer_system_folder)
        DrawerGroup(
            buildList {
                add(DrawerLink(rootLabel, Icons.Rounded.Folder) { actions.onShortcut("/") })
                if (rootMode) {
                    add(DrawerLink(dataLabel, Icons.Rounded.Storage) { actions.onShortcut("/data") })
                    add(DrawerLink(systemLabel, Icons.Rounded.Memory) { actions.onShortcut("/system") })
                }
            },
            MaterialShapes.Cookie6Sided,
            Modifier.stagger(),
        )

        SectionTitle(stringResource(R.string.remote_title), Modifier.stagger())
        val manageLabel = stringResource(R.string.drawer_manage_storages)
        val sharingLabel = stringResource(R.string.drawer_wifi_sharing)
        DrawerGroup(
            buildList {
                add(DrawerLink(manageLabel, Icons.Rounded.Cloud, actions.onNetwork))
                storages.forEach { c -> add(DrawerLink(c.name.ifBlank { c.host }, Icons.Rounded.CloudQueue) { actions.onStorage(c) }) }
                add(DrawerLink(sharingLabel, Icons.Rounded.Wifi, actions.onShare))
            },
            MaterialShapes.Clover4Leaf,
            Modifier.stagger(),
        )

        SectionTitle(stringResource(R.string.common_tools), Modifier.stagger())
        DrawerGroup(
            listOf(
                DrawerLink(stringResource(R.string.drawer_languages), Icons.Rounded.Terminal, actions.onLanguages),
                DrawerLink(stringResource(R.string.apps_title), Icons.Rounded.Apps, actions.onApps),
                DrawerLink(stringResource(R.string.color_picker_title), Icons.Rounded.Palette, actions.onColorPicker),
                DrawerLink(stringResource(R.string.keys_title), Icons.Rounded.Key, actions.onSigningKeys),
                DrawerLink(stringResource(R.string.plugin_title), Icons.Rounded.Extension, actions.onPlugins),
            ),
            MaterialShapes.Cookie9Sided,
            Modifier.stagger(),
        )

        VaultCard(actions.onVault, Modifier.stagger().padding(top = 22.dp))

        Row(
            Modifier.fillMaxWidth().stagger().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
            FooterButton(stringResource(R.string.common_recycle_bin), Icons.Rounded.DeleteSweep, leading = true, modifier = Modifier.weight(1f), onClick = actions.onRecycleBin)
            FooterButton(stringResource(R.string.common_settings), Icons.Rounded.Settings, leading = false, modifier = Modifier.weight(1f), onClick = actions.onSettings)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Brand(reduced: Boolean, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        StrataMark(reduced, Modifier.size(40.dp))
        Column(Modifier.padding(start = 12.dp)) {
            Text("Strata", style = MaterialTheme.typography.headlineSmallEmphasized, color = colors.onSurface)
            Text(stringResource(R.string.drawer_tagline), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun StrataMark(reduced: Boolean, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val layers = listOf(colors.secondary, colors.tertiary, colors.primary)
    val drift = if (reduced) {
        0f
    } else {
        val t = rememberInfiniteTransition()
        t.animateFloat(0f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing), RepeatMode.Reverse)).value
    }
    Canvas(modifier) {
        val h = size.height / 4.2f
        val gap = (size.height - 3 * h) / 2f
        layers.forEachIndexed { i, c ->
            val shift = sin((drift + i * 0.33f) * 2f * PI.toFloat()) * size.width * 0.07f
            val inset = size.width * (0.16f - i * 0.05f)
            drawRoundRect(
                color = c,
                topLeft = Offset(inset + shift, i * (h + gap)),
                size = Size(size.width - inset * 2f, h),
                cornerRadius = CornerRadius(h / 2f),
            )
        }
    }
}

/**
 * Free / total bytes for [path]. For the primary internal storage on API 26+ this asks the system
 * (StorageStatsManager), the same source Android Settings and Samsung My Files use, so the total is the
 * device capacity rather than just the user-data partition. Everything else falls back to StatFs.
 */
private fun storageUsage(context: android.content.Context, path: String): Pair<Long, Long> {
    val st = StatFs(path)
    val internal = runCatching { android.os.Environment.getExternalStorageDirectory().path }.getOrNull()
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && internal != null && path.startsWith(internal)) {
        runCatching {
            val stats = context.getSystemService(android.app.usage.StorageStatsManager::class.java)
            val total = stats.getTotalBytes(android.os.storage.StorageManager.UUID_DEFAULT)
            val free = stats.getFreeBytes(android.os.storage.StorageManager.UUID_DEFAULT)
            if (total > 0) return free.coerceAtMost(total) to total
        }
    }
    return st.availableBytes to st.totalBytes
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun StorageStrata(path: String, open: Boolean, reduced: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val context = androidx.compose.ui.platform.LocalContext.current
    val usage = remember(path) { runCatching { storageUsage(context, path) }.getOrNull() }
    val used = usage?.let { (free, total) -> if (total > 0) (1f - free.toFloat() / total).coerceIn(0f, 1f) else 0f } ?: 0f
    val fill by animateFloatAsState(if (open || reduced) used else 0f, if (reduced) snap() else spring(dampingRatio = 0.62f, stiffness = 90f))
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(30.dp),
        color = colors.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth().testTag("drawer-storage"),
    ) {
        Column(Modifier.padding(start = 18.dp, end = 14.dp, top = 16.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.model_start_home), style = MaterialTheme.typography.titleMediumEmphasized)
                    if (usage != null && usage.second > 0) {
                        Text(
                            stringResource(R.string.drawer_free_of, Formatters.size(usage.first), Formatters.size(usage.second)),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
                Box(
                    Modifier.size(48.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(colors.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.ui_percent, (used * 100).toInt()), style = MaterialTheme.typography.labelLarge, color = colors.onPrimaryContainer)
                }
            }
            StrataGauge(fill, reduced, Modifier.fillMaxWidth().padding(top = 14.dp).height(30.dp))
        }
    }
}

@Composable
private fun StrataGauge(fill: Float, reduced: Boolean, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val phase = if (reduced) {
        0f
    } else {
        val t = rememberInfiniteTransition()
        t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(5200, easing = LinearEasing))).value
    }
    val track = colors.surfaceContainerHighest
    val bands = listOf(colors.secondaryContainer, colors.tertiary, colors.primary)
    Canvas(modifier.clip(CircleShape)) {
        drawRect(track)
        if (fill <= 0.001f) return@Canvas
        scale(if (layoutDirection == LayoutDirection.Rtl) -1f else 1f, 1f) {
            drawStrata(fill, phase, bands)
        }
    }
}

private fun DrawScope.drawStrata(fill: Float, phase: Float, bands: List<Color>) {
    val w = size.width
    val h = size.height
    val front = Path().apply {
        moveTo(0f, 0f)
        val step = 3f
        var y = 0f
        val amp = h * 0.16f
        while (y <= h) {
            lineTo(w * fill + amp * sin(y / h * 2f * PI.toFloat() * 1.3f + phase * 1.6f), y)
            y += step
        }
        lineTo(w * fill, h)
        lineTo(0f, h)
        close()
    }
    clipPath(front) {
        drawRect(bands[0])
        listOf(0.34f to bands[1], 0.66f to bands[2]).forEachIndexed { i, (level, color) ->
            val band = Path().apply {
                moveTo(0f, h)
                var x = 0f
                while (x <= w) {
                    lineTo(x, h * level + h * 0.1f * sin(x / w * 2f * PI.toFloat() * (2f + i) + phase * (1f + i * 0.5f)))
                    x += 4f
                }
                lineTo(w, h)
                close()
            }
            drawPath(band, color)
        }
    }
}

@Composable
private fun rememberPlacePalette(count: Int): List<Pair<Color, Color>> {
    val colors = MaterialTheme.colorScheme
    val dark = colors.background.luminance() < 0.5f
    val seed = colors.primary
    return remember(seed, dark, count) {
        val hues = runCatching { TemperatureCache(seed.toHct()).getAnalogousColors(count, 9) }.getOrNull()
            ?.map { it.hue }
            ?.takeIf { it.size == count }
            ?: List(count) { (seed.toHct().hue + it * 360.0 / count) % 360.0 }
        hues.map { hue ->
            val container = Hct.from(hue, 42.0, if (dark) 30.0 else 90.0).toColor()
            val content = Hct.from(hue, 48.0, if (dark) 90.0 else 25.0).toColor()
            container to content
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PlaceTile(place: Place, container: Color, content: Color, reduced: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(
        if (pressed) 1f else 0f,
        if (reduced) snap() else spring(dampingRatio = 0.45f, stiffness = 520f),
    )
    val morph = rememberPolygonMorph(place.shape, MaterialShapes.Circle)
    Column(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(58.dp)
                .graphicsLayer {
                    val s = 1f - 0.08f * press
                    scaleX = s
                    scaleY = s
                    rotationZ = 18f * press
                }
                .clip(MorphShape(morph, press))
                .background(container),
            contentAlignment = Alignment.Center,
        ) {
            Icon(place.icon, contentDescription = null, tint = content, modifier = Modifier.size(26.dp).graphicsLayer { rotationZ = -18f * press })
        }
        Text(
            stringResource(place.label),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BookmarkChip(bookmark: Bookmark, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = onClick, shape = CircleShape, color = colors.surfaceContainerHigh) {
        Row(Modifier.padding(start = 6.dp, end = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(32.dp).clip(MaterialShapes.Cookie4Sided.toShape()).background(colors.tertiaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    bookmark.name.take(1).uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onTertiaryContainer,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(bookmark.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SectionTitle(text: String, modifier: Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmallEmphasized,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 8.dp, top = 22.dp, bottom = 10.dp).semantics { heading() },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun DrawerGroup(links: List<DrawerLink>, shape: RoundedPolygon, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        links.forEachIndexed { i, link ->
            SegmentedListItem(
                onClick = link.onClick,
                shapes = ListItemDefaults.segmentedShapes(i, links.size),
                colors = ListItemDefaults.segmentedColors(containerColor = colors.surfaceBright),
                leadingContent = {
                    Box(
                        Modifier.size(36.dp).clip(shape.toShape()).background(colors.secondaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(link.icon, contentDescription = null, tint = colors.onSecondaryContainer, modifier = Modifier.size(20.dp))
                    }
                },
            ) {
                Text(link.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun VaultCard(onClick: () -> Unit, modifier: Modifier) {
    val state by remember { if (Vault.ready) Vault.store.state else kotlinx.coroutines.flow.MutableStateFlow(VaultStore.State.NONE) }.collectAsState()
    val open = state == VaultStore.State.UNLOCKED
    val turn by animateFloatAsState(if (open) 1f else 0f, MaterialTheme.motionScheme.defaultSpatialSpec())
    val shape = remember { RoundedCornerShape(28.dp) }
    Surface(
        onClick = onClick,
        shape = shape,
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = modifier.fillMaxWidth().testTag("drawer-vault"),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(44.dp)
                    .graphicsLayer { rotationZ = turn * 45f }
                    .clip(MaterialShapes.Cookie7Sided.toShape())
                    .background(MaterialTheme.colorScheme.tertiary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (open) Icons.Rounded.LockOpen else Icons.Rounded.EnhancedEncryption,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onTertiary,
                    modifier = Modifier.size(22.dp).graphicsLayer { rotationZ = -turn * 45f },
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.vault_drawer), style = MaterialTheme.typography.titleMediumEmphasized)
                Text(
                    stringResource(
                        when (state) {
                            VaultStore.State.NONE -> R.string.vault_drawer_none
                            VaultStore.State.LOCKED -> R.string.vault_status_locked
                            VaultStore.State.UNLOCKED -> R.string.vault_status_open
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.8f),
                )
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FooterButton(label: String, icon: ImageVector, leading: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val outer = 28.dp
    val inner = 8.dp
    val shape = if (leading) {
        RoundedCornerShape(topStart = outer, bottomStart = outer, topEnd = inner, bottomEnd = inner)
    } else {
        RoundedCornerShape(topStart = inner, bottomStart = inner, topEnd = outer, bottomEnd = outer)
    }
    FilledTonalButton(
        onClick = onClick,
        shapes = ButtonShapes(shape = shape, pressedShape = RoundedCornerShape(16.dp)),
        modifier = modifier.heightIn(min = 60.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
