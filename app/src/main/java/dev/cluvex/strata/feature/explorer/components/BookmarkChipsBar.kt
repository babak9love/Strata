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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
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
