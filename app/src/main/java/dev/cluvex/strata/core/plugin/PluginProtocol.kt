package dev.cluvex.strata.core.plugin

import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.share.safeChildName
import java.util.Locale

object PluginProtocol {
    const val API = 1

    const val ACTION_FILE = "dev.cluvex.strata.plugin.action.FILE"

    const val META_API = "dev.cluvex.strata.plugin.API"

    const val META_EXTENSIONS = "dev.cluvex.strata.plugin.EXTENSIONS"

    const val META_DESCRIPTION = "dev.cluvex.strata.plugin.DESCRIPTION"

    const val EXTRA_API = "dev.cluvex.strata.plugin.extra.API"

    const val EXTRA_FILE_NAME = "dev.cluvex.strata.plugin.extra.FILE_NAME"

    const val EXTRA_RESULT_TEXT = "dev.cluvex.strata.plugin.extra.RESULT_TEXT"

    const val EXTRA_RESULT_NAME = "dev.cluvex.strata.plugin.extra.RESULT_NAME"

    const val EXTRA_MESSAGE = "dev.cluvex.strata.plugin.extra.MESSAGE"

    const val MAX_TEXT = 512 * 1024
    const val MAX_MESSAGE = 4_000

    const val MAX_RESULT_BYTES = 1L shl 30

    fun parseExtensions(raw: String?): Set<String>? {
        val list = raw.orEmpty().split(',', ';', ' ')
            .map { it.trim().trimStart('.').lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() }
        return if (list.isEmpty() || "*" in list) null else list.toSet()
    }

    fun handles(extensions: Set<String>?, fileName: String): Boolean {
        if (extensions == null) return true
        val name = fileName.lowercase(Locale.ROOT)
        return extensions.any { name.endsWith(".$it") }
    }

    fun interpret(reply: PluginReply, ownsAuthority: (String) -> Boolean): PluginOutcome {
        if (!reply.ok) return PluginOutcome.Cancelled
        val message = reply.message?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_MESSAGE)
        val name = safeChildName(reply.name?.trim())
        if (reply.uri != null) {
            if (!reply.uriScheme.equals("content", ignoreCase = true)) {
                return PluginOutcome.Refused(reply.uriScheme?.let { tr(R.string.plugin_refused_scheme, it) } ?: tr(R.string.plugin_refused_no_scheme))
            }
            val authority = reply.uriAuthority?.takeIf { it.isNotEmpty() } ?: return PluginOutcome.Refused(tr(R.string.plugin_refused_malformed))
            if (ownsAuthority(authority)) return PluginOutcome.Refused(tr(R.string.plugin_refused_own))
            return PluginOutcome.Content(reply.uri, name, message)
        }
        if (reply.text != null) {
            if (reply.text.length > MAX_TEXT) return PluginOutcome.Refused(tr(R.string.plugin_refused_too_long))
            return PluginOutcome.Text(reply.text, name, message)
        }
        return if (message != null) PluginOutcome.Message(message) else PluginOutcome.Cancelled
    }
}

data class PluginInfo(
    val packageName: String,
    val activityName: String,
    val label: String,
    val description: String?,
    val extensions: Set<String>?,
    val api: Int,
    val signer: String,
    val pastSigners: List<String> = emptyList(),
) {
    fun handles(fileName: String) = PluginProtocol.handles(extensions, fileName)
}

data class PluginReply(
    val ok: Boolean,
    val text: String? = null,
    val uri: String? = null,
    val uriScheme: String? = null,
    val uriAuthority: String? = null,
    val name: String? = null,
    val message: String? = null,
)

sealed interface PluginOutcome {
    data object Cancelled : PluginOutcome
    data class Message(val text: String) : PluginOutcome
    data class Text(val text: String, val name: String?, val message: String?) : PluginOutcome
    data class Content(val uri: String, val name: String?, val message: String?) : PluginOutcome
    data class Refused(val reason: String) : PluginOutcome
}
