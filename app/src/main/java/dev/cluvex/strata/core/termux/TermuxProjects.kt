package dev.cluvex.strata.core.termux

import androidx.annotation.StringRes
import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr
import java.io.File

data class ProjectKind(
    val id: String,
    private val name: String,
    val languageId: String,
    val markers: List<String>,
    val runScript: String,
    val tools: Set<String>,
    val searchUp: Boolean = true,
    @StringRes private val nameRes: Int? = null,
) {
    val label: String get() = nameRes?.let { tr(it) } ?: name

    val language: Language get() = TermuxLanguages.byId(languageId)!!
}

data class Project(val kind: ProjectKind, val dir: File)

data class ProjectTemplate(
    val id: String,
    val title: String,
    val languageId: String,
    val command: String,
    val nameRule: Regex,
    @StringRes val nameHint: Int,
    @StringRes val nameProblem: Int,
    val script: String,
) {
    val language: Language get() = TermuxLanguages.byId(languageId)!!
}

object TermuxProjects {

    private val NAME = Regex("^[A-Za-z][A-Za-z0-9_-]{0,63}$")
    private val NAME_HINT = R.string.termux_name_hint
    private val NAME_PROBLEM = R.string.termux_name_problem
    private val NPM_NAME = Regex("^[a-z][a-z0-9_-]{0,63}$")
    private val NPM_HINT = R.string.termux_name_hint_npm
    private val NPM_PROBLEM = R.string.termux_name_problem_npm

    val kinds: List<ProjectKind> = listOf(
        ProjectKind(
            "cargo", "Rust · Cargo", "rust", listOf("Cargo.toml"),
            TermuxLanguages.byId("rust")!!.projectRunScript!!, setOf("cargo"),
        ),
        ProjectKind("go", "Go module", "go", listOf("go.mod"), "cd \"$1\" && go run .", setOf("go"), nameRes = R.string.termux_kind_go),
        ProjectKind(
            "deno", "Deno", "typescript", listOf("deno.json", "deno.jsonc"),
            "cd \"$1\" && if [ -f main.ts ]; then deno run -q -A main.ts; else deno task start; fi", setOf("deno"),
        ),
        ProjectKind(
            "npm", "Node.js · npm", "javascript", listOf("package.json"),
            "cd \"$1\" && if grep -q '\"start\"[[:space:]]*:' package.json; then npm start --silent; else node .; fi",
            setOf("npm", "node"),
        ),
        ProjectKind("dart", "Dart", "dart", listOf("pubspec.yaml"), TermuxLanguages.byId("dart")!!.projectRunScript!!, setOf("dart")),
        ProjectKind("zig", "Zig", "zig", listOf("build.zig"), TermuxLanguages.byId("zig")!!.projectRunScript!!, setOf("zig")),
        ProjectKind("python", "Python", "python", listOf("main.py"), "cd \"$1\" && python main.py", setOf("python"), searchUp = false),
    )

    val templates: List<ProjectTemplate> = listOf(
        ProjectTemplate(
            "rust", "Rust", "rust", "cargo new <name>", NAME, NAME_HINT, NAME_PROBLEM,
            "cd \"$1\" && cargo new --quiet -- \"$2\"",
        ),
        ProjectTemplate(
            "go", "Go", "go", "go mod init <name> + main.go", NAME, NAME_HINT, NAME_PROBLEM,
            "cd \"$1\" && mkdir -- \"$2\" && cd -- \"$2\" && go mod init \"$2\" && " +
                "printf '%s\\n' 'package main' '' 'import \"fmt\"' '' 'func main() {' '\tfmt.Println(\"Hello from Go!\")' '}' > main.go",
        ),
        ProjectTemplate(
            "npm", "Node.js", "javascript", "npm init -y + index.js", NPM_NAME, NPM_HINT, NPM_PROBLEM,
            "cd \"$1\" && mkdir -- \"$2\" && cd -- \"$2\" && npm init -y >/dev/null && " +
                "npm pkg set scripts.start=\"node index.js\" && printf '%s\\n' 'console.log(\"Hello from Node.js!\");' > index.js",
        ),
        ProjectTemplate(
            "python", "Python", "python", "main.py", NAME, NAME_HINT, NAME_PROBLEM,
            "cd \"$1\" && mkdir -- \"$2\" && printf '%s\\n' 'def main():' '    print(\"Hello from Python!\")' '' '' " +
                "'if __name__ == \"__main__\":' '    main()' > \"$2/main.py\"",
        ),
        ProjectTemplate(
            "dart", "Dart", "dart", "dart create <name>", NAME, NAME_HINT, NAME_PROBLEM,
            "cd \"$1\" && dart create --no-pub \"$2\"",
        ),
        ProjectTemplate(
            "zig", "Zig", "zig", "zig init", NAME, NAME_HINT, NAME_PROBLEM,
            "cd \"$1\" && mkdir -- \"$2\" && cd -- \"$2\" && zig init",
        ),
    )

    fun template(id: String): ProjectTemplate? = templates.firstOrNull { it.id == id }

    fun validate(template: ProjectTemplate, name: String, existing: Collection<String>): String? = when {
        name.isBlank() -> tr(R.string.termux_enter_name)
        !template.nameRule.matches(name) -> tr(template.nameProblem)
        existing.any { it.equals(name, ignoreCase = true) } -> tr(R.string.termux_name_exists, name)
        else -> null
    }

    fun createCommand(template: ProjectTemplate, parent: File, name: String): TermuxCommand =
        TermuxCommand.bash(template.script, parent.path, name, label = "New ${template.title} project")

    fun detect(dir: File, maxUp: Int = 4): Project? {
        kinds.firstOrNull { k -> k.markers.any { File(dir, it).isFile } }?.let { return Project(it, dir) }
        var up = dir.parentFile
        var depth = 1
        while (up != null && depth <= maxUp) {
            val here = up
            kinds.firstOrNull { k -> k.searchUp && k.markers.any { File(here, it).isFile } }?.let { return Project(it, here) }
            up = up.parentFile
            depth++
        }
        return null
    }

    fun runCommand(project: Project, interactive: Boolean): TermuxCommand {
        val label = "Run ${project.dir.name}"
        return if (!interactive) {
            TermuxCommand.bash(project.kind.runScript, project.dir.path, label = label)
        } else {
            TermuxLanguages.preloaded(interactiveLine(project), label)
        }
    }

    fun interactiveLine(project: Project): String = interactiveScript(project).replace("\"\$1\"", TermuxLanguages.shellQuote(project.dir.path))

    fun commandTemplate(project: Project): String = interactiveScript(project).removePrefix("cd \"\$1\" && ")

    private fun interactiveScript(project: Project): String {
        return when (project.kind.id) {
            "cargo" -> "cd \"$1\" && cargo run"
            "npm" -> if (hasStartScript(project.dir)) "cd \"$1\" && npm start" else "cd \"$1\" && node ."
            "deno" -> if (File(project.dir, "main.ts").isFile) "cd \"$1\" && deno run -A main.ts" else "cd \"$1\" && deno task start"
            "zig" -> "cd \"$1\" && zig build run --prefix \"\$TMPDIR/strata-zig-out\""
            else -> project.kind.runScript
        }
    }

    private fun hasStartScript(dir: File): Boolean =
        runCatching { Regex("\"start\"\\s*:").containsMatchIn(File(dir, "package.json").readText()) }.getOrDefault(false)

    fun terminalCommand(dir: File): TermuxCommand = TermuxCommand.bash(
        "cd -- \"$1\" 2>/dev/null || printf 'Termux cannot open %s - allow storage with termux-setup-storage\\n' \"$1\"; " +
            TERMINAL_BUILD_ENV.replace('§', '$') + "; exec bash -i",
        dir.path, background = false, label = "Terminal: ${dir.name.ifEmpty { "/" }}",
    )
}
