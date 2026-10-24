package dev.cluvex.strata.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.CallMerge
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.automirrored.rounded.ManageSearch
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Outbox
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddLink
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Audiotrack
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.FolderSpecial
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Coffee
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.CopyAll
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.EnhancedEncryption
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.FolderZip
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SortByAlpha
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.ui.graphics.vector.ImageVector
import dev.cluvex.strata.core.model.BrowseAction
import dev.cluvex.strata.core.model.SelectionAction
import dev.cluvex.strata.core.model.SheetAction

val BrowseAction.icon: ImageVector
    get() = when (this) {
        BrowseAction.BACK -> Icons.AutoMirrored.Rounded.ArrowBack
        BrowseAction.FORWARD -> Icons.AutoMirrored.Rounded.ArrowForward
        BrowseAction.NEW -> Icons.Rounded.Add
        BrowseAction.PASTE -> Icons.Rounded.ContentPaste
        BrowseAction.SWAP -> Icons.Rounded.SwapHoriz
        BrowseAction.UP -> Icons.Rounded.ArrowUpward
        BrowseAction.HOME -> Icons.Rounded.Home
        BrowseAction.REFRESH -> Icons.Rounded.Refresh
        BrowseAction.BOOKMARKS -> Icons.Rounded.Bookmarks
        BrowseAction.BOOKMARK_FOLDER -> Icons.Rounded.BookmarkAdd
        BrowseAction.PROTECTED_FOLDERS -> Icons.Rounded.Shield
        BrowseAction.SELECT_ALL -> Icons.Rounded.SelectAll
        BrowseAction.RUN_PROJECT -> Icons.Rounded.PlayArrow
        BrowseAction.TERMINAL -> Icons.Rounded.Terminal
        BrowseAction.FILTER -> Icons.Rounded.Search
        BrowseAction.SEARCH -> Icons.AutoMirrored.Rounded.ManageSearch
        BrowseAction.SORT -> Icons.Rounded.SortByAlpha
        BrowseAction.JUMP -> Icons.Rounded.Explore
        BrowseAction.HIDDEN_FILES -> Icons.Rounded.Visibility
    }

val SelectionAction.icon: ImageVector
    get() = when (this) {
        SelectionAction.OPEN_WITH -> Icons.AutoMirrored.Rounded.OpenInNew
        SelectionAction.EXTRACT -> Icons.Rounded.Unarchive
        SelectionAction.COPY -> Icons.Rounded.ContentCopy
        SelectionAction.MOVE -> Icons.Rounded.ContentCut
        SelectionAction.DELETE -> Icons.Rounded.Delete
        SelectionAction.RENAME -> Icons.Rounded.DriveFileRenameOutline
        SelectionAction.BATCH_RENAME -> Icons.Rounded.FormatListNumbered
        SelectionAction.COPY_CLIPBOARD -> Icons.Rounded.CopyAll
        SelectionAction.CUT_CLIPBOARD -> Icons.Rounded.ContentCut
        SelectionAction.COMPRESS -> Icons.Rounded.FolderZip
        SelectionAction.MERGE -> Icons.AutoMirrored.Rounded.CallMerge
        SelectionAction.COMPARE -> Icons.Rounded.Compare
        SelectionAction.SMALI_TO_DEX -> Icons.Rounded.Build
        SelectionAction.JAR_TO_DEX -> Icons.Rounded.Coffee
        SelectionAction.BOOKMARK -> Icons.Rounded.BookmarkAdd
        SelectionAction.BOOKMARKS -> Icons.Rounded.Bookmarks
        SelectionAction.FOLDER_ICON -> Icons.Rounded.FolderSpecial
        SelectionAction.PROTECT -> Icons.Rounded.Lock
        SelectionAction.PROTECTED_FOLDERS -> Icons.Rounded.Shield
        SelectionAction.LINKS -> Icons.Rounded.AddLink
        SelectionAction.VAULT -> Icons.Rounded.EnhancedEncryption
        SelectionAction.RESTORE -> Icons.Rounded.Outbox
        SelectionAction.SHARE -> Icons.Rounded.Share
        SelectionAction.COPY_PATH -> Icons.Rounded.Link
        SelectionAction.PROPERTIES -> Icons.Rounded.Info
        SelectionAction.SELECT_ALL -> Icons.Rounded.SelectAll
        SelectionAction.INVERT -> Icons.Rounded.Flip
    }

val SheetAction.icon: ImageVector
    get() = when (this) {
        SheetAction.OPEN -> Icons.Rounded.FileOpen
        SheetAction.OPEN_WITH -> Icons.AutoMirrored.Rounded.OpenInNew
        SheetAction.SHARE -> Icons.Rounded.Share
        SheetAction.RENAME -> Icons.Rounded.DriveFileRenameOutline
        SheetAction.OPEN_AS_TEXT -> Icons.Rounded.Edit
        SheetAction.HEX -> Icons.Rounded.Memory
        SheetAction.VIEW -> Icons.Rounded.FolderOpen
        SheetAction.APK_INFO -> Icons.Rounded.Android
        SheetAction.EXTRACT -> Icons.Rounded.Unarchive
        SheetAction.COMPRESS -> Icons.Rounded.FolderZip
        SheetAction.SIGN_APK -> Icons.Rounded.Key
        SheetAction.SMALI_TO_DEX -> Icons.Rounded.Build
        SheetAction.JAR_TO_DEX -> Icons.Rounded.Coffee
        SheetAction.CHECKSUM -> Icons.Rounded.Fingerprint
        SheetAction.SPLIT -> Icons.AutoMirrored.Rounded.CallSplit
        SheetAction.CIPHER -> Icons.Rounded.EnhancedEncryption
        SheetAction.SELECT -> Icons.Rounded.CheckCircle
        SheetAction.BOOKMARK -> Icons.Rounded.BookmarkAdd
        SheetAction.PROTECT -> Icons.Rounded.Lock
        SheetAction.LINK -> Icons.Rounded.AddLink
        SheetAction.VAULT -> Icons.Rounded.EnhancedEncryption
        SheetAction.RESTORE -> Icons.Rounded.Outbox
        SheetAction.PROPERTIES -> Icons.Rounded.Info
        SheetAction.AUDIO_INFO -> Icons.Rounded.Audiotrack
        SheetAction.VIDEO_INFO -> Icons.Rounded.Movie
    }
