package dev.cluvex.strata.feature.explorer.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.cluvex.strata.R
import dev.cluvex.strata.core.bookmarks.Bookmark
import dev.cluvex.strata.core.bookmarks.Bookmarks

/**
 * Horizontal bookmark chips (Material Files style).
 * Short press opens the path; long press opens bookmark settings.
 * No leading icon; label is centered inside the capsule.
 */
@OptIn(ExperimentalFoundationApi::class)
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
    val shape = RoundedCornerShape(20.dp)
    val scroll = rememberScrollState()
    var viewport by remember { mutableIntStateOf(0) }
    val bounds = remember { mutableStateMapOf<String, Pair<Int, Int>>() }
    val firstScroll = remember { booleanArrayOf(true) }
    val edgePadding = with(LocalDensity.current) { 12.dp.roundToPx() }
    val hasSelected = bookmarks.any { Bookmarks.normalize(it.path) == normalizedCurrent }
    LaunchedEffect(normalizedCurrent, viewport, hasSelected) {
        if (!hasSelected || viewport == 0) return@LaunchedEffect
        val (x, w) = snapshotFlow { bounds[normalizedCurrent] }.filterNotNull().first()
        if (firstScroll[0]) {
            // On entering the app, show the selected chip at the left edge; the chips before it are a scroll to the left.
            firstScroll[0] = false
            scroll.scrollTo((x - edgePadding).coerceIn(0, scroll.maxValue))
        } else {
            scroll.animateScrollTo((x + w / 2 - viewport / 2).coerceIn(0, scroll.maxValue))
        }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { viewport = it.width }
            .horizontalScroll(scroll)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        bookmarks.forEach { bookmark ->
            val selected = Bookmarks.normalize(bookmark.path) == normalizedCurrent
            val cd = stringResource(R.string.explorer_bookmark_cd, bookmark.name)
            val bg = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
            }
            val fg = if (selected) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .clip(shape)
                    .background(bg, shape)
                    .then(
                        if (selected) Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, shape)
                        else Modifier
                    )
                    .onGloballyPositioned { bounds[Bookmarks.normalize(bookmark.path)] = it.positionInParent().x.toInt() to it.size.width }
                    .semantics { contentDescription = cd }
                    .combinedClickable(
                        onClick = { onOpen(bookmark) },
                        onLongClick = { onLongPress(bookmark) },
                    )
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text = bookmark.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
