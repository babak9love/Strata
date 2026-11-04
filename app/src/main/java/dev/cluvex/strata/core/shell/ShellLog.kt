package dev.cluvex.strata.core.shell

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

object ShellLogSource {
    const val APP = "app"
    const val SHORTCUT = "shortcut"
}

@Serializable
data class LogEntry(
    val id: String,
    val time: Long,
    val commandId: String,
    val name: String,
    val command: String,
    /** "SHIZUKU", "ROOT", or null when neither could run the command. */
    val backend: String?,
    val exitCode: Int,
    val timedOut: Boolean,
    val durationMs: Long,
    val output: String,
    val source: String = ShellLogSource.APP,
    /** Background log file the command writes to (only for commands run in the background). */
    val logFile: String? = null,
) {
    val ok: Boolean get() = backend != null && !timedOut && exitCode == 0
}

/** Execution history, newest first, kept in a small JSON file in the app's private storage. */
class ShellLogStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "shell_log.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(LogEntry.serializer())

    fun load(): List<LogEntry> = synchronized(LOCK) { read() }

    fun append(entry: LogEntry) = synchronized(LOCK) {
        write((listOf(entry) + read()).take(MAX_ENTRIES))
    }

    fun clear() = synchronized(LOCK) { file.delete(); Unit }

    private fun read(): List<LogEntry> = runCatching {
        if (!file.exists()) emptyList() else json.decodeFromString(serializer, file.readText())
    }.getOrDefault(emptyList())

    private fun write(entries: List<LogEntry>) {
        runCatching {
            val text = json.encodeToString(serializer, entries)
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(file)) {
                file.writeText(text)
                tmp.delete()
            }
        }
    }

    companion object {
        private val LOCK = Any()
        const val MAX_ENTRIES = 200
        const val MAX_OUTPUT = 20_000
    }
}

object ShellExecutor {
    /**
     * Runs a saved command and records it in the log. The run and the log write are not cancellable, so a
     * command started from a shortcut is still logged if its screen is gone before it finishes.
     */
    suspend fun runLogged(context: Context, command: SavedCommand, source: String): ShellResult =
        withContext(NonCancellable) {
            val started = SystemClock.elapsedRealtime()
            val result = ShellRunner.run(
                if (command.detached) ShellRunner.detach(command.id, command.command) else command.command,
                command.mode,
                command.timeoutSec,
            )
            val took = SystemClock.elapsedRealtime() - started
            val output = if (result.output.length > ShellLogStore.MAX_OUTPUT) {
                result.output.take(ShellLogStore.MAX_OUTPUT) + "\n…"
            } else {
                result.output
            }
            withContext(Dispatchers.IO) {
                ShellLogStore(context).append(
                    LogEntry(
                        id = UUID.randomUUID().toString(),
                        time = System.currentTimeMillis(),
                        commandId = command.id,
                        name = command.name,
                        command = command.command,
                        backend = result.backend?.name,
                        exitCode = result.exitCode,
                        timedOut = result.timedOut,
                        durationMs = took,
                        output = output,
                        source = source,
                        logFile = if (command.detached) ShellRunner.detachedLogPath(command.id) else null,
                    ),
                )
            }
            result
        }
}
