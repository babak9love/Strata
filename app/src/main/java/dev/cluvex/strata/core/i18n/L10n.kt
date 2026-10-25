package dev.cluvex.strata.core.i18n

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import java.util.ServiceLoader

object L10n {

    interface Source {
        fun text(@StringRes id: Int, args: Array<out Any?>): String
        fun plural(@PluralsRes id: Int, count: Int, args: Array<out Any?>): String
    }

    @Volatile
    var source: Source? = null

    private val fallback: Source by lazy {
        runCatching { ServiceLoader.load(Source::class.java).firstOrNull() }.getOrNull() ?: Missing
    }

    internal fun current(): Source = source ?: fallback

    private object Missing : Source {
        override fun text(id: Int, args: Array<out Any?>): String = "#" + Integer.toHexString(id)
        override fun plural(id: Int, count: Int, args: Array<out Any?>): String = "#" + Integer.toHexString(id)
    }
}

fun tr(@StringRes id: Int, vararg args: Any?): String = L10n.current().text(id, args)

fun separator(): String = tr(dev.cluvex.strata.R.string.common_separator)

fun trPlural(@PluralsRes id: Int, count: Int, vararg args: Any?): String = L10n.current().plural(id, count, args)
