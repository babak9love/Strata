package dev.cluvex.strata.core.text

import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class GrammarEntry(
    val name: String,
    val scopeName: String,
    val grammar: String,
    val languageConfiguration: String? = null,
    val embeddedLanguages: Map<String, String> = emptyMap(),
)

@Serializable
private data class LanguagesFile(val languages: List<GrammarEntry>)

@Serializable
private data class FileTypesFile(
    val extensions: Map<String, String> = emptyMap(),
    val filenames: Map<String, String> = emptyMap(),
    val scopes: Map<String, String> = emptyMap(),
)

@Serializable
data class EditorTheme(val id: String, val name: String, val dark: Boolean, val path: String)

@Serializable
private data class ThemesFile(val themes: List<EditorTheme>)

data class EditorLanguage(val name: String, val scopeName: String) {
    val label: String get() = if (name == "jsonc") tr(R.string.editor_language_jsonc) else LABELS[name] ?: name.replaceFirstChar { it.uppercase() }

    private companion object {
        val LABELS = mapOf(
            "shellscript" to "Shell", "javascript" to "JavaScript", "typescript" to "TypeScript", "csharp" to "C#",
            "cpp" to "C++", "c" to "C", "objective-c" to "Objective-C", "json" to "JSON",
            "xml" to "XML", "xsl" to "XSL", "html" to "HTML", "css" to "CSS", "scss" to "SCSS", "less" to "Less",
            "php" to "PHP", "sql" to "SQL", "yaml" to "YAML", "ini" to "INI", "bat" to "Batch", "r" to "R",
            "powershell" to "PowerShell", "dockerfile" to "Dockerfile", "makefile" to "Makefile",
        )
    }
}

class GrammarCatalog(
    val entries: List<GrammarEntry>,
    private val extensions: Map<String, String>,
    private val filenames: Map<String, String>,
    private val scopes: Map<String, String>,
) {
    private val byScope = entries.associateBy { it.scopeName }
    private val byName = entries.associateBy { it.name }

    val languages: List<EditorLanguage> =
        scopes.map { (name, scope) -> EditorLanguage(name, scope) }.sortedBy { it.label.trimStart('\u200F').lowercase() }

    fun language(name: String): EditorLanguage? = scopes[name]?.let { EditorLanguage(name, it) }

    fun languageFor(fileName: String): EditorLanguage? {
        val lower = fileName.lowercase()
        val name = filenames[lower]
            ?: lower.substringAfterLast('.', "").takeIf { it.isNotEmpty() && lower.contains('.') }?.let { extensions[it] }
            ?: return null
        return language(name)
    }

    fun entryForScope(scope: String): GrammarEntry? = byScope[scope]

    fun closure(scope: String, includes: (GrammarEntry) -> Set<String>): List<GrammarEntry> {
        val ordered = LinkedHashSet<GrammarEntry>()
        val visiting = HashSet<String>()
        fun visit(s: String) {
            val entry = byScope[s] ?: return
            if (entry in ordered || !visiting.add(s)) return
            val deps = includes(entry) + entry.embeddedLanguages.values.mapNotNull { byName[it]?.scopeName ?: scopes[it] } +
                entry.embeddedLanguages.keys.filter { it in byScope }
            deps.filter { it != s }.forEach(::visit)
            ordered += entry
        }
        visit(scope)
        return ordered.toList()
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(languagesJson: String, fileTypesJson: String): GrammarCatalog {
            val langs = json.decodeFromString<LanguagesFile>(languagesJson).languages
            val types = json.decodeFromString<FileTypesFile>(fileTypesJson)
            return GrammarCatalog(langs, types.extensions, types.filenames, types.scopes)
        }

        fun parseThemes(themesJson: String): List<EditorTheme> = json.decodeFromString<ThemesFile>(themesJson).themes

        fun includedScopes(grammarText: String): Set<String> =
            INCLUDE.findAll(grammarText).map { it.groupValues[1].substringBefore('#') }.toSet() +
                PLIST_INCLUDE.findAll(grammarText).map { it.groupValues[1].substringBefore('#') }.toSet()

        private val INCLUDE = Regex("\"include\"\\s*:\\s*\"((?:source|text)\\.[^\"]+)\"")
        private val PLIST_INCLUDE = Regex("<key>include</key>\\s*<string>((?:source|text)\\.[^<]+)</string>")
    }
}
