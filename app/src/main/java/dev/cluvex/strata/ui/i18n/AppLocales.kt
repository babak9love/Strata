package dev.cluvex.strata.ui.i18n

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import dev.cluvex.strata.core.i18n.AppLanguage
import dev.cluvex.strata.core.i18n.L10n
import java.util.Locale

object AppLocales {

    private const val PREFS = "strata_locale"
    private const val KEY = "tag"

    fun current(context: Context): AppLanguage =
        if (Build.VERSION.SDK_INT >= 33) {
            val locales = runCatching { context.getSystemService(LocaleManager::class.java)?.applicationLocales }.getOrNull()
            if (locales == null || locales.isEmpty) AppLanguage.SYSTEM else AppLanguage.of(locales[0].toLanguageTag())
        } else {
            AppLanguage.of(stored(context))
        }

    fun set(activity: Activity, language: AppLanguage) {
        if (Build.VERSION.SDK_INT >= 33) {
            activity.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (language == AppLanguage.SYSTEM) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(language.tag)
        } else {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, language.tag).commit()
            applyTo(activity.applicationContext)
            activity.recreate()
        }
    }

    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= 33) return base
        val locale = override(base) ?: return base
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        return base.createConfigurationContext(config)
    }

    fun applyTo(app: Context) {
        if (Build.VERSION.SDK_INT >= 33) return
        val locale = override(app) ?: Resources.getSystem().configuration.locales[0]
        Locale.setDefault(locale)
        val res = app.resources
        val config = Configuration(res.configuration)
        config.setLocale(locale)
        config.setLayoutDirection(locale)
        @Suppress("DEPRECATION")
        res.updateConfiguration(config, res.displayMetrics)
    }

    fun install(app: Context) {
        applyTo(app)
        L10n.source = AndroidStrings(app)
    }

    private fun stored(context: Context): String? =
        runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) }.getOrNull()

    private fun override(context: Context): Locale? {
        val language = AppLanguage.of(stored(context))
        return if (language == AppLanguage.SYSTEM) null else Locale.forLanguageTag(language.tag)
    }
}

class AndroidStrings(private val context: Context) : L10n.Source {
    override fun text(id: Int, args: Array<out Any?>): String =
        if (args.isEmpty()) context.resources.getString(id) else context.resources.getString(id, *args)

    override fun plural(id: Int, count: Int, args: Array<out Any?>): String =
        if (args.isEmpty()) context.resources.getQuantityText(id, count).toString() else context.resources.getQuantityString(id, count, *args)
}
