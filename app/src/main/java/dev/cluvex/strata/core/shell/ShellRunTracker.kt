package dev.cluvex.strata.core.shell

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** A command that is running right now, with the end of its output so far. */
data class ActiveRun(
    val commandId: String,
    val name: String,
    val mode: ShellMode,
    val source: String,
    val startedAt: Long,
    val output: String = "",
    /** Id the history entry of this run will get, so the live item and the saved entry are the same list item. */
    val entryId: String = "",
)

/**
 * The commands running right now (from the app or from shortcuts) and their output so far. The shell screen and the
 * history both read it, so a running command can be followed from either, and a shortcut run shows up too.
 * Output can arrive many times a second; the published text is refreshed a few times a second instead.
 */
object ShellRunTracker {
    private const val KEEP_CHARS = 20_000
    private const val PUBLISH_MS = 200L

    private class Buffer {
        val text = StringBuilder()
        val dirty = AtomicBoolean(false)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val buffers = ConcurrentHashMap<String, Buffer>()
    private val _runs = MutableStateFlow<Map<String, ActiveRun>>(emptyMap())
    private var ticker: Job? = null

    /** The running commands by command id. */
    val runs: StateFlow<Map<String, ActiveRun>> = _runs.asStateFlow()

    fun start(command: SavedCommand, source: String, entryId: String = "") {
        buffers[command.id] = Buffer()
        _runs.update { it + (command.id to ActiveRun(command.id, command.name, command.mode, source, System.currentTimeMillis(), entryId = entryId)) }
        ensureTicker()
    }

    /** Adds output; only the end of very long output is kept. */
    fun append(id: String, chunk: String) {
        val b = buffers[id] ?: return
        synchronized(b.text) {
            b.text.append(chunk)
            if (b.text.length > KEEP_CHARS * 2) b.text.delete(0, b.text.length - KEEP_CHARS)
        }
        b.dirty.set(true)
    }

    fun finish(id: String) {
        buffers.remove(id)
        _runs.update { it - id }
    }

    @Synchronized
    private fun ensureTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (buffers.isNotEmpty()) {
                delay(PUBLISH_MS)
                for ((id, b) in buffers) {
                    if (!b.dirty.getAndSet(false)) continue
                    val text = synchronized(b.text) { b.text.toString() }
                    _runs.update { m -> m[id]?.let { m + (id to it.copy(output = text)) } ?: m }
                }
            }
        }
    }
}
