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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
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
import androidx.compose.ui.semantics.Role
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
import dev.cluvex.strata.core.model.SizeUnits
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
    val onClose: () -> Unit = {},
)

private class Place(val id: String, @StringRes val label: Int, val icon: ImageVector, val folder: String, val shape: RoundedPolygon)

private class DrawerLink(val id: String, val label: String, val icon: ImageVector, val onClick: () -> Unit)

// Stable ids of the items (and sections) the user can hide from the side menu. They are stored in settings,
// so they must not depend on the language. Bookmarks and saved storages use a prefix plus their own id.
private const val SECTION_PLACES = "section_places"
private const val SECTION_BOOKMARKS = "section_bookmarks"
private const val SECTION_SYSTEM = "section_system"
private const val SECTION_REMOTE = "section_remote"
private const val SECTION_TOOLS = "section_tools"
private const val ID_VAULT = "vault"
private const val ID_RECYCLE = "footer_recycle"

private fun bookmarkId(b: Bookmark) = "bm:" + b.path

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
    /** Ids of the items and sections the user chose to hide. */
    hidden: Set<String> = emptySet(),
    onHiddenChange: (Set<String>) -> Unit = {},
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
    // Edit mode (the pencil): items show a minus / plus badge and a tap hides or shows them instead of opening them.
    var editing by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(closed) { if (closed) editing = false }
    fun toggle(id: String) = onHiddenChange(if (id in hidden) hidden - id else hidden + id)

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
        Brand(reduced, editing, { editing = !editing }, Modifier.stagger(), actions.onClose)
        if (editing) EditBar { onHiddenChange(emptySet()) }
        StorageStrata(root, !closed, reduced, Modifier.stagger().padding(top = 16.dp)) { actions.onShortcut(root) }

        val places = remember(root) {
            listOf(
                Place("place_downloads", R.string.drawer_downloads, Icons.Rounded.Download, "$root/Download", MaterialShapes.Arrow),
                Place("place_images", R.string.drawer_images, Icons.Rounded.Image, "$root/Pictures", MaterialShapes.Flower),
                Place("place_camera", R.string.drawer_camera, Icons.Rounded.CameraAlt, "$root/DCIM", MaterialShapes.Cookie12Sided),
                Place("place_movies", R.string.drawer_movies, Icons.Rounded.Movie, "$root/Movies", MaterialShapes.Clover8Leaf),
                Place("place_music", R.string.drawer_music, Icons.Rounded.Audiotrack, "$root/Music", MaterialShapes.SoftBurst),
                Place("place_documents", R.string.drawer_documents, Icons.Rounded.Description, "$root/Documents", MaterialShapes.Bun),
            )
        }
        val palette = rememberPlacePalette(places.size)
        val placesHidden = SECTION_PLACES in hidden
        // Keep each place's own colour (its index in the full list) even when others are hidden.
        val shownPlaces = places.withIndex().filter { editing || it.value.id !in hidden }
        if (editing || (!placesHidden && shownPlaces.isNotEmpty())) {
            EditableTitle(stringResource(R.string.drawer_places), placesHidden, editing, { toggle(SECTION_PLACES) }, Modifier.stagger())
            if (!placesHidden) {
                shownPlaces.chunked(3).forEachIndexed { row, three ->
                    Row(Modifier.fillMaxWidth().stagger().padding(top = if (row == 0) 0.dp else 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        three.forEach { (index, place) ->
                            val colors = palette[index]
                            Editable(editing, place.id in hidden, { toggle(place.id) }, Modifier.weight(1f)) {
                                PlaceTile(place, colors.first, colors.second, reduced, Modifier.fillMaxWidth()) { actions.onShortcut(place.folder) }
                            }
                        }
                        repeat(3 - three.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }

        val bookmarksHidden = SECTION_BOOKMARKS in hidden
        val shownBookmarks = bookmarks.filter { editing || bookmarkId(it) !in hidden }
        if (bookmarks.isNotEmpty() && (editing || (!bookmarksHidden && shownBookmarks.isNotEmpty()))) {
            EditableTitle(stringResource(R.string.common_bookmarks), bookmarksHidden, editing, { toggle(SECTION_BOOKMARKS) }, Modifier.stagger())
            if (!bookmarksHidden) {
                LazyRow(
                    modifier = Modifier.fillMaxWidth().stagger(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    // Room for the badges that stick out of the chips' corner while editing.
                    contentPadding = PaddingValues(top = if (editing) 8.dp else 0.dp, end = if (editing) 14.dp else 8.dp),
                ) {
                    items(shownBookmarks, key = { it.path }) { b ->
                        Editable(
                            editing, bookmarkId(b) in hidden, { toggle(bookmarkId(b)) },
                            badgeOffset = DpOffset(6.dp, (-6).dp), badgePadding = 0.dp,
                        ) { BookmarkChip(b) { actions.onBookmark(b) } }
                    }
                }
            }
        }

        val rootLabel = stringResource(R.string.drawer_root)
        val dataLabel = stringResource(R.string.drawer_data)
        val systemLabel = stringResource(R.string.drawer_system_folder)
        LinkSection(
            stringResource(R.string.drawer_system), SECTION_SYSTEM,
            buildList {
                add(DrawerLink("sys_root", rootLabel, Icons.Rounded.Folder) { actions.onShortcut("/") })
                if (rootMode) {
                    add(DrawerLink("sys_data", dataLabel, Icons.Rounded.Storage) { actions.onShortcut("/data") })
                    add(DrawerLink("sys_system", systemLabel, Icons.Rounded.Memory) { actions.onShortcut("/system") })
                }
            },
            MaterialShapes.Cookie6Sided, editing, hidden, ::toggle, Modifier.stagger(), Modifier.stagger(),
        )

        val manageLabel = stringResource(R.string.drawer_manage_storages)
        val sharingLabel = stringResource(R.string.drawer_wifi_sharing)
        LinkSection(
            stringResource(R.string.remote_title), SECTION_REMOTE,
            buildList {
                add(DrawerLink("remote_manage", manageLabel, Icons.Rounded.Cloud, actions.onNetwork))
                storages.forEach { c -> add(DrawerLink("remote:" + c.id, c.name.ifBlank { c.host }, Icons.Rounded.CloudQueue) { actions.onStorage(c) }) }
                add(DrawerLink("remote_wifi", sharingLabel, Icons.Rounded.Wifi, actions.onShare))
            },
            MaterialShapes.Clover4Leaf, editing, hidden, ::toggle, Modifier.stagger(), Modifier.stagger(),
        )

        LinkSection(
            stringResource(R.string.common_tools), SECTION_TOOLS,
            listOf(
                DrawerLink("tool_languages", stringResource(R.string.drawer_languages), Icons.Rounded.Terminal, actions.onLanguages),
                DrawerLink("tool_apps", stringResource(R.string.apps_title), Icons.Rounded.Apps, actions.onApps),
                DrawerLink("tool_colors", stringResource(R.string.color_picker_title), Icons.Rounded.Palette, actions.onColorPicker),
                DrawerLink("tool_keys", stringResource(R.string.keys_title), Icons.Rounded.Key, actions.onSigningKeys),
                DrawerLink("tool_plugins", stringResource(R.string.plugin_title), Icons.Rounded.Extension, actions.onPlugins),
            ),
            MaterialShapes.Cookie9Sided, editing, hidden, ::toggle, Modifier.stagger(), Modifier.stagger(),
        )

        val vaultShown = editing || ID_VAULT !in hidden
        if (vaultShown) {
            Editable(editing, ID_VAULT in hidden, { toggle(ID_VAULT) }, Modifier.fillMaxWidth().stagger().padding(top = 22.dp), badgePadding = 8.dp) {
                VaultCard(actions.onVault, Modifier)
            }
        }

        // Settings can never be hidden, so there is always a way back into the app's options.
        val recycleShown = editing || ID_RECYCLE !in hidden
        Row(
            Modifier.fillMaxWidth().stagger().padding(top = if (vaultShown) 10.dp else 22.dp),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
        ) {
            if (recycleShown) {
                Editable(editing, ID_RECYCLE in hidden, { toggle(ID_RECYCLE) }, Modifier.weight(1f), badgePadding = 6.dp) {
                    FooterButton(stringResource(R.string.common_recycle_bin), Icons.Rounded.DeleteSweep, leading = true, single = false, modifier = Modifier.fillMaxWidth(), onClick = actions.onRecycleBin)
                }
            }
            FooterButton(stringResource(R.string.common_settings), Icons.Rounded.Settings, leading = false, single = !recycleShown, modifier = Modifier.weight(1f), onClick = actions.onSettings)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Brand(reduced: Boolean, editing: Boolean, onEdit: () -> Unit, modifier: Modifier, onClose: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // Tapping the logo acts like Back: it closes the drawer and returns to the file list.
        Row(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(20.dp))
                .clickable(onClickLabel = stringResource(R.string.common_back), role = Role.Button, onClick = onClose)
                .padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StrataMark(reduced, Modifier.size(40.dp))
            Column(Modifier.padding(start = 12.dp)) {
                Text("Strata", style = MaterialTheme.typography.headlineSmallEmphasized, color = colors.onSurface)
                Text(stringResource(R.string.drawer_tagline), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            }
        }
        // The pencil turns the menu into edit mode; in edit mode it becomes a tick that finishes editing.
        if (editing) {
            FilledTonalIconButton(onClick = onEdit, modifier = Modifier.testTag("drawer-edit")) {
                Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.common_done))
            }
        } else {
            IconButton(onClick = onEdit, modifier = Modifier.testTag("drawer-edit")) {
                Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.drawer_edit))
            }
        }
    }
}

@Composable
private fun EditBar(onReset: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 8.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.drawer_edit_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onReset) {
            Icon(Icons.Rounded.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.drawer_edit_reset))
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
                            // The device capacity is a binary figure (64 GiB, not 68.72 GB), so this line stays binary whatever the units setting says.
                            stringResource(R.string.drawer_free_of, Formatters.size(usage.first, SizeUnits.BINARY), Formatters.size(usage.second, SizeUnits.BINARY)),
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
private fun DrawerGroup(
    links: List<DrawerLink>,
    shape: RoundedPolygon,
    editing: Boolean,
    hidden: Set<String>,
    onToggle: (String) -> Unit,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    // Hidden links are left out entirely, so the rounded corners are computed over the visible ones only.
    val shown = links.filter { editing || it.id !in hidden }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        shown.forEachIndexed { i, link ->
            Editable(editing, link.id in hidden, { onToggle(link.id) }, Modifier.fillMaxWidth(), Alignment.CenterEnd, badgePadding = 14.dp) {
                SegmentedListItem(
                    onClick = link.onClick,
                    shapes = ListItemDefaults.segmentedShapes(i, shown.size),
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
}

/** A titled group of links. Gone when the section is hidden or all its links are hidden (unless editing). */
@Composable
private fun LinkSection(
    title: String,
    sectionId: String,
    links: List<DrawerLink>,
    shape: RoundedPolygon,
    editing: Boolean,
    hidden: Set<String>,
    onToggle: (String) -> Unit,
    titleModifier: Modifier,
    groupModifier: Modifier,
) {
    val sectionHidden = sectionId in hidden
    if (!editing && (sectionHidden || links.all { it.id in hidden })) return
    EditableTitle(title, sectionHidden, editing, { onToggle(sectionId) }, titleModifier)
    if (!sectionHidden) DrawerGroup(links, shape, editing, hidden, onToggle, groupModifier)
}

@Composable
private fun EditableTitle(text: String, hidden: Boolean, editing: Boolean, onToggle: () -> Unit, modifier: Modifier) {
    Editable(editing, hidden, onToggle, modifier.fillMaxWidth(), Alignment.CenterEnd, badgePadding = 8.dp) {
        SectionTitle(text, Modifier)
    }
}

/**
 * Wraps one drawer item. Outside edit mode a hidden item is not drawn at all. In edit mode every item is drawn
 * (a hidden one faded), a tap anywhere on it hides or shows it instead of opening it, and a small minus / plus
 * badge says what the tap will do.
 */
@Composable
private fun Editable(
    editing: Boolean,
    hidden: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    badgeAlignment: Alignment = Alignment.TopEnd,
    badgePadding: Dp = 4.dp,
    badgeOffset: DpOffset = DpOffset.Zero,
    content: @Composable () -> Unit,
) {
    if (!editing) {
        if (!hidden) Box(modifier) { content() }
        return
    }
    val colors = MaterialTheme.colorScheme
    Box(modifier) {
        Box(Modifier.graphicsLayer { alpha = if (hidden) 0.4f else 1f }) { content() }
        // Sits above the item so its own click never fires while editing.
        Box(
            Modifier
                .matchParentSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClickLabel = stringResource(if (hidden) R.string.drawer_show else R.string.drawer_hide),
                    role = Role.Button,
                    onClick = onToggle,
                ),
        )
        Box(
            Modifier
                .align(badgeAlignment)
                .offset(badgeOffset.x, badgeOffset.y)
                .padding(badgePadding)
                .size(22.dp)
                .clip(CircleShape)
                .background(if (hidden) colors.primary else colors.errorContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (hidden) Icons.Rounded.Add else Icons.Rounded.Remove,
                contentDescription = null,
                tint = if (hidden) colors.onPrimary else colors.onErrorContainer,
                modifier = Modifier.size(14.dp),
            )
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
private fun FooterButton(label: String, icon: ImageVector, leading: Boolean, single: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val outer = 28.dp
    val inner = 8.dp
    val shape = if (single) {
        RoundedCornerShape(outer)
    } else if (leading) {
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
