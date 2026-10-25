package dev.cluvex.strata.feature.editor

import android.content.Context
import android.content.res.AssetManager
import dev.cluvex.strata.core.text.EditorTheme
import dev.cluvex.strata.core.text.GrammarCatalog
import dev.cluvex.strata.core.text.GrammarEntry
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.DefaultGrammarDefinition
import io.github.rosemoe.sora.langs.textmate.registry.model.GrammarDefinition
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import org.eclipse.tm4e.core.registry.IGrammarSource
import org.eclipse.tm4e.core.registry.IThemeSource

object TextMateEngine {

    private val lock = Any()

    @Volatile
    private var ready = false
    private lateinit var assets: AssetManager
    private val loadedScopes = HashSet<String>()

    lateinit var catalog: GrammarCatalog
        private set
    lateinit var themes: List<EditorTheme>
        private set

    fun init(context: Context) {
        if (ready) return
        synchronized(lock) {
            if (ready) return
            assets = context.applicationContext.assets
            GrammarRegistry.getInstance()
            FileProviderRegistry.getInstance().addFileProvider(AssetsFileResolver(assets))
            catalog = GrammarCatalog.parse(read("textmate/languages.json"), read("textmate/filetypes.json"))
            themes = GrammarCatalog.parseThemes(read("textmate/themes.json"))
            val registry = ThemeRegistry.getInstance()
            for (t in themes) {
                val stream = FileProviderRegistry.getInstance().tryGetInputStream(t.path)
                registry.loadTheme(ThemeModel(IThemeSource.fromInputStream(stream, t.path, null), t.id).apply { isDark = t.dark }, false)
            }
            registry.setTheme(themes.first().id)
            ready = true
        }
    }

    fun theme(id: String): EditorTheme? = themes.firstOrNull { it.id == id }

    fun activateTheme(id: String): ThemeModel {
        val registry = ThemeRegistry.getInstance()
        if (registry.currentThemeModel?.name != id) registry.setTheme(id)
        return registry.currentThemeModel
    }

    fun language(scope: String, autoComplete: Boolean = true): TextMateLanguage {
        synchronized(lock) {
            ThemeRegistry.getInstance().currentThemeModel?.let { GrammarRegistry.getInstance().setTheme(it) }
            val needed = catalog.closure(scope) { includesOf(it) }.filter { it.scopeName !in loadedScopes }
            if (needed.isNotEmpty()) {
                GrammarRegistry.getInstance().loadGrammars(needed.map(::definition))
                loadedScopes += needed.map { it.scopeName }
            }
        }
        return TextMateLanguage.create(scope, autoComplete)
    }

    private fun definition(e: GrammarEntry): GrammarDefinition {
        val source = IGrammarSource.fromInputStream(FileProviderRegistry.getInstance().tryGetInputStream(e.grammar), e.grammar, null)
        val def = if (e.languageConfiguration != null) {
            DefaultGrammarDefinition.withLanguageConfiguration(source, e.languageConfiguration, e.name, e.scopeName)
        } else {
            DefaultGrammarDefinition.withGrammarSource(source, e.name, e.scopeName)
        }
        return if (e.embeddedLanguages.isEmpty()) def else def.withEmbeddedLanguages(e.embeddedLanguages)
    }

    private fun includesOf(entry: GrammarEntry): Set<String> =
        runCatching { GrammarCatalog.includedScopes(read(entry.grammar)) }.getOrDefault(emptySet())

    private fun read(path: String): String = assets.open(path).bufferedReader().use { it.readText() }
}
