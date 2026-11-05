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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.File

@Serializable
data class SavedCommand(
    val id: String,
    val name: String,
    val command: String,
    val shortcut: Boolean = false,
    val mode: ShellMode = ShellMode.AUTO,
    val timeoutSec: Int = ShellRunner.DEFAULT_TIMEOUT_SEC,
    /** Runs the command as an independent background process that survives Strata or Shizuku being killed. */
    val detached: Boolean = false,
    /** Asks for confirmation before running. */
    val confirm: Boolean = false,
    /** Shows the status banner at the top of the screen while a shortcut runs this command. */
    val showStatus: Boolean = true,
    /** Name of the group this command is listed under; empty = not in a group. */
    val group: String = "",
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
    // Only the export file is indented, like the settings backup; the private store file stays compact.
    private val pretty = Json { prettyPrint = true }
    private val serializer = ListSerializer(SavedCommand.serializer())

    fun load(): List<SavedCommand> = runCatching {
        if (!file.exists()) emptyList() else json.decodeFromString(serializer, file.readText())
    }.getOrDefault(emptyList())

    /** The export file: an indented JSON object holding the commands under "shellCommands", as in the settings backup. */
    fun encode(commands: List<SavedCommand>): String =
        pretty.encodeToString(JsonObject.serializer(), JsonObject(mapOf("shellCommands" to json.encodeToJsonElement(serializer, commands))))

    /** Reads an export file: a bare list, or an object with a "shellCommands" list (this export, or a full settings backup). */
    fun decode(text: String): List<SavedCommand>? = runCatching {
        val root = json.parseToJsonElement(text)
        val list = when (root) {
            is JsonArray -> root
            is JsonObject -> root["shellCommands"] as? JsonArray
            else -> null
        } ?: return@runCatching null
        json.decodeFromJsonElement(serializer, list)
    }.getOrNull()

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

    /**
     * Wraps [command] so it runs as its own background process: the text is written to a script file and started
     * with setsid, so killing Strata (or Shizuku) afterwards does not interrupt it. Its output goes to a log file.
     */
    private fun detachedKey(id: String): String = id.filter { it.isLetterOrDigit() }.take(8).ifEmpty { "cmd" }

    /** Path of the log file that [detach] writes this command's output to. */
    fun detachedLogPath(id: String): String = "/data/local/tmp/strata_${detachedKey(id)}.log"

    fun detachedPidPath(id: String): String = "/data/local/tmp/strata_${detachedKey(id)}.pid"

    /** True while the background process started by [detach] for this command is still alive. */
    suspend fun isDetachedRunning(id: String, mode: ShellMode): Boolean {
        val pid = detachedPidPath(id)
        val r = run("p=\$(cat '$pid' 2>/dev/null); if [ -n \"\$p\" ] && kill -0 \$p 2>/dev/null; then echo 1; else echo 0; fi", mode, 10)
        return r.backend != null && r.output.trim() == "1"
    }

    /** Stops the background process (and its children): TERM first, then KILL if it is still alive. */
    suspend fun stopDetached(id: String, mode: ShellMode): Boolean {
        val pid = detachedPidPath(id)
        val cmd = "p=\$(cat '$pid' 2>/dev/null); " +
            "if [ -n \"\$p\" ] && kill -0 \$p 2>/dev/null; then " +
            "kill -s TERM -- -\$p 2>/dev/null || kill -s TERM \$p; sleep 1; " +
            "if kill -0 \$p 2>/dev/null; then kill -s KILL -- -\$p 2>/dev/null || kill -s KILL \$p; fi; " +
            "echo stopped; else echo idle; fi"
        val r = run(cmd, mode, 15)
        return r.backend != null && r.output.trim().endsWith("stopped")
    }

    /** Reads the tail of a background log file with the same backend that started it; null if it can't be read. */
    suspend fun readFile(path: String, mode: ShellMode, maxBytes: Int = 20_000): String? {
        val r = run("tail -c $maxBytes '$path'", mode, 10)
        return if (r.backend != null && !r.timedOut && r.exitCode == 0) r.output else null
    }

    /** Removes the script and log that [detach] left in /data/local/tmp for this command. */
    fun detachedCleanup(id: String): String {
        val key = detachedKey(id)
        return "rm -f /data/local/tmp/strata_$key.sh /data/local/tmp/strata_$key.log /data/local/tmp/strata_$key.pid"
    }

    /**
     * Removes the script, log and pid files of the given background commands, except those whose
     * process is still running (their files are needed to read the output or stop them). Best effort.
     */
    suspend fun cleanupIdleDetached(ids: Collection<String>, mode: ShellMode = ShellMode.AUTO) {
        val keys = ids.map { detachedKey(it) }.distinct()
        if (keys.isEmpty()) return
        val cmd = "for k in ${keys.joinToString(" ")}; do " +
            "f=/data/local/tmp/strata_\$k; p=\$(cat \$f.pid 2>/dev/null); " +
            "if [ -n \"\$p\" ] && kill -0 \$p 2>/dev/null; then continue; fi; " +
            "rm -f \$f.sh \$f.log \$f.pid; done"
        runCatching { run(cmd, mode, 15) }
    }

    fun detach(id: String, command: String): String {
        val key = detachedKey(id)
        val script = "/data/local/tmp/strata_$key.sh"
        val log = "/data/local/tmp/strata_$key.log"
        val pid = "/data/local/tmp/strata_$key.pid"
        var delim = "STRATA_EOF"
        while (command.contains(delim)) delim += "_"
        return buildString {
            append("cat > ").append(script).append(" <<'").append(delim).append("'\n")
            // The script records its own PID (it leads its own session), so the app can stop it later.
            append("echo \$\$ > ").append(pid).append('\n')
            append(command.trimEnd('\n', '\r')).append('\n')
            append(delim).append('\n')
            append("setsid nohup sh ").append(script).append(" > ").append(log).append(" 2>&1 &\n")
            append("echo \"Running in the background. Log: ").append(log).append("\"")
        }
    }

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

    /** Asks the launcher to pin a shortcut for [command] on the home screen; false if the launcher can't do it. */
    fun pin(context: Context, command: SavedCommand): Boolean = runCatching {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return@runCatching false
        val intent = Intent(context, ShellShortcutActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            putExtra(EXTRA_ID, command.id)
        }
        val info = ShortcutInfoCompat.Builder(context, "shell_pin_" + command.id)
            .setShortLabel(command.name)
            .setLongLabel(command.name)
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_shell))
            .setIntent(intent)
            .build()
        ShortcutManagerCompat.requestPinShortcut(context, info, null)
    }.getOrDefault(false)

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
