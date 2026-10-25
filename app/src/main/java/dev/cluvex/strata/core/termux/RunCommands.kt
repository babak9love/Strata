package dev.cluvex.strata.core.termux

import dev.cluvex.strata.R
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File

enum class RunScope { ONCE, FILE, PROJECT, TYPE }

data class RunTarget(val language: Language, val file: File?, val project: File?) {

    val dir: File get() = if (file != null) file.parentFile ?: File("/") else project ?: File("/")

    val extension: String? get() = file?.name?.substringAfterLast('.', "")?.lowercase()?.takeIf { it.isNotEmpty() }

    val scopes: List<RunScope>
        get() = if (file != null) listOfNotNull(RunScope.ONCE, RunScope.FILE, RunScope.TYPE.takeIf { extension != null }) else listOf(RunScope.ONCE, RunScope.PROJECT)

    companion object {
        fun forFile(language: Language, file: File) = RunTarget(language, file, TermuxLanguages.projectDir(language, file))
        fun forProject(project: Project) = RunTarget(project.kind.language, null, project.dir)
    }
}

object RunCommands {

    const val MAX = 200

    val VARIABLES: List<Pair<String, Int>> = listOf(
        "\$NAME" to R.string.termux_var_name,
        "\$FILE" to R.string.termux_var_file,
        "\$BASE" to R.string.termux_var_base,
        "\$DIR" to R.string.termux_var_dir,
        "\$PROJECT" to R.string.termux_var_project,
        "\$OUT" to R.string.termux_var_out,
    )

    fun fileKey(file: File) = "file:${file.path}"
    fun projectKey(dir: File) = "project:${dir.path}"
    fun typeKey(extension: String) = "type:${extension.lowercase()}"

    fun keyFor(scope: RunScope, target: RunTarget): String? = when (scope) {
        RunScope.ONCE -> null
        RunScope.FILE -> target.file?.let(::fileKey)
        RunScope.PROJECT -> if (target.file == null) target.project?.let(::projectKey) else null
        RunScope.TYPE -> target.extension?.let(::typeKey)
    }

    private val PRECEDENCE = listOf(RunScope.FILE, RunScope.PROJECT, RunScope.TYPE)

    fun saved(commands: Map<String, String>, target: RunTarget): Pair<RunScope, String>? {
        for (scope in PRECEDENCE) {
            val key = keyFor(scope, target) ?: continue
            commands[key]?.takeIf { it.isNotBlank() }?.let { return scope to it }
        }
        return null
    }

    fun defaultCommand(target: RunTarget): String {
        val file = target.file
        if (file == null) {
            val project = target.project?.let { TermuxProjects.detect(it, maxUp = 0) }
            return project?.let { TermuxProjects.commandTemplate(it) } ?: TermuxLanguages.commandTemplate(target.language)
        }
        val inProject = target.project != null && target.language.projectRunScript != null
        return if (inProject) "cd \"\$PROJECT\" && " + TermuxLanguages.projectTemplate(target.language) else TermuxLanguages.commandTemplate(target.language)
    }

    fun command(target: RunTarget, command: String, interactive: Boolean): TermuxCommand {
        val file = target.file
        val label = "Run ${file?.name ?: target.dir.name}"
        val args = arrayOf(
            target.dir.path,
            file?.path.orEmpty(),
            file?.name.orEmpty(),
            file?.let { it.name.substringBeforeLast('.', it.name) }.orEmpty(),
            target.project?.path.orEmpty(),
            command,
        )
        return TermuxCommand.bash(if (interactive) PRELOAD else BACKGROUND, *args, background = !interactive, label = label)
    }

    private val ENV = """
        export DIR="§1" FILE="§2" NAME="§3" BASE="§4" PROJECT="§5" OUT="§TMPDIR/strata-run"
        $TERMINAL_BUILD_ENV
    """.trimIndent()

    val BACKGROUND: String = """
        cd -- "§1" || exit 1
        $ENV
        eval "§6"
    """.trimIndent().replace('§', '$')

    val PRELOAD: String = """
        cd -- "§1" 2>/dev/null || printf 'Termux cannot open %s - allow storage with termux-setup-storage\n' "§1"
        $ENV
        export STRATA_CMD="§6"
        if [ -n "§BASH_VERSION" ] && [ -t 0 ]; then
          printf '\033[2m— ready to run; edit if you like, then press Enter —\033[0m\n'
          IFS= read -e -p '§ ' -i "§STRATA_CMD" __strata_line && { history -s "§__strata_line"; eval "§__strata_line"; }
        fi
        exec bash -i
    """.trimIndent().replace('§', '$')

    fun remember(commands: Map<String, String>, target: RunTarget, scope: RunScope, command: String): Map<String, String> {
        val key = keyFor(scope, target) ?: return commands
        val cleared = PRECEDENCE.takeWhile { it != scope }.mapNotNull { keyFor(it, target) }.fold(commands) { map, k -> with(map, k, null) }
        val without = with(cleared, key, null)
        val fallback = saved(without, target)?.second ?: defaultCommand(target)
        return if (command.trim() == fallback.trim()) without else with(cleared, key, command)
    }

    fun forget(commands: Map<String, String>, target: RunTarget, scope: RunScope): Map<String, String> =
        keyFor(scope, target)?.let { with(commands, it, null) } ?: commands

    fun with(commands: Map<String, String>, key: String, command: String?): Map<String, String> {
        val next = LinkedHashMap(commands)
        next.remove(key)
        if (!command.isNullOrBlank()) next[key] = command.trim()
        while (next.size > MAX) next.remove(next.keys.first())
        return next
    }

    fun encode(commands: Map<String, String>): String =
        JsonObject(commands.mapValues { JsonPrimitive(it.value) }).toString()

    fun decode(text: String?): Map<String, String> {
        if (text.isNullOrBlank()) return emptyMap()
        return runCatching {
            Json.parseToJsonElement(text).jsonObject.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { k to it } }.toMap(LinkedHashMap())
        }.getOrDefault(emptyMap())
    }
}
