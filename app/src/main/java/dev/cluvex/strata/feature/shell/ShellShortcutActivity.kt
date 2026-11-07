package dev.cluvex.strata.feature.shell

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import dev.cluvex.strata.R
import dev.cluvex.strata.core.shell.ShellCommandStore
import dev.cluvex.strata.core.shell.ShellExecutor
import dev.cluvex.strata.core.shell.ShellLogSource
import dev.cluvex.strata.core.shell.ShellParams
import dev.cluvex.strata.core.shell.ShellResult
import dev.cluvex.strata.core.shell.ShellShortcuts
import dev.cluvex.strata.core.shell.SavedCommand
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal sealed interface Banner {
    data class Running(val name: String) : Banner
    data class Done(val name: String, val ok: Boolean, val detail: String?) : Banner
}

/**
 * Target of the launcher shortcuts. It has no screen of its own: it looks the command up by id (so an intent
 * can never carry its own command text), runs it, shows a small banner at the top of the screen, records the
 * run in the log and finishes. The window lets touches through to whatever is behind it.
 */
class ShellShortcutActivity : ComponentActivity() {
    private val banner = MutableStateFlow<Banner?>(null)
    private val confirmFor = MutableStateFlow<SavedCommand?>(null)
    private val pickFor = MutableStateFlow<SavedCommand?>(null)
    private val pickAnswer = CompletableDeferred<String?>()
    private val answer = CompletableDeferred<Boolean>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        setContent {
            val current by banner.collectAsState()
            val asking by confirmFor.collectAsState()
            val picking by pickFor.collectAsState()
            picking?.let { c ->
                MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                    ShellAppPickerPlainDialog(
                        title = stringResource(R.string.shell_param_pick_title, c.name),
                        lastPackage = ShellParams.lastPackage(applicationContext, c.id),
                        onPick = { pickAnswer.complete(it) },
                        onDismiss = { pickAnswer.complete(null) },
                    )
                }
            }
            asking?.let { c ->
                MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                    AlertDialog(
                        onDismissRequest = { answer.complete(false) },
                        title = { Text(stringResource(R.string.shell_confirm_title, c.name)) },
                        text = { Text(c.command, maxLines = 8) },
                        confirmButton = { TextButton(onClick = { answer.complete(true) }) { Text(stringResource(R.string.shell_run)) } },
                        dismissButton = { TextButton(onClick = { answer.complete(false) }) { Text(stringResource(R.string.common_cancel)) } },
                    )
                }
            }
            Box(
                Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).padding(start = 16.dp, end = 16.dp, top = 8.dp),
                contentAlignment = Alignment.TopCenter,
            ) {
                current?.let { BannerPill(it) }
            }
        }

        val id = intent.getStringExtra(ShellShortcuts.EXTRA_ID)
        if (id == null) {
            finish()
            return
        }
        lifecycleScope.launch {
            val stored = withContext(Dispatchers.IO) {
                ShellCommandStore(applicationContext).load().firstOrNull { it.id == id }
            }
            if (stored == null) {
                banner.value = Banner.Done(getString(R.string.shell_not_found), ok = false, detail = null)
                delay(2_500)
                finish()
                return@launch
            }
            var command: SavedCommand = stored
            if (ShellParams.needsPackage(command.command)) {
                // A command with {package} asks which app first; the shortcut cannot carry that choice itself.
                window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                pickFor.value = command
                val pkg = pickAnswer.await()
                pickFor.value = null
                if (pkg == null) {
                    finish()
                    return@launch
                }
                ShellParams.rememberPackage(applicationContext, command.id, pkg)
                command = ShellParams.resolve(command, pkg)
                window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            }
            if (command.confirm) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                confirmFor.value = command
                val ok = answer.await()
                confirmFor.value = null
                if (!ok) {
                    finish()
                    return@launch
                }
                window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
            }
            if (command.showStatus) banner.value = Banner.Running(command.name)
            var result = ShellExecutor.runLogged(applicationContext, command, ShellLogSource.SHORTCUT)
            if (!command.showStatus) {
                finish()
                return@launch
            }
            var stillRunning = false
            if (command.detached && result.backend != null && !result.timedOut && result.exitCode == 0) {
                // For a background command "started" is all runLogged knows. A command that fails at once is the usual
                // case, so give it a few seconds: if it ends, the banner shows how it really ended; if not, it is running.
                val end = ShellExecutor.awaitDetachedEnd(applicationContext, command, DETACHED_BANNER_WAIT_MS)
                if (end == null) stillRunning = true
                else result = result.copy(exitCode = end.exitCode, timedOut = end.timedOut, stopped = end.stopped)
            }
            banner.value = if (stillRunning) Banner.Done(command.name, ok = true, detail = getString(R.string.shell_log_running)) else done(command.name, result)
            delay(if (result.backend != null && !result.timedOut && result.exitCode == 0) 1_800 else 3_000)
            finish()
        }
    }

    private companion object {
        /** How long the banner of a background command waits to see whether it ends right away. */
        const val DETACHED_BANNER_WAIT_MS = 5_000L
    }

    private fun done(name: String, result: ShellResult): Banner.Done = when {
        result.stopped -> Banner.Done(name, ok = true, detail = getString(R.string.shell_log_stopped))
        result.backend == null -> Banner.Done(name, ok = false, detail = getString(R.string.shell_banner_no_backend))
        result.timedOut -> Banner.Done(name, ok = false, detail = getString(R.string.shell_log_timed_out))
        result.exitCode != 0 -> Banner.Done(
            name,
            ok = false,
            // Same words as the history: "Failed · exit code 1".
            detail = getString(R.string.shell_log_failed) + getString(R.string.common_separator) + getString(R.string.shell_exit_code, result.exitCode),
        )
        else -> Banner.Done(name, ok = true, detail = null)
    }
}

@Composable
private fun BannerPill(banner: Banner) {
    Surface(shape = RoundedCornerShape(28.dp), color = Color(0xEB1E1E1E), shadowElevation = 6.dp) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (banner) {
                is Banner.Running -> {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                    Text(banner.name, color = Color.White)
                }
                is Banner.Done -> Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = if (banner.ok) Color(0xFF7BE08B) else Color(0xFFFF7A70), fontWeight = FontWeight.Bold)) {
                            append(if (banner.ok) "✓" else "✗")
                        }
                        append(" ")
                        append(banner.name)
                        banner.detail?.let { append(" · "); append(it) }
                    },
                    color = Color.White,
                )
            }
        }
    }
}
