package dev.cluvex.strata.core.apk.install

import dev.cluvex.strata.core.root.RootCommands

data class ApkPart(val name: String, val path: String, val size: Long)

object PmInstall {
    fun create(parts: List<ApkPart>, allowDowngrade: Boolean): String =
        buildString {
            append("pm install-create -r -t")
            if (allowDowngrade) append(" -d")
            append(" -S ").append(parts.sumOf { it.size })
        }

    fun parseSessionId(output: String): Int? = SESSION.find(output)?.groupValues?.get(1)?.toIntOrNull()

    fun write(session: Int, parts: List<ApkPart>): List<String> = parts.mapIndexed { i, part ->
        "pm install-write -S ${part.size} $session ${RootCommands.quote("${i}_" + safeName(part.name))} ${RootCommands.quote(part.path)}"
    }

    fun commit(session: Int): String = "pm install-commit $session"

    fun abandon(session: Int): String = "pm install-abandon $session"

    data class Staging(val command: String, val parts: List<ApkPart>)

    fun stagingDir(nonce: Long = System.nanoTime()): String = "/data/local/tmp/$STAGING_PREFIX$nonce"

    fun stage(parts: List<ApkPart>, dir: String): Staging {
        val copies = parts.mapIndexed { i, part -> part.copy(path = "$dir/${i}_" + safeName(part.name)) }
        val command = buildString {
            append("{ mkdir -p ").append(RootCommands.quote(dir)).append(" && chmod 755 ").append(RootCommands.quote(dir))
            for ((from, to) in parts.zip(copies)) {
                append(" && cp ").append(RootCommands.quote(from.path)).append(' ').append(RootCommands.quote(to.path))
                append(" && chmod 644 ").append(RootCommands.quote(to.path))
            }
            append(" && echo ").append(STAGED).append("; }")
        }
        return Staging(command, copies)
    }

    fun staged(output: String): Boolean = output.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() } == STAGED

    fun unstage(dir: String): String {
        require(dir.substringAfterLast('/').startsWith(STAGING_PREFIX) && dir.split('/').none { it == ".." }) {
            "not a staging folder: $dir"
        }
        return "rm -rf ${RootCommands.quote(dir)}"
    }

    const val STAGED = "strata-staged"

    private const val STAGING_PREFIX = "strata-install-"

    private fun safeName(name: String) = name.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private val SESSION = Regex("\\[(\\d+)\\]")
}
