package dev.cluvex.strata.feature.apps

import dev.cluvex.strata.core.i18n.separator
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.cluvex.strata.R
import dev.cluvex.strata.core.apk.AppIntents
import dev.cluvex.strata.core.apk.InstalledApp
import dev.cluvex.strata.core.util.Formatters
import dev.cluvex.strata.feature.explorer.components.TaskOverlay
import dev.cluvex.strata.ui.rememberTextClipboard
import java.io.File
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.rounded.GppMaybe
import androidx.compose.material.icons.rounded.VerifiedUser
import dev.cluvex.strata.core.apk.AppSigners
import dev.cluvex.strata.feature.keys.CertificateDetails

data class AppsActions(
    val onBack: () -> Unit,
    val onOpenApk: (path: String) -> Unit,
    val onLocate: (folder: String, reveal: String?) -> Unit,
    val onExtractSettings: (dir: String, pattern: String) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AppsScreen(
    extractDir: String,
    namePattern: String,
    actions: AppsActions,
    vm: AppsViewModel = viewModel(),
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var searching by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<InstalledApp?>(null) }
    var signatureOf by remember { mutableStateOf<InstalledApp?>(null) }
    var settingsDialog by remember { mutableStateOf(false) }
    val showLabel = stringResource(R.string.common_show)

    LaunchedEffect(s.message) {
        val msg = s.message ?: return@LaunchedEffect
        val folder = s.lastExtracted
        val result = snackbar.showSnackbar(msg, actionLabel = if (folder != null) showLabel else null, duration = SnackbarDuration.Long)
        vm.consumeMessage()
        if (result == SnackbarResult.ActionPerformed && folder != null) actions.onLocate(folder.path, null)
    }
    BackHandler {
        when {
            s.selected.isNotEmpty() -> vm.clearSelection()
            searching -> { searching = false; vm.setQuery("") }
            else -> actions.onBack()
        }
    }

    Scaffold(
        modifier = Modifier.testTag("apps-screen"),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (s.selected.isNotEmpty()) {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = vm::clearSelection) { Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.common_clear_selection)) } },
                    title = { Text(stringResource(R.string.apps_selected, s.selected.size)) },
                    actions = {
                        IconButton(onClick = vm::selectAll) { Icon(Icons.Rounded.SelectAll, contentDescription = stringResource(R.string.common_select_all)) }
                        IconButton(onClick = { vm.extract(s.all.filter { it.packageName in s.selected }, extractDir, namePattern) }) {
                            Icon(Icons.Rounded.Unarchive, contentDescription = stringResource(R.string.apps_extract_apks))
                        }
                    },
                )
            } else {
                TopAppBar(
                    navigationIcon = { IconButton(onClick = actions.onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back)) } },
                    title = {
                        if (searching) {
                            val focusRequester = remember { FocusRequester() }
                            val keyboard = LocalSoftwareKeyboardController.current
                            // Opening the search puts the cursor in the field and brings up the keyboard.
                            LaunchedEffect(Unit) {
                                delay(100)
                                runCatching { focusRequester.requestFocus() }
                                keyboard?.show()
                            }
                            OutlinedTextField(
                                value = s.query,
                                onValueChange = vm::setQuery,
                                placeholder = { Text(stringResource(R.string.apps_search_hint)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                            )
                        } else {
                            Column {
                                Text(stringResource(R.string.apps_title))
                                Text(
                                    pluralStringResource(if (s.showSystem) R.plurals.apps_count_all else R.plurals.apps_count_user, s.visible.size, s.visible.size),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { searching = !searching; if (!searching) vm.setQuery("") }) {
                            Icon(if (searching) Icons.Rounded.Close else Icons.Rounded.Search, contentDescription = stringResource(if (searching) R.string.apps_close_search else R.string.common_search))
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.apps_options)) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.apps_show_system)) },
                                    trailingIcon = { Checkbox(checked = s.showSystem, onCheckedChange = null) },
                                    onClick = { menu = false; vm.setShowSystem(!s.showSystem) },
                                )
                                DropdownMenuItem(text = { Text(stringResource(R.string.apps_extract_settings)) }, onClick = { menu = false; settingsDialog = true })
                                DropdownMenuItem(text = { Text(stringResource(R.string.common_refresh)) }, onClick = { menu = false; vm.reload() })
                            }
                        }
                    },
                )
            }
        },
    ) { padding ->
        if (s.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { LoadingIndicator() }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding).testTag("apps-list"),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(s.visible, key = { it.packageName }) { app ->
                    AppRow(
                        app = app,
                        selected = app.packageName in s.selected,
                        icon = { vm.icon(it) },
                        signer = { vm.signerSummary(it) },
                        onClick = { if (s.selected.isNotEmpty()) vm.toggle(app.packageName) else sheet = app },
                        onLongClick = { vm.toggle(app.packageName) },
                        onSignature = { signatureOf = app },
                    )
                }
            }
        }
    }

    s.task?.let { TaskOverlay(it) }

    sheet?.let { app ->
        AppSheet(
            app = app,
            icon = { vm.icon(it) },
            onExtract = { sheet = null; vm.extract(listOf(app), extractDir, namePattern) },
            onOpenApk = { sheet = null; actions.onOpenApk(app.sourceDir) },
            onLocate = { sheet = null; actions.onLocate(File(app.sourceDir).parent ?: "/", app.sourceDir) },
            onDataFolder = { sheet = null; app.dataDir?.let { actions.onLocate(it, null) } },
            onSignature = { sheet = null; signatureOf = app },
            onDismiss = { sheet = null },
        )
    }
    signatureOf?.let { app ->
        SignatureSheet(app = app, signers = { vm.signers(it) }, summary = { vm.signerSummary(it) }, onDismiss = { signatureOf = null })
    }
    if (settingsDialog) {
        ExtractSettingsDialog(
            dir = extractDir.ifBlank { AppsViewModel.defaultDir().path },
            pattern = namePattern,
            onSave = { d, p -> settingsDialog = false; actions.onExtractSettings(d, p) },
            onDismiss = { settingsDialog = false },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppRow(
    app: InstalledApp,
    selected: Boolean,
    icon: suspend (String) -> android.graphics.Bitmap?,
    signer: suspend (String) -> SignerSummary?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onSignature: () -> Unit,
) {
    val bitmap by produceState<ImageBitmap?>(null, app.packageName) { value = icon(app.packageName)?.asImageBitmap() }
    val signerInfo by produceState<SignerSummary?>(null, app.packageName) { value = signer(app.packageName) }
    val size = remember(app.packageName) { app.apkPaths.sumOf { File(it).length() } }
    val splitLabel = stringResource(R.string.apps_split_apks)
    val systemLabel = stringResource(R.string.apps_system)
    val platformLabel = stringResource(R.string.apps_signer_platform)
    val debugLabel = stringResource(R.string.apps_signer_debug)
    val rotatedLabel = stringResource(R.string.apps_signer_rotated)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            val b = bitmap
            when {
                selected -> Icon(Icons.Rounded.CheckCircle, contentDescription = stringResource(R.string.apps_selected_cd), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(36.dp))
                b != null -> Image(b, contentDescription = null, modifier = Modifier.size(40.dp))
                else -> Icon(Icons.Rounded.Android, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                app.packageName,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(app.versionName.ifEmpty { null }, Formatters.size(size), if (app.isSplit) splitLabel else null, if (app.system) systemLabel else null)
                    .joinToString(separator()),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            signerInfo?.let { si ->
                Row(
                    Modifier
                        .padding(top = 2.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onSignature)
                        .testTag("signer-${app.packageName}"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (si.debug) Icons.Rounded.GppMaybe else Icons.Rounded.VerifiedUser,
                        contentDescription = null,
                        tint = if (si.debug) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(12.dp),
                    )
                    Text(
                        buildString {
                            append(si.label)
                            append(separator()).append(si.shortHash)
                            if (si.platform) append(separator()).append(platformLabel)
                            if (si.debug) append(separator()).append(debugLabel)
                            if (si.rotated) append(separator()).append(rotatedLabel)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 4.dp, end = 4.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SignatureSheet(
    app: InstalledApp,
    signers: suspend (String) -> AppSigners?,
    summary: suspend (String) -> SignerSummary?,
    onDismiss: () -> Unit,
) {
    val all by produceState<AppSigners?>(null, app.packageName) { value = signers(app.packageName) }
    val si by produceState<SignerSummary?>(null, app.packageName) { value = summary(app.packageName) }
    val platformNote = stringResource(R.string.apps_note_platform)
    val debugNote = stringResource(R.string.apps_note_debug)
    val multipleNote = stringResource(R.string.apps_note_multiple)
    val rotatedNote = stringResource(R.string.apps_note_rotated)
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("signature-sheet")) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text(stringResource(R.string.apk_signature), style = MaterialTheme.typography.titleLarge)
            Text(app.label + separator() + app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            si?.let { s ->
                val notes = buildList {
                    if (s.platform) add(platformNote)
                    if (s.debug) add(debugNote)
                    if (s.multiple) add(multipleNote)
                    if (s.rotated) add(rotatedNote)
                }
                notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
            }
            val signersNow = all
            when {
                signersNow == null -> Text(stringResource(R.string.apps_reading), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
                signersNow.current.isEmpty() -> Text(stringResource(R.string.apps_no_certificate), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
                else -> {
                    signersNow.current.forEachIndexed { i, cert ->
                        HorizontalDivider(Modifier.padding(vertical = 12.dp))
                        Text(if (signersNow.current.size > 1) stringResource(R.string.apps_signer_n, i + 1) else stringResource(R.string.apps_current_certificate), style = MaterialTheme.typography.titleSmall)
                        CertificateDetails(cert, Modifier.padding(top = 6.dp))
                    }
                    signersNow.past.asReversed().forEachIndexed { i, cert ->
                        HorizontalDivider(Modifier.padding(vertical = 12.dp))
                        Text(stringResource(R.string.apps_earlier_certificate, i + 1), style = MaterialTheme.typography.titleSmall)
                        CertificateDetails(cert, Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppSheet(
    app: InstalledApp,
    icon: suspend (String) -> android.graphics.Bitmap?,
    onExtract: () -> Unit,
    onOpenApk: () -> Unit,
    onLocate: () -> Unit,
    onDataFolder: () -> Unit,
    onSignature: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = rememberTextClipboard()
    val bitmap by produceState<ImageBitmap?>(null, app.packageName) { value = icon(app.packageName)?.asImageBitmap() }
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag("app-sheet")) {
        Column(Modifier.padding(bottom = 24.dp)) {
            Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                bitmap?.let { Image(it, contentDescription = null, modifier = Modifier.size(48.dp)) }
                Column(Modifier.padding(start = 16.dp)) {
                    Text(app.label, style = MaterialTheme.typography.titleLarge)
                    Text("${app.versionName} (${app.versionCode})", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider()
            SheetAction(stringResource(R.string.apps_extract_apk), onClick = onExtract)
            SheetAction(stringResource(R.string.apk_info_title), onClick = onOpenApk)
            SheetAction(stringResource(R.string.apk_signature), onClick = onSignature)
            if (app.launchable) SheetAction(stringResource(R.string.apk_launch)) { onDismiss(); AppIntents.launch(context, app.packageName) }
            SheetAction(stringResource(R.string.apps_app_info_system)) { onDismiss(); AppIntents.details(context, app.packageName) }
            SheetAction(stringResource(R.string.apps_show_in_files), onClick = onLocate)
            if (app.dataDir != null) SheetAction(stringResource(R.string.apps_open_data_folder), onClick = onDataFolder)
            SheetAction(stringResource(R.string.apps_copy_package)) { clipboard.setText(app.packageName); onDismiss() }
            if (!app.system) SheetAction(stringResource(R.string.common_uninstall), destructive = true) { onDismiss(); AppIntents.uninstall(context, app.packageName) }
        }
    }
}

@Composable
private fun SheetAction(label: String, destructive: Boolean = false, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Text(
            label,
            color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ExtractSettingsDialog(dir: String, pattern: String, onSave: (String, String) -> Unit, onDismiss: () -> Unit) {
    var d by remember { mutableStateOf(dir) }
    var p by remember { mutableStateOf(pattern) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.apps_extract_apk)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    d,
                    { d = it },
                    label = { Text(stringResource(R.string.apps_storage_path)) },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    p,
                    { p = it },
                    label = { Text(stringResource(R.string.apps_name_pattern)) },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.apps_pattern_help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(d, p) }, enabled = d.isNotBlank()) { Text(stringResource(R.string.common_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
