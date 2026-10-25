package dev.cluvex.strata.core.termux

import androidx.annotation.StringRes
import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr
import java.io.File

enum class LibraryManager(val label: String, val script: String, val example: String) {
    PIP("pip", "cd \"$1\" && shift && pip install \"$@\"", "requests numpy"),
    NPM(
        "npm",
        "cd \"$1\" && case \"$1\" in /storage/*|/sdcard/*|/mnt/*) shift; npm install --no-bin-links \"$@\" ;; *) shift; npm install \"$@\" ;; esac",
        "lodash chalk",
    ),
    GEM("gem", "cd \"$1\" && shift && gem install \"$@\"", "nokogiri colorize"),
    CARGO("cargo add", "cd \"$1\" && shift && cargo add \"$@\"", "serde rand"),
    PKG("pkg", "shift && pkg install -y \"$@\"", "libcurl openssl"),
}

data class Language(
    val id: String,
    val name: String,
    val extensions: Set<String>,
    val packages: List<String>,
    val binary: String,
    val versionScript: String,
    val runScript: String,
    val libraries: LibraryManager? = null,
    val projectFile: String? = null,
    val projectRunScript: String? = null,
    @StringRes val nameRes: Int? = null,
) {
    val displayName: String get() = nameRes?.let { tr(it) } ?: name
}

private const val OUT = "\"\$TMPDIR/strata-run\""

internal const val TERMINAL_BUILD_ENV =
    "export CARGO_TARGET_DIR=\"§{CARGO_TARGET_DIR:-§TMPDIR/strata-cargo}\" " +
        "ZIG_LOCAL_CACHE_DIR=\"§{ZIG_LOCAL_CACHE_DIR:-§TMPDIR/zig-cache}\" " +
        "ZIG_GLOBAL_CACHE_DIR=\"§{ZIG_GLOBAL_CACHE_DIR:-§TMPDIR/zig-gcache}\""

private val PRELOAD_INTERACTIVE: String = """
    export STRATA_CMD=§1
    $TERMINAL_BUILD_ENV
    if [ -n "§BASH_VERSION" ] && [ -t 0 ]; then
      printf '\033[2m— ready to run; edit if you like, then press Enter —\033[0m\n'
      IFS= read -e -p '§ ' -i "§STRATA_CMD" __strata_line && { history -s "§__strata_line"; eval "§__strata_line"; }
    fi
    exec bash -i
""".trimIndent().replace('§', '$')

object TermuxLanguages {

    val all: List<Language> = listOf(
        Language(
            "python", "Python", setOf("py"), listOf("python"), "python",
            "python --version", "cd \"$1\" && python \"$2\"", LibraryManager.PIP,
        ),
        Language(
            "c", "C", setOf("c"), listOf("clang"), "clang",
            "clang --version | head -n1", "cd \"$1\" && clang \"$2\" -o $OUT -lm && $OUT", LibraryManager.PKG,
        ),
        Language(
            "cpp", "C++", setOf("cpp", "cc", "cxx", "c++"), listOf("clang"), "clang++",
            "clang++ --version | head -n1", "cd \"$1\" && clang++ \"$2\" -o $OUT && $OUT", LibraryManager.PKG,
        ),
        Language(
            "rust", "Rust", setOf("rs"), listOf("rust"), "rustc",
            "rustc --version", "cd \"$1\" && rustc \"$2\" -o $OUT && $OUT", LibraryManager.CARGO,
            projectFile = "Cargo.toml",
            projectRunScript = "cd \"$1\" && CARGO_TARGET_DIR=\"\${CARGO_TARGET_DIR:-\$TMPDIR/strata-cargo}\" cargo run --quiet",
        ),
        Language(
            "javascript", "JavaScript (Node.js)", setOf("js", "mjs", "cjs"), listOf("nodejs"), "node",
            "node --version", "cd \"$1\" && node \"$2\"", LibraryManager.NPM, projectFile = "package.json",
        ),
        Language(
            "go", "Go", setOf("go"), listOf("golang"), "go",
            "go version", "cd \"$1\" && go run \"$2\"",
        ),
        Language(
            "java", "Java", setOf("java"), listOf("openjdk-21"), "java",
            "java -version 2>&1 | head -n1", "cd \"$1\" && java \"$2\"",
        ),
        Language(
            "kotlin", "Kotlin script", setOf("kts"), listOf("kotlin", "openjdk-21"), "kotlinc",
            "kotlinc -version 2>&1 | tail -n1", "cd \"$1\" && kotlinc -script \"$2\"",
            nameRes = R.string.termux_lang_kotlin,
        ),
        Language(
            "ruby", "Ruby", setOf("rb"), listOf("ruby"), "ruby",
            "ruby --version", "cd \"$1\" && ruby \"$2\"", LibraryManager.GEM,
        ),
        Language(
            "php", "PHP", setOf("php"), listOf("php"), "php",
            "php --version | head -n1", "cd \"$1\" && php \"$2\"",
        ),
        Language(
            "perl", "Perl", setOf("pl"), listOf("perl"), "perl",
            "perl -e 'print \"perl \$^V\\n\"'", "cd \"$1\" && perl \"$2\"",
        ),
        Language(
            "lua", "Lua", setOf("lua"), listOf("lua54"), "lua",
            "lua -v", "cd \"$1\" && lua \"$2\"",
        ),
        Language(
            "bash", "Shell (bash)", setOf("sh", "bash"), emptyList(), "bash",
            "bash --version | head -n1", "cd \"$1\" && bash \"$2\"",
            nameRes = R.string.termux_lang_shell,
        ),
        Language(
            "typescript", "TypeScript (Deno)", setOf("ts", "tsx", "mts", "cts"), listOf("deno"), "deno",
            "deno --version | head -n1", "cd \"$1\" && deno run -q -A \"$2\"",
        ),
        Language(
            "dart", "Dart", setOf("dart"), listOf("dart"), "dart",
            "dart --version 2>&1 | head -n1", "cd \"$1\" && dart run \"$2\"",
            projectFile = "pubspec.yaml", projectRunScript = "cd \"$1\" && dart run",
        ),
        Language(
            "zig", "Zig", setOf("zig"), listOf("zig"), "zig",
            "zig version", "cd \"$1\" && zig run --cache-dir \"\$TMPDIR/zig-cache\" --global-cache-dir \"\$TMPDIR/zig-gcache\" \"$2\"",
            projectFile = "build.zig",
            projectRunScript = "cd \"$1\" && zig build run --prefix \"\$TMPDIR/strata-zig-out\" --cache-dir \"\$TMPDIR/zig-cache\" --global-cache-dir \"\$TMPDIR/zig-gcache\"",
        ),
        Language(
            "nim", "Nim", setOf("nim"), listOf("nim", "clang"), "nim",
            "nim --version 2>&1 | head -n1", "cd \"$1\" && nim r --hints:off --nimcache:\"\$TMPDIR/strata-nim\" \"$2\"",
        ),
        Language(
            "tcl", "Tcl", setOf("tcl"), listOf("tcl"), "tclsh",
            "echo 'puts [info patchlevel]' | tclsh", "cd \"$1\" && tclsh \"$2\"",
        ),
        Language(
            "erlang", "Erlang", setOf("erl"), listOf("erlang"), "escript",
            "erl -noshell -eval 'io:format(\"Erlang/OTP ~s~n\",[erlang:system_info(otp_release)])' -s init stop",
            "cd \"$1\" && escript \"$2\"",
        ),
        Language(
            "elixir", "Elixir", setOf("exs", "ex"), listOf("elixir"), "elixir",
            "elixir --version 2>&1 | tail -n1", "cd \"$1\" && elixir \"$2\"",
        ),
        Language(
            "scheme", "Scheme (Guile)", setOf("scm"), listOf("guile"), "guile",
            "guile --version | head -n1", "cd \"$1\" && guile --no-auto-compile \"$2\"",
        ),
        Language(
            "csharp", "C# (Mono)", setOf("cs"), listOf("mono"), "mcs",
            "mono --version | head -n1", "cd \"$1\" && mcs \"$2\" -out:$OUT.exe && mono $OUT.exe",
        ),
    )

    private val byExtension: Map<String, Language> = buildMap {
        for (l in all) for (e in l.extensions) put(e, l)
    }

    fun forFile(name: String): Language? = byExtension[name.substringAfterLast('.', "").lowercase()]

    fun byId(id: String): Language? = all.firstOrNull { it.id == id }

    fun projectDir(language: Language, file: File): File? {
        val marker = language.projectFile ?: return null
        var dir: File? = file.parentFile
        while (dir != null) {
            if (File(dir, marker).isFile) return dir
            dir = dir.parentFile
        }
        return null
    }

    fun runCommand(language: Language, file: File, interactive: Boolean): TermuxCommand {
        val project = projectDir(language, file)
        val label = "Run ${file.name}"
        val useProject = project != null && language.projectRunScript != null
        if (!interactive) {
            return if (useProject) {
                TermuxCommand.bash(language.projectRunScript!!, project!!.path, background = true, label = label)
            } else {
                TermuxCommand.bash(language.runScript, file.parent ?: "/", file.name, background = true, label = label)
            }
        }
        return preloaded(interactiveLine(language, file), label)
    }

    fun commandTemplate(language: Language): String =
        language.runScript.removePrefix("cd \"\$1\" && ").replace("\"\$2\"", "\"\$NAME\"").replace(OUT, "\"\$OUT\"")

    fun projectTemplate(language: Language): String =
        (language.projectRunScript ?: language.runScript).removePrefix("cd \"\$1\" && ").replace(Regex("^CARGO_TARGET_DIR=\"[^\"]*\" "), "")

    fun preloaded(line: String, label: String): TermuxCommand =
        TermuxCommand.bash(PRELOAD_INTERACTIVE, line, background = false, label = label)

    fun interactiveLine(language: Language, file: File): String {
        val project = projectDir(language, file)
        return if (project != null && language.projectRunScript != null) {
            language.projectRunScript.replace("\"\$1\"", shellQuote(project.path))
        } else {
            language.runScript
                .replace("\"\$1\"", shellQuote(file.parent ?: "/"))
                .replace("\"\$2\"", shellQuote(file.name))
        }
    }

    internal fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    fun programMissing(language: Language, result: TermuxResult, tools: Collection<String> = emptyList()): Boolean {
        if (result.issue != TermuxIssue.CommandNotFound) return false
        val names = tools + language.binary
        return result.stderr.lineSequence().any { line -> line.startsWith("strata: ") && names.any { line.endsWith(": $it: command not found") } }
    }

    fun installCommand(language: Language): TermuxCommand =
        TermuxCommand.bash("pkg install -y \"$@\"", *language.packages.toTypedArray(), background = false, label = "Install ${language.name}")

    fun libraryCommand(language: Language, file: File, names: List<String>): TermuxCommand? {
        val manager = language.libraries ?: return null
        val clean = names.map { it.trim() }.filter { it.isNotEmpty() }
        if (clean.isEmpty()) return null
        val dir = projectDir(language, file) ?: file.parentFile ?: File("/")
        return TermuxCommand.bash(manager.script, dir.path, *clean.toTypedArray(), background = false, label = "${manager.label} install")
    }

    fun detectionCommand(languages: List<Language> = all): TermuxCommand {
        val script = languages.joinToString("\n") { l ->
            "if command -v ${l.binary} >/dev/null 2>&1; then echo '@@${l.id}'; { ${l.versionScript}; } 2>&1 | head -n1; fi"
        }
        return TermuxCommand.bash(script, label = "Detect languages")
    }

    fun parseDetection(stdout: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val lines = stdout.lines()
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("@@")) {
                val id = line.removePrefix("@@")
                val next = lines.getOrNull(i + 1)?.trim().orEmpty()
                if (next.startsWith("@@")) {
                    out[id] = ""
                } else {
                    out[id] = next
                    i++
                }
            }
            i++
        }
        return out
    }
}
