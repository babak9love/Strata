package dev.cluvex.strata.core.markdown

import java.io.ByteArrayOutputStream
import java.util.Locale

sealed interface MarkdownLink {
    data class Web(val uri: String) : MarkdownLink

    data class LocalFile(val path: String, val fragment: String?) : MarkdownLink

    data class Anchor(val fragment: String) : MarkdownLink

    data object Unsupported : MarkdownLink
}

object MarkdownLinks {

    private val WEB = setOf("http", "https", "mailto")

    fun resolve(folder: String, link: String): MarkdownLink {
        val l = link.trim()
        if (l.isEmpty()) return MarkdownLink.Unsupported
        if (l.startsWith("#")) return MarkdownLink.Anchor(decode(l.substring(1)))
        val scheme = SCHEME.find(l)?.groupValues?.get(1)?.lowercase(Locale.ROOT)
        return when {
            scheme in WEB -> MarkdownLink.Web(l)
            scheme == "file" -> local("/", "/" + l.substring("file:".length).trimStart('/'))
            scheme != null -> MarkdownLink.Unsupported
            else -> local(folder, l)
        }
    }

    fun imagePath(folder: String, src: String): String? =
        (resolve(folder, src) as? MarkdownLink.LocalFile)?.path

    private fun local(folder: String, link: String): MarkdownLink {
        val hash = link.indexOf('#')
        val beforeHash = if (hash >= 0) link.substring(0, hash) else link
        val fragment = if (hash >= 0) decode(link.substring(hash + 1)) else null
        val path = decode(beforeHash.substringBefore('?'))
        if (path.isEmpty()) return fragment?.let { MarkdownLink.Anchor(it) } ?: MarkdownLink.Unsupported
        val joined = if (path.startsWith("/")) path else "${folder.trimEnd('/')}/$path"
        return MarkdownLink.LocalFile(normalize(joined), fragment)
    }

    internal fun normalize(path: String): String {
        val out = ArrayList<String>()
        for (seg in path.split('/')) {
            when (seg) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                else -> out += seg
            }
        }
        return "/" + out.joinToString("/")
    }

    internal fun decode(s: String): String {
        if ('%' !in s) return s
        val bytes = ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length && hex(s[i + 1]) >= 0 && hex(s[i + 2]) >= 0) {
                bytes.write(hex(s[i + 1]) * 16 + hex(s[i + 2]))
                i += 3
            } else {
                bytes.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
        }
        return bytes.toString("UTF-8")
    }

    private fun hex(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    private val SCHEME = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")
}
