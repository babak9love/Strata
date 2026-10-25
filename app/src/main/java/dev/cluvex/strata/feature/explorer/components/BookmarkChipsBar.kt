package dev.cluvex.strata.feature.explorer.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.toShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.positionInParent
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.cluvex.strata.R
import dev.cluvex.strata.core.bookmarks.Bookmark
import dev.cluvex.strata.core.bookmarks.Bookmarks
import dev.cluvex.strata.ui.LocalSettings

private val CHIP_ROW_PADDING = 12.dp
private val CHIP_SPACING = 8.dp

/**
 * Horizontal bookmark chips (Material Files style).
 * Short press opens the path; long press opens bookmark settings.
 * Each chip has the folder icon before its name; the selected one is filled and bold.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BookmarkChipsBar(
    bookmarks: List<Bookmark>,
    currentPath: String,
    onOpen: (Bookmark) -> Unit,
    onLongPress: (Bookmark) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (bookmarks.isEmpty()) return
    val normalizedCurrent = Bookmarks.normalize(currentPath)
    // Classic Strata look: each chip starts with a tile holding the first letter of the bookmark, like the drawer.
    val look = LocalSettings.current.appearance
    // Letter tile only while folder-shaped icons are off; with them on the chip shows the folder like every folder.
    val classic = !look.folderShaped
    // "Fixed folder color" off: the folder takes the theme colour, bookmarks included.
    val tintFolder = look.folderShaped && !look.folderFixedColor
    val folderTint = rememberFolderTint()
    // The classic chip is the drawer's: a filled pill, no outline.
    val shape = if (classic) CircleShape else RoundedCornerShape(20.dp)
    val scroll = rememberScrollState()
    val bounds = remember { mutableStateMapOf<String, Int>() }
    val density = LocalDensity.current
    val edgePaddingPx = with(density) { CHIP_ROW_PADDING.roundToPx() }
    val chipSpacingPx = with(density) { CHIP_SPACING.roundToPx() }
    val hasSelected = bookmarks.any { Bookmarks.normalize(it.path) == normalizedCurrent }
    // Whenever the selected bookmark changes (including on entering the app), put its chip at the start
    // of the row, with the gap before it ending exactly at the edge so the previous chip does not peek in.
    LaunchedEffect(normalizedCurrent, hasSelected, bookmarks) {
        if (!hasSelected) return@LaunchedEffect
        // Let the row finish measuring/positioning so the chip's position and the scroll range are current.
        withFrameNanos { }
        withFrameNanos { }
        val x = snapshotFlow { bounds[normalizedCurrent] }.filterNotNull().first()
        withTimeoutOrNull(300) { snapshotFlow { scroll.maxValue }.first { it > 0 } }
        // x is measured inside the row's padding, so the chip lands at (padding + x - target) on screen.
        // Put it exactly one gap from the edge: the previous chip then ends at the edge and cannot peek in.
        val target = if (x <= edgePaddingPx) 0 else x + edgePaddingPx - chipSpacingPx
        scroll.animateScrollTo(target.coerceIn(0, scroll.maxValue))
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(horizontal = CHIP_ROW_PADDING, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(CHIP_SPACING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        bookmarks.forEach { bookmark ->
            val selected = Bookmarks.normalize(bookmark.path) == normalizedCurrent
            val cd = stringResource(R.string.explorer_bookmark_cd, bookmark.name)
            // Selected chip: filled (tonal) and bold. The others: transparent with a thin outline.
            val bg = when {
                selected -> MaterialTheme.colorScheme.secondaryContainer
                classic -> MaterialTheme.colorScheme.surfaceContainerHigh
                else -> Color.Transparent
            }
            val fg = when {
                selected -> MaterialTheme.colorScheme.onSecondaryContainer
                classic -> MaterialTheme.colorScheme.onSurface
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(shape)
                    .background(bg, shape)
                    .then(
                        if (selected || classic) Modifier
                        else Modifier.border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                    )
                    .onGloballyPositioned { bounds[Bookmarks.normalize(bookmark.path)] = it.positionInParent().x.toInt() }
                    .semantics { contentDescription = cd }
                    .combinedClickable(
                        onClick = { onOpen(bookmark) },
                        onLongClick = { onLongPress(bookmark) },
                    )
                    .then(
                        if (classic) Modifier.padding(start = 6.dp, end = 16.dp, top = 6.dp, bottom = 6.dp)
                        else Modifier.padding(start = 14.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                    ),
            ) {
                if (classic) {
                    Box(
                        Modifier
                            .size(32.dp)
                            .clip(MaterialShapes.Cookie4Sided.toShape())
                            .background(MaterialTheme.colorScheme.tertiaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            bookmark.name.take(1).uppercase(),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                } else {
                    Image(
                        painter = painterResource(look.folderStyle.drawable),
                        contentDescription = null,
                        colorFilter = if (tintFolder) folderTint else null,
                        modifier = Modifier.size(width = 24.dp, height = 19.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = bookmark.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.Bold else null,
                    color = fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
