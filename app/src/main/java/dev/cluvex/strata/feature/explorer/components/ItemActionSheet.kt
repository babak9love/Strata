package dev.cluvex.strata.feature.explorer.components

import dev.cluvex.strata.ui.isolated
import dev.cluvex.strata.core.i18n.separator
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.cluvex.strata.R
import dev.cluvex.strata.core.archive.ArchiveManager
import dev.cluvex.strata.core.archive.ArchiveReader
import dev.cluvex.strata.core.crypto.FileCipher
import dev.cluvex.strata.core.model.ActionLayout
import dev.cluvex.strata.core.model.FileItem
import dev.cluvex.strata.core.model.SheetAction
import dev.cluvex.strata.core.model.SheetContext
import dev.cluvex.strata.core.model.SheetGroup
import dev.cluvex.strata.core.model.Zoned
import dev.cluvex.strata.core.model.available
import dev.cluvex.strata.core.remote.RemoteUri
import dev.cluvex.strata.core.util.FileKind
import dev.cluvex.strata.core.util.FileKinds
import dev.cluvex.strata.core.util.Formatters
import dev.cluvex.strata.ui.icon
import okio.Path.Companion.toPath
import dev.cluvex.strata.core.vault.Vault

class ItemActions(
    val onSelect: () -> Unit,
    val onOpen: () -> Unit,
    val onOpenWith: () -> Unit,
    val onOpenAsText: () -> Unit,
    val onHex: () -> Unit,
    val onRename: () -> Unit,
    val onShare: () -> Unit,
    val onChecksum: () -> Unit,
    val onExtract: () -> Unit,
    val onApkInfo: () -> Unit,
    val onSignApk: () -> Unit,
    val onView: () -> Unit,
    val onCompress: () -> Unit,
    val onAddBookmark: () -> Unit,
    val onCreateLink: () -> Unit,
    val onSplit: () -> Unit,
    val onEncrypt: () -> Unit,
    val onDecrypt: () -> Unit,
    val onProperties: () -> Unit,
    val onDelete: () -> Unit,
    val onSmaliToDex: () -> Unit = {},
    val onJarToDex: () -> Unit = {},
    val onMoveToVault: () -> Unit = {},
    val onRestore: () -> Unit = {},
)

private class SheetEntry(val icon: ImageVector, @StringRes val label: Int, val onClick: () -> Unit)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ItemActionSheet(
    item: FileItem,
    actions: ItemActions,
    onDismiss: () -> Unit,
    smaliFolder: Boolean = false,
    layout: Zoned<SheetAction> = ActionLayout.DEFAULT_SHEET,
    onCustomize: (() -> Unit)? = null,
) {
    val local = !RemoteUri.isRemote(item.path)
    val kind = FileKinds.of(item)
    val file = !item.isDirectory
    val context = SheetContext(
        file = file,
        local = local,
        textEditable = FileKinds.isTextEditable(item),
        archiveViewable = ArchiveReader.supports(item.name),
        extractable = ArchiveManager.isExtractable(item.name),
        apk = kind == FileKind.APK,
        jar = item.extension.equals("jar", ignoreCase = true),
        smaliFolder = smaliFolder,
        symlink = item.isSymlink,
        vault = Vault.isVault(item.path),
    )
    val encrypted = item.name.endsWith(FileCipher.EXTENSION, ignoreCase = true)
    fun entry(a: SheetAction): SheetEntry = when (a) {
        SheetAction.OPEN -> SheetEntry(if (file) Icons.Rounded.FileOpen else Icons.Rounded.FolderOpen, a.label, actions.onOpen)
        SheetAction.OPEN_WITH -> SheetEntry(a.icon, a.label, actions.onOpenWith)
        SheetAction.SHARE -> SheetEntry(a.icon, a.label, actions.onShare)
        SheetAction.RENAME -> SheetEntry(a.icon, a.label, actions.onRename)
        SheetAction.OPEN_AS_TEXT -> SheetEntry(a.icon, a.label, actions.onOpenAsText)
        SheetAction.HEX -> SheetEntry(a.icon, a.label, actions.onHex)
        SheetAction.VIEW -> SheetEntry(a.icon, a.label, actions.onView)
        SheetAction.APK_INFO -> SheetEntry(a.icon, a.label, actions.onApkInfo)
        SheetAction.EXTRACT -> SheetEntry(a.icon, a.label, actions.onExtract)
        SheetAction.COMPRESS -> SheetEntry(a.icon, a.label, actions.onCompress)
        SheetAction.SIGN_APK -> SheetEntry(a.icon, a.label, actions.onSignApk)
        SheetAction.SMALI_TO_DEX -> SheetEntry(a.icon, a.label, actions.onSmaliToDex)
        SheetAction.JAR_TO_DEX -> SheetEntry(a.icon, a.label, actions.onJarToDex)
        SheetAction.CHECKSUM -> SheetEntry(a.icon, a.label, actions.onChecksum)
        SheetAction.SPLIT -> SheetEntry(a.icon, a.label, actions.onSplit)
        SheetAction.CIPHER -> if (encrypted) SheetEntry(Icons.Rounded.LockOpen, R.string.explorer_decrypt_more, actions.onDecrypt) else SheetEntry(a.icon, R.string.explorer_encrypt_more, actions.onEncrypt)
        SheetAction.SELECT -> SheetEntry(a.icon, a.label, actions.onSelect)
        SheetAction.BOOKMARK -> SheetEntry(a.icon, a.label, actions.onAddBookmark)
        SheetAction.LINK -> SheetEntry(a.icon, a.label, actions.onCreateLink)
        SheetAction.VAULT -> SheetEntry(a.icon, a.label, actions.onMoveToVault)
        SheetAction.RESTORE -> SheetEntry(a.icon, a.label, actions.onRestore)
        SheetAction.PROPERTIES -> SheetEntry(a.icon, a.label, actions.onProperties)
    }
    val primary = layout.zones[0].filter { it.available(context) }.map(::entry)
    val groups = SheetGroup.entries.map { g -> g to layout.zones[1].filter { it.group == g && it.available(context) }.map(::entry) }

    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("item-sheet")) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Header(item, kind, local, onCustomize)
            if (primary.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    primary.forEach { a -> PrimaryButton(a, Modifier.weight(1f)) }
                }
            }
            groups.forEach { (g, items) -> Group(stringResource(g.title), items) }
            OutlinedButton(
                onClick = actions.onDelete,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
            ) {
                Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.common_delete))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Header(item: FileItem, kind: FileKind, local: Boolean, onCustomize: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(colors.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            val custom = FileKinds.customIcon(item)
            if (custom != null) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(custom),
                    contentDescription = null,
                    modifier = Modifier.size(36.dp),
                )
            } else {
                Icon(kind.icon, contentDescription = null, tint = colors.onSecondaryContainer, modifier = Modifier.size(28.dp))
            }
            if (local && (kind == FileKind.IMAGE || kind == FileKind.VIDEO)) {
                val model = remember(item.path) { item.path.toPath() }
                AsyncImage(model = model, contentDescription = null, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)))
            }
        }
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(isolated(item.name), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = buildList {
                when {
                    item.isSymlink -> add(stringResource(R.string.explorer_symlink))
                    item.isDirectory -> add(stringResource(R.string.common_folder))
                    else -> add(Formatters.size(item.size))
                }
                if (item.lastModified > 0) add(Formatters.date(item.lastModified))
                if (!item.isDirectory && item.extension.isNotEmpty()) add(item.extension.uppercase())
            }
            Text(meta.joinToString(separator()), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        if (onCustomize != null) {
            IconButton(onClick = onCustomize, shapes = IconButtonDefaults.shapes()) {
                Icon(Icons.Rounded.Tune, contentDescription = stringResource(R.string.explorer_customize_menu), tint = colors.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PrimaryButton(a: SheetEntry, modifier: Modifier) {
    Surface(
        onClick = a.onClick,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = modifier.heightIn(min = 68.dp),
    ) {
        Column(Modifier.padding(vertical = 10.dp, horizontal = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(a.icon, contentDescription = null)
            Text(stringResource(a.label), style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun Group(title: String, items: List<SheetEntry>) {
    if (items.isEmpty()) return
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 18.dp, bottom = 8.dp, start = 4.dp),
    )
    items.chunked(3).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { a -> Tile(a, Modifier.weight(1f)) }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun Tile(a: SheetEntry, modifier: Modifier) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = a.onClick),
    ) {
        Column(
            Modifier.padding(vertical = 12.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(a.icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                stringResource(a.label),
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}
