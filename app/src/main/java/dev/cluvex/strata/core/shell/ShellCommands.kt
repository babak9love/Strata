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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import dev.cluvex.strata.core.root.RootManager
import dev.cluvex.strata.core.shizuku.ShizukuManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

/** Commands plus the group names in display order (including groups that hold no commands). */
data class ShellBundle(val commands: List<SavedCommand>, val groups: List<String>)

/** Saved commands live in a small JSON file in the app's private storage. */
class ShellCommandStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "shell_commands.json")
    /** Last readable, non-empty version of [file]; used when the file itself can't be read. */
    private val backup = File(file.parentFile, file.name + ".bak")
    /** A copy of an unreadable [file], kept so its text can still be recovered by hand. */
    private val corrupt = File(file.parentFile, file.name + ".corrupt")
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    // Only the export file is indented, like the settings backup; the private store file stays compact.
    private val pretty = Json { prettyPrint = true }
    private val serializer = ListSerializer(SavedCommand.serializer())

    private fun parse(f: File): List<SavedCommand>? = runCatching {
        if (!f.exists()) null else json.decodeFromString(serializer, f.readText())
    }.getOrNull()

    /**
     * An unreadable file must never end up as an empty list that the next save writes back over it: its text is
     * copied aside and the last good backup is used instead.
     */
    fun load(): List<SavedCommand> {
        if (!file.exists()) return emptyList()
        parse(file)?.let { return it }
        runCatching { file.copyTo(corrupt, overwrite = true) }
        return parse(backup) ?: emptyList()
    }

    /** The export file: an indented JSON object with the commands under "shellCommands" and the group order under "shellGroups". */
    fun encode(commands: List<SavedCommand>, groups: List<String> = emptyList()): String {
        val root = mutableMapOf<String, JsonElement>("shellCommands" to json.encodeToJsonElement(serializer, commands))
        if (groups.isNotEmpty()) root["shellGroups"] = JsonArray(groups.map { JsonPrimitive(it) })
        return pretty.encodeToString(JsonObject.serializer(), JsonObject(root))
    }

    /**
     * Reads an export file: a bare list, or an object with a "shellCommands" list (this export, or a full settings
     * backup) and, optionally, a "shellGroups" list. Files without groups give an empty group list.
     */
    fun decodeBundle(text: String): ShellBundle? = runCatching {
        val root = json.parseToJsonElement(text)
        val list = when (root) {
            is JsonArray -> root
            is JsonObject -> root["shellCommands"] as? JsonArray
            else -> null
        } ?: return@runCatching null
        val groups = ((root as? JsonObject)?.get("shellGroups") as? JsonArray).orEmpty().mapNotNull { e ->
            val p = e as? JsonPrimitive
            if (p != null && p.isString) p.content.trim().ifEmpty { null } else null
        }.distinct()
        ShellBundle(json.decodeFromJsonElement(serializer, list), groups)
    }.getOrNull()

    fun decode(text: String): List<SavedCommand>? = decodeBundle(text)?.commands

    fun save(commands: List<SavedCommand>) {
        runCatching {
            val text = json.encodeToString(serializer, commands)
            // Keep the last readable, non-empty version, so a bad write or an accidental clear never costs the whole list.
            if (parse(file)?.isNotEmpty() == true) runCatching { file.copyTo(backup, overwrite = true) }
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
    /** Extra time the app waits for the watchdog to end a command (TERM, a 2 s pause, then KILL). */
    private const val KILL_GRACE_MS = 8_000L

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

    /** libsu has one shared root shell that runs one job at a time; waiting for it must not eat into a command's timeout. */
    private val rootLock = Mutex()

    /**
     * Output has stderr merged in. With [ShellMode.AUTO] Shizuku (shell user) is tried first, then root.
     * [onOutput], when given, also receives the output in pieces while the command is still running.
     *
     * With a [runId] the command runs under a watchdog: it can be stopped with [stopRun], and when the time is up the
     * command and everything it started are ended (a plain timeout would only stop waiting for it).
     */
    suspend fun run(
        command: String,
        mode: ShellMode = ShellMode.AUTO,
        timeoutSec: Int = DEFAULT_TIMEOUT_SEC,
        onOutput: ((String) -> Unit)? = null,
        runId: String? = null,
    ): ShellResult {
        val timeoutMs = timeoutSec.coerceIn(MIN_TIMEOUT_SEC, MAX_TIMEOUT_SEC) * 1000L
        val hard = runId != null
        val script = if (runId != null) withWatchdog(command, timeoutMs / 1000, runPidPath(runId)) else command
        if (mode != ShellMode.ROOT) {
            ShizukuManager.refresh()
            if (ShizukuManager.state.value != ShizukuManager.Status.NOT_RUNNING) {
                val started = System.nanoTime()
                // With the watchdog the command ends by itself; the service's own timer is only the safety net.
                val wait = if (hard) timeoutMs + KILL_GRACE_MS else timeoutMs
                val r = if (onOutput != null) ShizukuManager.execStreaming(script, wait, onOutput) else ShizukuManager.exec(script, wait)
                r?.let {
                    val killed = hard && endedByWatchdog(it.exitCode, started, timeoutMs)
                    val timedOut = killed || it.exitCode == TIMEOUT_CODE
                    return ShellResult(ShellBackend.SHIZUKU, if (killed) TIMEOUT_CODE else it.exitCode, it.output.trimEnd(), timedOut)
                }
            }
        }
        if (mode != ShellMode.SHIZUKU && (RootManager.isGranted || RootManager.request())) {
            rootLock.withLock { runAsRoot(script, timeoutMs, onOutput, hard) }?.let { return it }
        }
        return ShellResult(null, -1, "")
    }

    /** 143 and 137 are TERM and KILL; together with the elapsed time they tell the watchdog's kill from a normal exit. */
    private fun endedByWatchdog(code: Int, startedNanos: Long, timeoutMs: Long): Boolean =
        (code == 143 || code == 137) && (System.nanoTime() - startedNanos) / 1_000_000 >= timeoutMs - 500

    /** Pid file of a command run with a [run] id; the watchdog writes it and [stopRun] reads it. */
    private fun runPidPath(id: String): String = "/data/local/tmp/strata_${detachedKey(id)}.run.pid"

    /**
     * Wraps [command] so it runs in its own session, with a watchdog that ends the whole process group after
     * [timeoutSec] seconds: TERM first, KILL two seconds later. The group's id is written to [pidFile], which is how
     * [stopRun] finds it. The command's exit code is passed on, so a command that was ended this way finishes with
     * 143 or 137. The wrapper runs in a subshell, so no variables leak into the shared root shell.
     */
    private fun withWatchdog(command: String, timeoutSec: Long, pidFile: String): String {
        val quoted = "'" + command.replace("'", "'\\''") + "'"
        return listOf(
            "(",
            "__c=$quoted",
            "if command -v setsid >/dev/null 2>&1; then",
            "setsid sh -c \"\$__c\" &",
            "else",
            "sh -c \"\$__c\" &",
            "fi",
            "__p=\$!",
            "echo \$__p > '$pidFile' 2>/dev/null",
            "(",
            "trap 'kill \$__s 2>/dev/null; exit 0' TERM",
            "sleep $timeoutSec &",
            "__s=\$!",
            "wait \$__s",
            "kill -s TERM -- -\$__p 2>/dev/null || kill -s TERM \$__p 2>/dev/null",
            "sleep 2",
            "kill -s KILL -- -\$__p 2>/dev/null || kill -s KILL \$__p 2>/dev/null",
            ") &",
            "__w=\$!",
            "wait \$__p",
            "__r=\$?",
            "kill \$__w 2>/dev/null",
            "rm -f '$pidFile' 2>/dev/null",
            "exit \$__r",
            ")",
        ).joinToString("\n")
    }

    /** Null when the root shell could not run the command at all. [script] is already wrapped when [hard] is set. */
    private suspend fun runAsRoot(script: String, timeoutMs: Long, onOutput: ((String) -> Unit)?, hard: Boolean): ShellResult? {
        // With live output the text is collected here, because the shell hands the lines over instead of keeping them.
        val live = if (onOutput != null) StringBuilder() else null
        val started = System.nanoTime()
        // libsu's exec blocks and can't be interrupted. With the watchdog the command ends by itself; this wait is
        // only the safety net (and the only timeout without the watchdog), after which the command is just reported.
        val job = CoroutineScope(Dispatchers.IO).async {
            runCatching {
                if (onOutput == null) {
                    RootManager.exec(script)
                } else {
                    RootManager.execStreaming(script) { line ->
                        synchronized(live!!) { live.append(line).append('\n') }
                        onOutput(line + "\n")
                    }
                }
            }.getOrNull()
        }
        val wait = if (hard) timeoutMs + KILL_GRACE_MS else timeoutMs
        val finished = withTimeoutOrNull(wait) { job.await() to true } ?: (null to false)
        if (!finished.second) {
            val partial = if (live != null) synchronized(live) { live.toString() } else ""
            return ShellResult(ShellBackend.ROOT, TIMEOUT_CODE, partial.trimEnd(), timedOut = true)
        }
        val r = finished.first ?: return null
        val text = (if (live != null) synchronized(live) { live.toString() } else (r.out + r.err).joinToString("\n")).trimEnd()
        val killed = hard && endedByWatchdog(r.code, started, timeoutMs)
        return if (killed) ShellResult(ShellBackend.ROOT, TIMEOUT_CODE, text, timedOut = true) else ShellResult(ShellBackend.ROOT, r.code, text)
    }

    /**
     * Stops a command that was started with a [run] id: TERM to its whole process group, then KILL if it is still
     * there a second later. True when something was stopped.
     */
    suspend fun stopRun(id: String, mode: ShellMode): Boolean {
        val pid = runPidPath(id)
        val script = "p=\$(cat '$pid' 2>/dev/null); " +
            "if [ -n \"\$p\" ] && kill -0 \$p 2>/dev/null; then " +
            "kill -s TERM -- -\$p 2>/dev/null || kill -s TERM \$p 2>/dev/null; sleep 1; " +
            "if kill -0 \$p 2>/dev/null; then kill -s KILL -- -\$p 2>/dev/null || kill -s KILL \$p 2>/dev/null; fi; " +
            "echo stopped; else echo idle; fi"
        // A shell-user process can't be signalled by root's counterpart and the other way round, so both are tried.
        if (mode != ShellMode.ROOT) {
            val r = run(script, ShellMode.SHIZUKU, 15)
            if (r.backend != null && r.output.trim().endsWith("stopped")) return true
        }
        if (mode != ShellMode.SHIZUKU && RootManager.isGranted) {
            // The shared root shell is busy running the command, so root gets its own short-lived `su` process.
            return runRootSeparate(script, 15_000L)?.trim()?.endsWith("stopped") == true
        }
        return false
    }

    /** Runs a script in a separate `su` process, next to whatever the shared root shell is doing. Null on failure. */
    private suspend fun runRootSeparate(script: String, timeoutMs: Long): String? = withContext(Dispatchers.IO) {
        runCatching {
            val process = ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
            // Closing the process ends the blocking read below if it takes too long.
            val killer = CoroutineScope(Dispatchers.IO).launch {
                delay(timeoutMs)
                process.destroy()
            }
            val text = process.inputStream.bufferedReader().readText()
            killer.cancel()
            text
        }.getOrNull()
    }

    /**
     * The ids (out of [ids], all of background commands) whose process is still alive, checked with one call through
     * [mode]. Null when the check could not run, so a failed check is not mistaken for "nothing is running".
     */
    suspend fun runningDetachedIds(ids: Collection<String>, mode: ShellMode): Set<String>? {
        if (ids.isEmpty()) return emptySet()
        val byKey = ids.associateBy { detachedKey(it) }
        val script = "for k in ${byKey.keys.joinToString(" ")}; do " +
            "p=\$(cat /data/local/tmp/strata_\$k.pid 2>/dev/null); " +
            "if [ -n \"\$p\" ] && kill -0 \$p 2>/dev/null; then echo \$k; fi; done"
        val r = run(script, mode, 15)
        if (r.backend == null) return null
        val keys = r.output.lines().map { it.trim() }.toSet()
        return byKey.filterKeys { it in keys }.values.toSet()
    }

    /** Like [runningDetachedIds] for saved [commands], each checked through its own backend mode. */
    suspend fun runningDetached(commands: Collection<SavedCommand>): Set<String>? {
        val alive = mutableSetOf<String>()
        for ((mode, group) in commands.groupBy { it.mode }) {
            alive += runningDetachedIds(group.map { it.id }, mode) ?: return null
        }
        return alive
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
