package dev.cluvex.strata.core.shell

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dev.cluvex.strata.R
import dev.cluvex.strata.feature.shell.ShellShortcutActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import dev.cluvex.strata.core.root.RootManager
import dev.cluvex.strata.core.shizuku.ShizukuManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class SavedCommand(
    val id: String,
    val name: String,
    val command: String,
    val shortcut: Boolean = false,
    val mode: ShellMode = ShellMode.AUTO,
    val timeoutSec: Int = ShellRunner.DEFAULT_TIMEOUT_SEC,
)

enum class ShellBackend { SHIZUKU, ROOT }

/** Which backend a command should use: Shizuku first then root (AUTO), or only one of them. */
enum class ShellMode { AUTO, SHIZUKU, ROOT }

/** [backend] is null when neither Shizuku nor root could run the command. */
data class ShellResult(
    val backend: ShellBackend?,
    val exitCode: Int,
    val output: String,
    val timedOut: Boolean = false,
)

/** Saved commands live in a small JSON file in the app's private storage. */
class ShellCommandStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "shell_commands.json")
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val serializer = ListSerializer(SavedCommand.serializer())

    fun load(): List<SavedCommand> = runCatching {
        if (!file.exists()) emptyList() else json.decodeFromString(serializer, file.readText())
    }.getOrDefault(emptyList())

    fun save(commands: List<SavedCommand>) {
        runCatching {
            val text = json.encodeToString(serializer, commands)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.writeText(text)
                tmp.delete()
            }
        }
    }
}

object ShellRunner {
    const val DEFAULT_TIMEOUT_SEC = 60
    const val MIN_TIMEOUT_SEC = 5
    const val MAX_TIMEOUT_SEC = 600
    private const val TIMEOUT_CODE = -1

    /** Output has stderr merged in. With [ShellMode.AUTO] Shizuku (shell user) is tried first, then root. */
    suspend fun run(command: String, mode: ShellMode = ShellMode.AUTO, timeoutSec: Int = DEFAULT_TIMEOUT_SEC): ShellResult {
        val timeoutMs = timeoutSec.coerceIn(MIN_TIMEOUT_SEC, MAX_TIMEOUT_SEC) * 1000L
        if (mode != ShellMode.ROOT) {
            ShizukuManager.refresh()
            if (ShizukuManager.state.value != ShizukuManager.Status.NOT_RUNNING) {
                ShizukuManager.exec(command, timeoutMs)?.let {
                    return ShellResult(ShellBackend.SHIZUKU, it.exitCode, it.output.trimEnd(), timedOut = it.exitCode == TIMEOUT_CODE)
                }
            }
        }
        if (mode != ShellMode.SHIZUKU && (RootManager.isGranted || RootManager.request())) {
            // libsu's exec blocks and can't be interrupted, so on a timeout the command is left running and only reported.
            val job = CoroutineScope(Dispatchers.IO).async { runCatching { RootManager.exec(command) }.getOrNull() }
            val finished = withTimeoutOrNull(timeoutMs) { job.await() to true } ?: (null to false)
            if (!finished.second) return ShellResult(ShellBackend.ROOT, TIMEOUT_CODE, "", timedOut = true)
            finished.first?.let { r ->
                return ShellResult(ShellBackend.ROOT, r.code, (r.out + r.err).joinToString("\n").trimEnd())
            }
        }
        return ShellResult(null, -1, "")
    }
}

/** Publishes the commands marked "show in the app-icon menu" as launcher shortcuts, named after the command. */
object ShellShortcuts {
    const val EXTRA_ID = "dev.cluvex.strata.extra.SHELL_COMMAND_ID"

    fun publish(context: Context, commands: List<SavedCommand>) {
        runCatching {
            val max = ShortcutManagerCompat.getMaxShortcutCountPerActivity(context)
            val shortcuts = commands.filter { it.shortcut }.take(max).mapIndexed { index, c ->
                val intent = Intent(context, ShellShortcutActivity::class.java).apply {
                    action = Intent.ACTION_VIEW
                    putExtra(EXTRA_ID, c.id)
                }
                ShortcutInfoCompat.Builder(context, "shell_" + c.id)
                    .setShortLabel(c.name)
                    .setLongLabel(c.name)
                    .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_shell))
                    .setIntent(intent)
                    .setRank(index)
                    .build()
            }
            ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
        }
    }
}
