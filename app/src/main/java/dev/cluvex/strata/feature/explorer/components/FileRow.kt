package dev.cluvex.strata.feature.explorer.components

import dev.cluvex.strata.core.media.FolderBadgeStore
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.decode.BitmapFactoryDecoder
import coil3.request.ImageRequest
import dev.cluvex.strata.R
import dev.cluvex.strata.core.media.ApkIcon
import dev.cluvex.strata.core.media.FolderBadge
import dev.cluvex.strata.core.model.FileItem
import java.io.File
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import dev.cluvex.strata.core.model.ListPrefs
import dev.cluvex.strata.core.util.FileKind
import dev.cluvex.strata.core.util.FileKinds
import dev.cluvex.strata.core.util.Formatters
import dev.cluvex.strata.feature.explorer.dnd.DropHalo
import dev.cluvex.strata.feature.explorer.dnd.DropLook
import dev.cluvex.strata.feature.explorer.dnd.dropHaloBehind
import dev.cluvex.strata.feature.explorer.dnd.fileGestures
import dev.cluvex.strata.feature.explorer.dnd.rememberDropAnimation
import dev.cluvex.strata.ui.LocalSettings
import dev.cluvex.strata.ui.LocalUiDensity
import dev.cluvex.strata.ui.theme.KindPalette
import dev.cluvex.strata.ui.theme.toShape
import okio.Path.Companion.toPath

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
/** Paths currently waiting in a Cut (move) clipboard entry; their rows are drawn dimmed until the move is pasted or dropped. */
val LocalCutPaths = androidx.compose.runtime.compositionLocalOf<Set<String>> { emptySet() }

private const val CUT_ALPHA = 0.45f

@Composable
fun FileRow(
    item: FileItem,
    selected: Boolean,
    flashKey: Int,
    showThumbnail: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onIconClick: () -> Unit,
    modifier: Modifier = Modifier,
    flashPulses: Int = FLASH_PULSES,
    drop: DropLook = DropLook.NONE,
    onDragStart: ((Offset, PointerId) -> Boolean)? = null,
) {
    val motion = MaterialTheme.motionScheme
    val colors = MaterialTheme.colorScheme
    val density = LocalUiDensity.current
    val settings = LocalSettings.current
    val list = settings.list
    val iconSize = (density.iconDp * ((if (item.isDirectory) list.folderScale else list.itemScale) / 100f)).dp
    val nameScale = list.nameScale / 100f
    val metaScale = list.metaScale / 100f
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, motion.fastSpatialSpec(), label = "press")
    val look = rememberDropAnimation(drop)
    val background by animateColorAsState(
        when {
            drop.hovered -> colors.primaryContainer.copy(alpha = 0.6f)
            selected -> colors.secondaryContainer
            else -> Color.Transparent
        },
        motion.defaultEffectsSpec(),
        label = "rowBg",
    )
    val ghostAlpha by animateFloatAsState(if (drop.ghosted) 0.36f else 1f, motion.defaultEffectsSpec(), label = "rowGhost")
    val cutAlpha by animateFloatAsState(if (item.path in LocalCutPaths.current) CUT_ALPHA else 1f, motion.defaultEffectsSpec(), label = "rowCut")
    val flash = rememberFlash(flashKey, flashPulses)
    val flashColor = colors.primary.copy(alpha = 0.32f)

    Row(
        modifier
            .then(drop.spot)
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = ghostAlpha * cutAlpha
            }
            .clip(MaterialTheme.shapes.medium)
            .background(background)
            .drawBehind {
                val a = flash.value
                if (a > 0f) drawRect(flashColor, alpha = a)
            }
            .fileGestures(interaction, onClick, onLongClick, onDragStart)
            .padding(horizontal = 6.dp, vertical = density.rowPadDp.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DropHalo(look, drop.anchor) {
            Leading(item, selected, showThumbnail, iconSize, if (settings.behavior.iconTapSelects) onIconClick else null)
        }
        Spacer(Modifier.width(if (density == dev.cluvex.strata.core.model.UiDensity.COMPACT) 10.dp else 12.dp))
        Column(Modifier.weight(1f)) {
            FileNameText(
                name = Formatters.displayName(item.name, item.isDirectory, list.showExtensions),
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = (density.nameSp * nameScale).sp),
                maxLines = if (list.wrapNames) 3 else density.nameMaxLines,
            )
            val date = if (list.showDate) Formatters.listDate(item.lastModified, list.dateStyle, list.use24Hour) else null
            val info = trailingInfo(item, list)
            if (date != null || info != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        date.orEmpty(),
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = (density.metaSp * metaScale).sp,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (info != null) {
                        Text(
                            info,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = (density.metaSp * metaScale).sp,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FileTile(
    item: FileItem,
    selected: Boolean,
    selecting: Boolean,
    flashKey: Int,
    showThumbnail: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onIconClick: () -> Unit,
    modifier: Modifier = Modifier,
    flashPulses: Int = FLASH_PULSES,
    drop: DropLook = DropLook.NONE,
    onDragStart: ((Offset, PointerId) -> Boolean)? = null,
) {
    val motion = MaterialTheme.motionScheme
    val colors = MaterialTheme.colorScheme
    val settings = LocalSettings.current
    val list = settings.list
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, motion.fastSpatialSpec(), label = "press")
    val look = rememberDropAnimation(drop)
    val background by animateColorAsState(
        when {
            drop.hovered -> colors.primaryContainer
            selected -> colors.secondaryContainer
            else -> if (list.tileFrame) colors.surfaceContainerLow else androidx.compose.ui.graphics.Color.Transparent
        },
        motion.defaultEffectsSpec(),
        label = "tileBg",
    )
    val ghostAlpha by animateFloatAsState(if (drop.ghosted) 0.36f else 1f, motion.defaultEffectsSpec(), label = "tileGhost")
    val cutAlpha by animateFloatAsState(if (item.path in LocalCutPaths.current) CUT_ALPHA else 1f, motion.defaultEffectsSpec(), label = "tileCut")
    val flash = rememberFlash(flashKey, flashPulses)
    val flashColor = colors.primary.copy(alpha = 0.32f)
    val shape = LocalSettings.current.appearance.iconShape.toShape()
    val kind = FileKinds.of(item)
    val picture = showThumbnail && list.thumbnails && (kind == FileKind.IMAGE || kind == FileKind.VIDEO)
    val apk = showThumbnail && FileKinds.hasApkIcon(item)
    val folderBadge = showThumbnail && list.folderBadges && item.isDirectory
    val compact = !list.tileFrame && !picture
    val iconSize = (if (list.gridColumns == 4) 56.dp else 66.dp) * ((if (item.isDirectory) list.folderScale else list.itemScale) / 100f)
    val nameScale = list.nameScale / 100f
    val metaScale = list.metaScale / 100f
    Column(
        modifier
            .then(drop.spot)
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = ghostAlpha * cutAlpha
            }
            .clip(MaterialTheme.shapes.large)
            .background(background)
            .drawBehind {
                val a = flash.value
                if (a > 0f) drawRect(flashColor, alpha = a)
            }
            .fileGestures(interaction, onClick, onLongClick, onDragStart)
            .padding(6.dp)
            .testTag("file-tile"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.fillMaxWidth().then(if (compact) Modifier.height(iconSize + 8.dp) else Modifier.aspectRatio(1f)).then(drop.anchor).dropHaloBehind(look, reach = 0.72f),
            contentAlignment = Alignment.Center,
        ) {
            when {
                picture -> {
                    KindIcon(item, 48.dp, shape)
                    AsyncImage(
                        model = thumbnailModel(item),
                        contentDescription = item.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().clip(MaterialTheme.shapes.medium),
                    )
                }
                apk -> ApkIconOrKind(item, iconSize, shape)
                folderBadge -> FolderIconWithBadge(item, iconSize, shape)
                else -> KindIcon(item, iconSize, shape)
            }
            if (item.isDirectory && dev.cluvex.strata.core.model.ProtectedFolders.shows(settings.protectedFolders, item.path)) {
                LockBadge(Modifier.align(Alignment.BottomEnd))
            }
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(onClickLabel = stringResource(R.string.common_select)) { onIconClick() }
                    .padding(4.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Box(Modifier.fillMaxSize().clip(CircleShape).background(colors.primary), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.explorer_selected), tint = colors.onPrimary, modifier = Modifier.size(14.dp))
                    }
                } else if (selecting) {
                    Box(Modifier.fillMaxSize().clip(CircleShape).border(2.dp, colors.outline, CircleShape).background(colors.surface.copy(alpha = 0.6f)))
                }
            }
        }
        FileNameText(
            name = Formatters.displayName(item.name, item.isDirectory, list.showExtensions),
            style = MaterialTheme.typography.labelMedium.let { it.copy(fontSize = it.fontSize * nameScale) },
            maxLines = if (list.wrapNames) 3 else 2,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = if (compact) 2.dp else 6.dp),
        )
        val info = trailingInfo(item, list) ?: if (list.showDate && list.tileFrame) Formatters.listDate(item.lastModified, list.dateStyle, list.use24Hour) else null
        if (info != null) {
            Text(
                info,
                style = MaterialTheme.typography.labelSmall,
                fontSize = MaterialTheme.typography.labelSmall.fontSize * metaScale,
                color = colors.onSurfaceVariant,
                // Narrow 4-column tiles: wrap the date and time onto two lines instead of cutting it off.
                maxLines = if (list.gridColumns == 4) 2 else 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun rememberFlash(flashKey: Int, pulses: Int): Animatable<Float, *> {
    val flash = remember { Animatable(0f) }
    LaunchedEffect(flashKey) {
        if (flashKey == 0) {
            flash.snapTo(0f)
            return@LaunchedEffect
        }
        repeat(pulses) {
            flash.animateTo(1f, tween(FLASH_IN_MS, easing = FastOutSlowInEasing))
            flash.animateTo(0.25f, tween(FLASH_OUT_MS, easing = FastOutSlowInEasing))
        }
        flash.animateTo(0f, tween(FLASH_FADE_MS))
    }
    return flash
}

const val FLASH_PULSES = 2
private const val FLASH_IN_MS = 200
private const val FLASH_OUT_MS = 280
private const val FLASH_FADE_MS = 520

fun flashDurationMs(pulses: Int): Long = (pulses * (FLASH_IN_MS + FLASH_OUT_MS) + FLASH_FADE_MS).toLong()

@Composable
private fun trailingInfo(item: FileItem, list: ListPrefs): String? = when {
    item.isSymlink -> stringResource(R.string.explorer_link)
    item.isDirectory -> null
    !list.showSize -> null
    else -> Formatters.size(item.size, list.sizeUnits)
}

@Composable
private fun thumbnailModel(item: FileItem): Any {
    val context = LocalContext.current
    return remember(item.path) {
        if (item.extension == "gif" || item.extension == "webp") {
            ImageRequest.Builder(context).data(item.path.toPath()).decoderFactory(BitmapFactoryDecoder.Factory()).build()
        } else {
            item.path.toPath()
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Leading(item: FileItem, selected: Boolean, showThumbnail: Boolean, iconSize: Dp, onIconClick: (() -> Unit)?) {
    val motion = MaterialTheme.motionScheme
    val colors = MaterialTheme.colorScheme
    val settings = LocalSettings.current
    val shape = LocalSettings.current.appearance.iconShape.toShape()
    val kind = FileKinds.of(item)
    Box(
        Modifier
            .size(iconSize)
            // Folders are not clipped to the rounded tile: their corner badge would get its corner cut off (grid tiles never clip them).
            // Only the generic tile (Custom file icons off) takes the chosen icon shape. Dedicated ic_* icons and extension tiles keep their own geometry.
            .then(if (item.isDirectory || settings.appearance.customFileIcons) Modifier else Modifier.clip(shape))
            .then(if (onIconClick != null) Modifier.clickable(onClickLabel = stringResource(R.string.common_select)) { onIconClick() } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = selected,
            transitionSpec = {
                (scaleIn(motion.fastSpatialSpec(), initialScale = 0.6f) + fadeIn(motion.fastEffectsSpec()))
                    .togetherWith(scaleOut(motion.fastSpatialSpec(), targetScale = 0.6f) + fadeOut(motion.fastEffectsSpec()))
            },
            label = "selectCheck",
        ) { isSelected ->
            when {
                isSelected -> Box(
                    Modifier.size(iconSize).clip(shape).background(colors.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.explorer_selected), tint = colors.onPrimary)
                }
                showThumbnail && settings.list.thumbnails && (kind == FileKind.IMAGE || kind == FileKind.VIDEO) -> Box(
                    Modifier.size(iconSize),
                    contentAlignment = Alignment.Center,
                ) {
                    KindIcon(item, iconSize, shape)
                    AsyncImage(
                        model = thumbnailModel(item),
                        contentDescription = item.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(iconSize).clip(shape),
                    )
                }
                showThumbnail && FileKinds.hasApkIcon(item) -> ApkIconOrKind(item, iconSize, shape)
                showThumbnail && settings.list.folderBadges && item.isDirectory -> FolderIconWithBadge(item, iconSize, shape)
                else -> KindIcon(item, iconSize, shape)
            }
        }
        if (item.isDirectory && dev.cluvex.strata.core.model.ProtectedFolders.shows(settings.protectedFolders, item.path)) {
            LockBadge(Modifier.align(Alignment.BottomEnd))
        }
    }
}

@Composable
private fun LockBadge(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(modifier.size(16.dp).clip(CircleShape).background(colors.primary), contentAlignment = Alignment.Center) {
        Icon(
            Icons.Rounded.Lock,
            contentDescription = stringResource(R.string.explorer_protected_badge),
            tint = colors.onPrimary,
            modifier = Modifier.size(10.dp),
        )
    }
}

@Composable
private fun ApkIconOrKind(item: FileItem, size: Dp, shape: Shape) {
    var loaded by remember(item.path) { mutableStateOf(false) }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        if (!loaded) KindIcon(item, size, shape)
        AsyncImage(
            model = remember(item.path, item.lastModified) { ApkIcon(item.path, item.lastModified) },
            contentDescription = stringResource(R.string.explorer_app_icon),
            onSuccess = { loaded = true },
            modifier = Modifier.size(size).testTag("apk-icon"),
        )
    }
}

/** Folder icon with a small badge in its bottom corner: the phone's own media app, the app whose APK lives inside, or a chosen file. */
@Composable
private fun FolderIconWithBadge(item: FileItem, iconSize: Dp, shape: Shape) {
    val specs by FolderBadgeStore.specs.collectAsState()
    val spec = specs[item.path]
    val badgeSize = iconSize * 0.5f
    val badgeShape = RoundedCornerShape(20)
    var failed by remember(item.path, item.lastModified, spec) { mutableStateOf(false) }
    // "A file inside the folder": when it has no image / video / APK to show, fall back to the default icon of its first file.
    val fallback by produceState<FileItem?>(null, item.path, item.lastModified, spec, failed) {
        value = if (spec == "auto" && failed) {
            withContext(Dispatchers.IO) {
                runCatching {
                    File(item.path).listFiles()
                        ?.filter { it.isFile && !it.name.startsWith(".") } // hidden files never give the badge
                        ?.minWithOrNull(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
                        ?.let { FileItem.of(it) }
                }.getOrNull()
            }
        } else {
            null
        }
    }
    Box(Modifier.size(iconSize)) {
        KindIcon(item, iconSize, shape)
        AsyncImage(
            model = remember(item.path, item.lastModified, spec) { FolderBadge(item.path, item.lastModified, spec) },
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onError = { failed = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(badgeSize)
                .clip(badgeShape)
                .testTag("folder-badge"),
        )
        fallback?.let { file ->
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(badgeSize)
                    .clip(badgeShape)
                    .testTag("folder-badge-file"),
            ) { KindIcon(file, badgeSize, badgeShape) }
        }
    }
}

@Composable
private fun KindIcon(item: FileItem, iconSize: Dp, shape: Shape) {
    val colors = MaterialTheme.colorScheme
    val settings = LocalSettings.current
    val kind = FileKinds.of(item)
    // Files: dedicated icons when "Custom file icons" is on, else the classic tile. Folders: folder artwork when "Folder-shaped icons" is on, else the classic tile.
    val dedicated = if (item.isDirectory) settings.appearance.folderShaped else settings.appearance.customFileIcons
    val custom = if (dedicated) FileKinds.customIcon(item, settings.appearance.folderStyle) else null
    val folderTint = rememberFolderTint()
    val tintFolder = item.isDirectory && settings.appearance.folderShaped && !settings.appearance.folderFixedColor
    if (custom != null) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(custom),
            contentDescription = stringResource(kind.label),
            colorFilter = if (tintFolder) folderTint else null,
            modifier = Modifier
                .size(iconSize)
                // Folder art is wide and tightly cropped: it fills the box. File icons carry their own margin, so they are drawn a bit larger.
                .then(if (item.isDirectory) Modifier else Modifier.graphicsLayer { scaleX = FILE_ICON_SCALE; scaleY = FILE_ICON_SCALE }),
        )
        return
    }
    val autoLabel = if (dedicated) FileKinds.autoIconLabel(item) else null
    if (autoLabel != null) {
        ExtensionIcon(
            extension = autoLabel,
            contentDescription = stringResource(kind.label),
            modifier = Modifier
                .size(iconSize)
                .graphicsLayer { scaleX = FILE_ICON_SCALE; scaleY = FILE_ICON_SCALE },
        )
        return
    }
    val tile = KindPalette.colors(kind, settings.appearance.colorfulIcons, colors, colors.background.luminance() < 0.5f)
    Box(
        Modifier
            .size(iconSize)
            .clip(shape)
            .background(tile.container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            kind.icon,
            contentDescription = stringResource(kind.label),
            tint = tile.content,
            modifier = Modifier.padding(iconSize * 0.22f),
        )
    }
}

/** Branded file icons (and the extension tiles that share their shape) are drawn this much larger than their box so they weigh as much as the folder art. */
private const val FILE_ICON_SCALE = 1.06f
