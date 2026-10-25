package dev.cluvex.strata.feature.share

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.cluvex.strata.R
import dev.cluvex.strata.ui.asPath
import kotlinx.coroutines.delay
import java.io.File
import java.security.SecureRandom

private fun randomPassword(length: Int = 8): String {
    val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
    val r = SecureRandom()
    return String(CharArray(length) { alphabet[r.nextInt(alphabet.length)] })
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ShareScreen(currentFolder: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val status by ShareService.state.collectAsStateWithLifecycle()
    var ip by remember { mutableStateOf(LanAddress.current(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(3000)
            ip = LanAddress.current(context)
        }
    }
    BackHandler(onBack = onBack)
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun start(request: ShareRequest) {
        ShareService.clearError()
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        ShareService.start(context, request)
    }

    Scaffold(
        modifier = Modifier.testTag("share-screen"),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.share_title)) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.common_back)) } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(if (ip != null) Icons.Rounded.Wifi else Icons.Rounded.WifiOff, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    ip?.let { stringResource(R.string.share_this_phone, it) } ?: stringResource(R.string.share_not_on_wifi),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("share-ip"),
                )
            }
            status.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("share-error")) }

            WebCard(status.web, currentFolder, enabled = ip != null, onStart = ::start, onStop = { ShareService.stopWeb(context) })
            FtpCard(status.ftp, currentFolder, enabled = ip != null, onStart = ::start, onStop = { ShareService.stopFtp(context) })

            Text(
                stringResource(R.string.share_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ShareCard(icon: ImageVector, title: String, tag: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), modifier = Modifier.fillMaxWidth().testTag(tag)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

@Composable
private fun Running(share: RunningShare, how: String, onStop: () -> Unit) {
    SelectionContainer {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(share.url, style = MaterialTheme.typography.headlineSmall.asPath(), fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
            share.user?.let { Text(stringResource(R.string.share_user_value, it), fontFamily = FontFamily.Monospace) }
            Text(stringResource(R.string.share_password_value, share.password), fontFamily = FontFamily.Monospace)
            if (share.guests) Text(stringResource(R.string.share_guests_value), style = MaterialTheme.typography.bodySmall)
        }
    }
    val sharing = if (share.readOnly) stringResource(R.string.share_sharing_read_only, share.folder) else stringResource(R.string.share_sharing, share.folder)
    Text("$how\n$sharing", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedButton(onClick = onStop, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.common_stop)) }
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, onValueChange = onChange), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

private fun folderProblem(folder: String): Int? = File(folder).let { if (!it.isDirectory) R.string.share_no_such_folder else if (!it.canRead()) R.string.share_cant_read_folder else null }

@Composable
private fun WebCard(running: RunningShare?, currentFolder: String, enabled: Boolean, onStart: (ShareRequest) -> Unit, onStop: () -> Unit) {
    ShareCard(Icons.Rounded.Public, stringResource(R.string.share_browser_access), "share-web") {
        if (running != null) {
            Running(running, stringResource(R.string.share_web_how), onStop)
            return@ShareCard
        }
        var folder by rememberSaveable { mutableStateOf(currentFolder) }
        var port by rememberSaveable { mutableStateOf("8080") }
        var readOnly by rememberSaveable { mutableStateOf(false) }
        val problem = folderProblem(folder)
        val ltr = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr)
        Text(stringResource(R.string.share_web_description), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(folder, { folder = it.trim() }, label = { Text(stringResource(R.string.common_folder)) }, singleLine = true, textStyle = ltr, isError = problem != null, supportingText = problem?.let { id -> { Text(stringResource(id)) } }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text(stringResource(R.string.remote_port)) }, singleLine = true, textStyle = ltr, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        Toggle(stringResource(R.string.share_read_only_download), readOnly) { readOnly = it }
        Button(
            onClick = { onStart(ShareRequest.Web(folder, port.toIntOrNull() ?: 8080, randomPassword(), readOnly)) },
            enabled = enabled && problem == null && (port.toIntOrNull() ?: 0) in 1024..65535,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.testTag("start-web"),
        ) { Text(stringResource(R.string.share_start)) }
    }
}

@Composable
private fun FtpCard(running: RunningShare?, currentFolder: String, enabled: Boolean, onStart: (ShareRequest) -> Unit, onStop: () -> Unit) {
    ShareCard(Icons.Rounded.Storage, stringResource(R.string.share_ftp_server), "share-ftp") {
        if (running != null) {
            Running(running, stringResource(R.string.share_ftp_how), onStop)
            return@ShareCard
        }
        var folder by rememberSaveable { mutableStateOf(currentFolder) }
        var port by rememberSaveable { mutableStateOf("2121") }
        var user by rememberSaveable { mutableStateOf("strata") }
        var password by rememberSaveable { mutableStateOf(randomPassword()) }
        var guests by rememberSaveable { mutableStateOf(false) }
        var readOnly by rememberSaveable { mutableStateOf(false) }
        val problem = folderProblem(folder)
        val ltr = LocalTextStyle.current.copy(textDirection = TextDirection.Ltr)
        OutlinedTextField(folder, { folder = it.trim() }, label = { Text(stringResource(R.string.common_folder)) }, singleLine = true, textStyle = ltr, isError = problem != null, supportingText = problem?.let { id -> { Text(stringResource(id)) } }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(port, { port = it.filter(Char::isDigit).take(5) }, label = { Text(stringResource(R.string.remote_port)) }, singleLine = true, textStyle = ltr, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        OutlinedTextField(user, { user = it.trim() }, label = { Text(stringResource(R.string.share_user)) }, singleLine = true, textStyle = ltr, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.common_password)) }, singleLine = true, textStyle = ltr, modifier = Modifier.fillMaxWidth())
        Toggle(stringResource(R.string.share_allow_guests), guests) { guests = it }
        Toggle(stringResource(R.string.share_read_only), readOnly) { readOnly = it }
        Button(
            onClick = { onStart(ShareRequest.Ftp(folder, port.toIntOrNull() ?: 2121, user, password, guests, readOnly)) },
            enabled = enabled && problem == null && user.isNotBlank() && !user.equals("anonymous", true) && password.isNotEmpty() &&
                (port.toIntOrNull() ?: 0) in 1024..65535,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.testTag("start-ftp"),
        ) { Text(stringResource(R.string.share_start)) }
    }
}
