package dev.cluvex.strata.feature.remote

import dev.cluvex.strata.feature.explorer.components.FileNameText
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.cluvex.strata.R
import dev.cluvex.strata.core.remote.LanHost
import dev.cluvex.strata.core.remote.LanScanner
import dev.cluvex.strata.core.remote.RemoteConnection
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import dev.cluvex.strata.core.remote.RemoteEntry
import dev.cluvex.strata.core.remote.RemotePaths
import dev.cluvex.strata.core.remote.RemoteProtocol
import dev.cluvex.strata.core.util.Formatters
import dev.cluvex.strata.feature.termux.TerminalGate
import dev.cluvex.strata.feature.termux.TermuxViewModel
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import dev.cluvex.strata.core.remote.SafFileSystem
import dev.cluvex.strata.ui.expressive.Art
import dev.cluvex.strata.ui.expressive.ExpressiveEmptyState
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RemoteScreen(
    onBack: () -> Unit,
    onOpenLocalFile: (String) -> Unit,
    onLocate: (folder: String, reveal: String?) -> Unit,
    localDir: String?,
    vm: RemoteViewModel = viewModel(),
) {
    val ui by vm.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(Unit) {
        vm.checkModified()
        onPauseOrDispose { }
    }
    val snackbar = remember { SnackbarHostState() }
    var editor by remember { mutableStateOf<RemoteConnection?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var showNewFolder by remember { mutableStateOf(false) }
    var renameLocal by remember { mutableStateOf<RemoteConnection?>(null) }
    val context = LocalContext.current
    val addLocal = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        vm.saveConnection(
            RemoteConnection(
                id = UUID.randomUUID().toString(),
                name = SafFileSystem.label(context, uri),
                protocol = RemoteProtocol.SAF,
                host = uri.toString(),
                port = 0,
                anonymous = true,
                savePassword = false,
            ),
        )
    }
    val termux = viewModel<TermuxViewModel>().session
    fun openSsh(c: RemoteConnection, dir: String?) = termux.openSsh(c.host, c.port, c.username, dir)

    ui.openLocalFile?.let { path ->
        LaunchedEffect(path) {
            onOpenLocalFile(path)
            vm.consumeOpenLocalFile()
        }
    }
    ui.message?.let {
        LaunchedEffect(it) {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    BackHandler {
        when {
            ui.session != null && ui.session!!.path != "/" -> vm.up()
            ui.session != null -> vm.disconnect()
            else -> onBack()
        }
    }

    Scaffold(
        topBar = {
            val session = ui.session
            TopAppBar(
                title = {
                    Column {
                        Text(session?.connection?.name ?: stringResource(R.string.remote_title), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val sub = session?.path ?: pluralStringResource(R.plurals.remote_storages_count, ui.connections.size, ui.connections.size)
                        Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { if (ui.session != null) vm.disconnect() else onBack() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                actions = {
                    if (ui.session == null) {
                        IconButton(onClick = { addLocal.launch(SafFileSystem.termuxHome(context)) }) {
                            Icon(Icons.Rounded.CreateNewFolder, contentDescription = stringResource(R.string.remote_add_local))
                        }
                    }
                    if (ui.session != null) {
                        IconButton(onClick = vm::refresh) { Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.common_refresh)) }
                        var more by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { more = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.common_more)) }
                            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.common_new_folder)) },
                                    leadingIcon = { Icon(Icons.Rounded.CreateNewFolder, null) },
                                    onClick = { more = false; showNewFolder = true },
                                )
                                ui.session?.takeIf { it.connection.protocol == RemoteProtocol.SFTP }?.let { s ->
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.remote_ssh_here)) },
                                        leadingIcon = { Icon(Icons.Rounded.Terminal, null) },
                                        onClick = { more = false; openSsh(s.connection, s.path) },
                                    )
                                }
                            }
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (ui.session == null) {
                ExtendedFloatingActionButton(
                    text = { Text(stringResource(R.string.remote_add_storage)) },
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    onClick = { editor = null; showEditor = true },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val session = ui.session
            if (session == null) {
                ConnectionsList(
                    connections = ui.connections,
                    onConnect = { vm.connect(it) },
                    onSsh = { openSsh(it, null) },
                    onEdit = { if (it.protocol == RemoteProtocol.SAF) renameLocal = it else { editor = it; showEditor = true } },
                    onDelete = {
                        if (it.protocol == RemoteProtocol.SAF) SafFileSystem.release(context, it.host)
                        vm.deleteConnection(it.id)
                    },
                )
            } else {
                Browser(
                    entries = session.entries,
                    path = session.path,
                    onNavigate = vm::navigateTo,
                    onOpen = vm::open,
                    onDownload = { e -> if (localDir != null) vm.download(e, localDir) },
                    onRename = { entry, name -> vm.rename(entry, name) },
                    onDelete = vm::deleteEntry,
                )
            }
            if (ui.busy != null) BusyOverlay(ui.busy!!)
        }
    }

    renameLocal?.let { c ->
        var name by remember(c.id) { mutableStateOf(c.name) }
        AlertDialog(
            onDismissRequest = { renameLocal = null },
            title = { Text(stringResource(R.string.remote_rename_storage)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.common_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text(c.displayAddress, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.saveConnection(c.copy(name = name.trim().ifEmpty { c.name })); renameLocal = null }) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = { TextButton(onClick = { renameLocal = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }

    if (showEditor) {
        ConnectionEditorDialog(
            existing = editor,
            onSave = { vm.saveConnection(it); showEditor = false },
            onDismiss = { showEditor = false },
        )
    }
    if (showNewFolder && ui.session != null) {
        TextPromptDialog(
            title = stringResource(R.string.common_new_folder),
            label = stringResource(R.string.remote_folder_name),
            confirm = stringResource(R.string.common_create),
            onConfirm = { vm.makeDir(it); showNewFolder = false },
            onDismiss = { showNewFolder = false },
        )
    }
    if (ui.pendingUploads.isNotEmpty() && ui.busy == null) {
        val pending = ui.pendingUploads
        AlertDialog(
            onDismissRequest = { vm.discardPending(deleteCopies = false) },
            properties = DialogProperties(dismissOnClickOutside = false),
            title = { Text(stringResource(R.string.remote_upload_title)) },
            text = {
                val names = pending.joinToString("\n") { "• " + RemotePaths.name(it.remotePath) }
                val server = ui.session?.connection?.name ?: stringResource(R.string.remote_the_server)
                Text(
                    if (pending.size == 1) {
                        stringResource(R.string.remote_upload_one, RemotePaths.name(pending[0].remotePath), server)
                    } else {
                        stringResource(R.string.remote_upload_many, names, server)
                    },
                )
            },
            confirmButton = { TextButton(onClick = vm::uploadPending) { Text(stringResource(R.string.remote_upload)) } },
            dismissButton = {
                Row {
                    TextButton(onClick = { vm.discardPending(deleteCopies = true) }) { Text(stringResource(R.string.common_discard)) }
                    TextButton(onClick = { vm.discardPending(deleteCopies = false) }) { Text(stringResource(R.string.common_later)) }
                }
            },
        )
    }
    TerminalGate(termux, snackbar)
    ui.hostKeyChange?.let { change ->
        HostKeyChangedDialog(change.connection, change.old, change.presented, onTrust = vm::trustNewHostKey, onDismiss = vm::dismissHostKeyChange)
    }
    ui.needsPassword?.let { conn ->
        StoragePasswordDialog(
            connection = conn,
            onConfirm = { vm.connect(conn, passwordOverride = it) },
            onDismiss = vm::dismissPasswordPrompt,
        )
    }
}

@Composable
private fun ConnectionsList(
    connections: List<RemoteConnection>,
    onConnect: (RemoteConnection) -> Unit,
    onSsh: (RemoteConnection) -> Unit,
    onEdit: (RemoteConnection) -> Unit,
    onDelete: (RemoteConnection) -> Unit,
) {
    if (connections.isEmpty()) {
        ExpressiveEmptyState(
            stringResource(R.string.remote_empty_title),
            Modifier.fillMaxSize().padding(32.dp),
            icon = Icons.Rounded.Cloud,
            body = stringResource(R.string.remote_empty_body),
            maxHero = 168.dp,
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(connections, key = { it.id }) { c ->
            var menu by remember { mutableStateOf(false) }
            ListItem(
                leadingContent = { Icon(Icons.Rounded.Cloud, null, tint = MaterialTheme.colorScheme.primary) },
                content = { Text(c.name.ifBlank { c.host }) },
                supportingContent = { Text(c.displayAddress, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium) },
                trailingContent = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.common_more)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            if (c.protocol == RemoteProtocol.SFTP) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.remote_ssh_terminal)) }, leadingIcon = { Icon(Icons.Rounded.Terminal, null) }, onClick = { menu = false; onSsh(c) })
                            }
                            DropdownMenuItem(text = { Text(stringResource(R.string.common_edit)) }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { menu = false; onEdit(c) })
                            DropdownMenuItem(text = { Text(stringResource(R.string.common_delete)) }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = { menu = false; onDelete(c) })
                        }
                    }
                },
                modifier = Modifier.clickable { onConnect(c) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun Browser(
    entries: List<RemoteEntry>,
    path: String,
    onNavigate: (String) -> Unit,
    onOpen: (RemoteEntry) -> Unit,
    onDownload: (RemoteEntry) -> Unit,
    onRename: (RemoteEntry, String) -> Unit,
    onDelete: (RemoteEntry) -> Unit,
) {
    var renaming by remember { mutableStateOf<RemoteEntry?>(null) }
    var deleting by remember { mutableStateOf<RemoteEntry?>(null) }
    Column(Modifier.fillMaxSize()) {
        Breadcrumbs(path, onNavigate)
        HorizontalDivider()
        if (entries.isEmpty()) {
            ExpressiveEmptyState(stringResource(R.string.remote_empty_folder), Modifier.fillMaxSize(), art = Art.EMPTY_FOLDER, maxHero = 160.dp)
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(entries, key = { it.path }) { e ->
                    var menu by remember { mutableStateOf(false) }
                    ListItem(
                        leadingContent = {
                            Icon(if (e.isDirectory) Icons.Rounded.Folder else Icons.Rounded.Description, null, tint = if (e.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        },
                        content = { FileNameText(e.name, maxLines = 1) },
                        supportingContent = if (e.isDirectory) null else ({ Text(Formatters.size(e.size), style = MaterialTheme.typography.labelMedium) }),
                        trailingContent = {
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, stringResource(R.string.common_more)) }
                                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.remote_download)) }, leadingIcon = { Icon(Icons.Rounded.Download, null) }, onClick = { menu = false; onDownload(e) })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.common_rename)) }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { menu = false; renaming = e })
                                    DropdownMenuItem(text = { Text(stringResource(R.string.common_delete)) }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = { menu = false; deleting = e })
                                }
                            }
                        },
                        modifier = Modifier.clickable { onOpen(e) },
                    )
                    HorizontalDivider(Modifier.padding(start = 56.dp))
                }
            }
        }
    }
    deleting?.let { e ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.remote_delete_title, e.name)) },
            text = { Text(if (e.isDirectory) stringResource(R.string.remote_delete_folder_body) else stringResource(R.string.remote_delete_file_body)) },
            confirmButton = { TextButton(onClick = { onDelete(e); deleting = null }) { Text(stringResource(R.string.common_delete)) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.common_cancel)) } },
        )
    }
    renaming?.let { e ->
        TextPromptDialog(
            title = stringResource(R.string.common_rename),
            label = stringResource(R.string.remote_new_name),
            initial = e.name,
            confirm = stringResource(R.string.common_rename),
            onConfirm = { onRename(e, it); renaming = null },
            onDismiss = { renaming = null },
        )
    }
}

@Composable
private fun Breadcrumbs(path: String, onNavigate: (String) -> Unit) {
    val crumbs = RemotePaths.breadcrumbs(path)
    val rootLabel = stringResource(R.string.remote_root)
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        crumbs.forEachIndexed { i, crumb ->
            val label = if (crumb == "/") rootLabel else RemotePaths.name(crumb)
            Text(
                label,
                style = MaterialTheme.typography.labelLarge,
                color = if (i == crumbs.lastIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable { onNavigate(crumb) }.padding(horizontal = 4.dp, vertical = 2.dp),
            )
            if (i != crumbs.lastIndex) Text("/", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun BusyOverlay(label: String) {
    Surface(color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f), modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Surface(shape = MaterialTheme.shapes.large, tonalElevation = 4.dp) {
                Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    LoadingIndicator()
                    Text(label)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConnectionEditorDialog(
    existing: RemoteConnection?,
    onSave: (RemoteConnection) -> Unit,
    onDismiss: () -> Unit,
) {
    val protocols = RemoteProtocol.entries.filter { it != RemoteProtocol.SAF && it != RemoteProtocol.VAULT }
    var protocol by remember { mutableStateOf(existing?.protocol?.takeIf { it in protocols } ?: RemoteProtocol.FTP) }
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var host by remember { mutableStateOf(existing?.host ?: "") }
    var port by remember { mutableStateOf((existing?.port ?: protocol.defaultPort).toString()) }
    var username by remember { mutableStateOf(existing?.username ?: "") }
    var password by remember { mutableStateOf(existing?.password ?: "") }
    var anonymous by remember { mutableStateOf(existing?.anonymous ?: false) }
    var savePassword by remember { mutableStateOf(existing?.savePassword ?: true) }
    var folder by remember { mutableStateOf(existing?.initialPath?.takeIf { it != "/" } ?: "") }
    var scanning by remember { mutableStateOf(false) }
    var scanDone by remember { mutableStateOf(false) }
    val found = remember { mutableStateListOf<LanHost>() }
    var scanJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()
    DisposableEffect(Unit) { onDispose { scanJob?.cancel() } }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(if (existing == null) stringResource(R.string.remote_add_storage) else stringResource(R.string.remote_edit_storage)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    protocols.forEach { p ->
                        FilterChip(
                            selected = protocol == p,
                            onClick = {
                                val wasDefault = port == protocol.defaultPort.toString()
                                protocol = p
                                if (wasDefault) port = p.defaultPort.toString()
                            },
                            label = { Text(if (p == RemoteProtocol.WEBDAVS) "WebDAVS" else if (p == RemoteProtocol.WEBDAV) "WebDAV" else p.name) },
                        )
                    }
                }
                val ltr = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr)
                if (protocol == RemoteProtocol.SMB) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        FilledTonalButton(
                            enabled = !scanning,
                            onClick = {
                                found.clear(); scanDone = false; scanning = true
                                scanJob?.cancel()
                                scanJob = scope.launch {
                                    try { LanScanner.scan().collect { found += it } } finally { scanning = false; scanDone = true }
                                }
                            },
                        ) {
                            val orbit = rememberInfiniteTransition(label = "scan-orbit")
                            val angle by orbit.animateFloat(
                                initialValue = 0f,
                                targetValue = 360f,
                                animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
                                label = "scan-angle",
                            )
                            Icon(
                                Icons.Rounded.Search,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp).graphicsLayer {
                                    if (scanning) {
                                        val r = 3.dp.toPx()
                                        val a = Math.toRadians(angle.toDouble())
                                        translationX = (r * cos(a)).toFloat()
                                        translationY = (r * sin(a)).toFloat()
                                    }
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.remote_scan_network))
                        }
                        if (!scanning && scanDone && found.isEmpty()) {
                            Text(stringResource(R.string.remote_scan_none), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (found.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            found.sortedBy { h -> h.ip.substringAfterLast('.').toIntOrNull() ?: 0 }.forEach { h ->
                                AssistChip(
                                    onClick = {
                                        host = h.ip
                                        if (name.isBlank()) name = h.name ?: h.ip
                                    },
                                    label = { Text(if (h.name != null) "${h.name} · ${h.ip}" else h.ip) },
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.common_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("storage-name"))
                OutlinedTextField(host, { host = it }, label = { Text(stringResource(R.string.remote_host)) }, singleLine = true, textStyle = ltr, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    port, { port = it.filter(Char::isDigit).take(5) },
                    label = { Text(stringResource(R.string.remote_port)) }, singleLine = true, textStyle = ltr,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    folder, { folder = it.trim() },
                    label = { Text(stringResource(R.string.remote_folder_optional)) },
                    placeholder = {
                        Text(
                            when (protocol) {
                                RemoteProtocol.SMB -> stringResource(R.string.remote_smb_folder_hint)
                                RemoteProtocol.WEBDAV, RemoteProtocol.WEBDAVS -> "/remote.php/dav/files/you"
                                else -> "/"
                            },
                        )
                    },
                    singleLine = true,
                    textStyle = ltr,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = anonymous, onCheckedChange = { anonymous = it })
                    Text(if (protocol == RemoteProtocol.SMB) stringResource(R.string.remote_guest) else stringResource(R.string.remote_anonymous))
                }
                if (!anonymous) {
                    OutlinedTextField(
                        username, { username = it },
                        label = { Text(stringResource(R.string.remote_username)) },
                        supportingText = if (protocol == RemoteProtocol.SMB) ({ Text(stringResource(R.string.remote_smb_domain_hint)) }) else null,
                        singleLine = true,
                        textStyle = ltr,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        password, { password = it }, label = { Text(stringResource(R.string.common_password)) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = savePassword, onCheckedChange = { savePassword = it })
                        Text(stringResource(R.string.remote_save_password))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = host.isNotBlank(),
                onClick = {
                    onSave(
                        RemoteConnection(
                            id = existing?.id ?: "",
                            name = name.ifBlank { host },
                            protocol = protocol,
                            host = host.trim(),
                            port = port.toIntOrNull() ?: protocol.defaultPort,
                            username = username.trim(),
                            password = password,
                            anonymous = anonymous,
                            savePassword = savePassword,
                            initialPath = RemotePaths.normalize(folder.ifBlank { "/" }),
                            hostKey = existing?.hostKey?.takeIf { existing.host == host.trim() && existing.port == (port.toIntOrNull() ?: protocol.defaultPort) && existing.protocol == protocol }.orEmpty(),
                        ),
                    )
                },
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
private fun TextPromptDialog(
    title: String,
    label: String,
    initial: String = "",
    confirm: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(title) },
        text = {
            OutlinedTextField(text, { text = it }, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(enabled = text.isNotBlank(), onClick = { onConfirm(text.trim()) }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
