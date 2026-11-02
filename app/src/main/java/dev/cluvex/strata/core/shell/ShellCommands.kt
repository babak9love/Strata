package dev.cluvex.strata.core.shell

import android.content.Context
import dev.cluvex.strata.core.root.RootManager
import dev.cluvex.strata.core.shizuku.ShizukuManager
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class SavedCommand(val id: String, val name: String, val command: String)

enum class ShellBackend { SHIZUKU, ROOT }

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
    private val json = Json { ignoreUnknownKeys = true }
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
    const val TIMEOUT_MS = 60_000L
    private const val TIMEOUT_CODE = -1

    /** Shizuku first (shell user), then root. Output has stderr merged in. */
    suspend fun run(command: String): ShellResult {
        ShizukuManager.refresh()
        if (ShizukuManager.state.value != ShizukuManager.Status.NOT_RUNNING) {
            ShizukuManager.exec(command, TIMEOUT_MS)?.let {
                return ShellResult(ShellBackend.SHIZUKU, it.exitCode, it.output.trimEnd(), timedOut = it.exitCode == TIMEOUT_CODE)
            }
        }
        if (RootManager.isGranted || RootManager.request()) {
            val r = runCatching { RootManager.exec(command) }.getOrNull()
            if (r != null) {
                return ShellResult(ShellBackend.ROOT, r.code, (r.out + r.err).joinToString("\n").trimEnd())
            }
        }
        return ShellResult(null, -1, "")
    }
}
