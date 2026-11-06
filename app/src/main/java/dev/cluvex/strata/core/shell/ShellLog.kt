package dev.cluvex.strata.core.shell

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    /** The person stopped the command with Stop. */
    val stopped: Boolean = false,
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

    /** Records that a background command was stopped by hand, and how long it had run. */
    fun markStopped(id: String, durationMs: Long) = synchronized(LOCK) {
        write(read().map { if (it.id == id) it.copy(stopped = true, durationMs = durationMs) else it })
    }

    /** Records how a background command really ended: its exit code and how long it ran. */
    fun markFinished(id: String, exitCode: Int, durationMs: Long) = synchronized(LOCK) {
        write(read().map { if (it.id == id) it.copy(exitCode = exitCode, durationMs = durationMs) else it })
    }

    /** A background command's entry is saved when it starts; this records that its watchdog later stopped it. */
    fun markTimedOut(id: String, durationMs: Long) = synchronized(LOCK) {
        write(read().map { if (it.id == id) it.copy(timedOut = true, durationMs = durationMs) else it })
    }

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
    /** The backend an entry was run with, as a mode to talk to it again; null when nothing ran it. */
    fun modeOf(backend: String?): ShellMode? = when (backend) {
        "ROOT" -> ShellMode.ROOT
        "SHIZUKU" -> ShellMode.SHIZUKU
        else -> null
    }

    /**
     * A background command's entry is written when it starts, so how it really ended (timed out, stopped by hand,
     * its exit code and run time) is read from the files its script left, once it has stopped running.
     * Saves the result on the entry and returns the updated entry; null when nothing is known (yet).
     */
    suspend fun finalizeDetached(store: ShellLogStore, e: LogEntry, mode: ShellMode): LogEntry? {
        if (e.logFile == null) return null
        val end = ShellRunner.detachedEnd(e.commandId, mode) ?: return null
        if (end.timeoutSec != null) {
            // The watchdog stopped it: shown as "Timed out", with the limit as its time.
            val ms = end.timeoutSec * 1000L
            withContext(Dispatchers.IO) { store.markTimedOut(e.id, ms) }
            return e.copy(timedOut = true, durationMs = ms)
        }
        if (end.stoppedAtSec != null) {
            // Stop was pressed while it ran: shown as "Stopped", with the time it had run.
            val ms = (end.stoppedAtSec * 1000L - e.time).coerceAtLeast(0L)
            withContext(Dispatchers.IO) { store.markStopped(e.id, ms) }
            return e.copy(stopped = true, durationMs = ms)
        }
        val code = end.code ?: return null
        val ms = end.secs * 1000L
        withContext(Dispatchers.IO) { store.markFinished(e.id, code, ms) }
        return e.copy(exitCode = code, durationMs = ms)
    }

    /**
     * Waits up to [windowMs] for the background command just started to end (a command that fails at once is
     * the common case) and returns its finalized entry; null when it is still running or nothing is known.
     */
    suspend fun awaitDetachedEnd(context: Context, command: SavedCommand, windowMs: Long): LogEntry? {
        val store = ShellLogStore(context)
        val entry = withContext(Dispatchers.IO) {
            store.load().filter { it.commandId == command.id && it.logFile != null }.maxByOrNull { it.time }
        } ?: return null
        val mode = modeOf(entry.backend) ?: return null
        val until = SystemClock.elapsedRealtime() + windowMs
        while (true) {
            delay(400)
            if (!ShellRunner.isDetachedRunning(command.id, mode)) return finalizeDetached(store, entry, mode) ?: entry
            if (SystemClock.elapsedRealtime() >= until) return null
        }
    }

    suspend fun runLogged(context: Context, command: SavedCommand, source: String): ShellResult =
        withContext(NonCancellable) {
            val started = SystemClock.elapsedRealtime()
            // A normal command is followed live (the tracker feeds the live view and the history) and can be stopped;
            // a background command only prints a short "started" message and has its log file instead.
            val live = !command.detached
            val entryId = UUID.randomUUID().toString()
            if (live) ShellRunTracker.start(command, source, entryId)
            val result = try {
                ShellRunner.run(
                    if (command.detached) ShellRunner.detach(command.id, command.command, command.timeoutSec) else command.command,
                    command.mode,
                    // The launcher of a background command only needs the default; its real limit lives in the script.
                    if (command.detached && command.timeoutSec <= ShellRunner.NO_TIMEOUT) ShellRunner.DEFAULT_TIMEOUT_SEC else command.timeoutSec,
                    if (live) ({ chunk: String -> ShellRunTracker.append(command.id, chunk) }) else null,
                    runId = if (live) command.id else null,
                )
            } catch (t: Throwable) {
                if (live) ShellRunTracker.finish(command.id)
                throw t
            }
            val took = SystemClock.elapsedRealtime() - started
            val output = if (result.output.length > ShellLogStore.MAX_OUTPUT) {
                result.output.take(ShellLogStore.MAX_OUTPUT) + "\n…"
            } else {
                result.output
            }
            withContext(Dispatchers.IO) {
                ShellLogStore(context).append(
                    LogEntry(
                        id = entryId,
                        time = System.currentTimeMillis(),
                        commandId = command.id,
                        name = command.name,
                        command = command.command,
                        backend = result.backend?.name,
                        exitCode = result.exitCode,
                        timedOut = result.timedOut,
                        stopped = result.stopped,
                        durationMs = took,
                        output = output,
                        source = source,
                        logFile = if (command.detached) ShellRunner.detachedLogPath(command.id) else null,
                    ),
                )
            }
            // After the entry is saved, so a history that reloads when a run ends already finds it.
            if (live) ShellRunTracker.finish(command.id)
            result.copy(durationMs = took)
        }
}
