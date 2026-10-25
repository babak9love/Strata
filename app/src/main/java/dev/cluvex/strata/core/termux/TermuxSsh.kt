package dev.cluvex.strata.core.termux

import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr

object TermuxSsh {
    const val PACKAGE = "openssh"

    private val PLAIN_PATH = Regex("^/[A-Za-z0-9_./-]*$")

    fun command(host: String, port: Int, user: String, remoteDir: String? = null): TermuxCommand {
        val h = host.trim()
        require(h.isNotEmpty() && !h.startsWith("-")) { tr(R.string.termux_invalid_host) }
        require(port in 1..65535) { tr(R.string.termux_invalid_port) }
        val u = user.trim().ifEmpty { "root" }
        val dir = remoteDir?.takeIf { it != "/" && PLAIN_PATH.matches(it) }
        val label = "SSH $u@$h"
        return if (dir != null) {
            TermuxCommand.bash(
                "exec ssh -t -p \"$1\" -l \"$2\" -- \"$3\" \"cd $4 && exec \\\$SHELL -l\"",
                port.toString(), u, h, dir, background = false, label = label,
            )
        } else {
            TermuxCommand.bash("exec ssh -p \"$1\" -l \"$2\" -- \"$3\"", port.toString(), u, h, background = false, label = label)
        }
    }

    val detection: TermuxCommand = TermuxCommand.bash("command -v ssh", label = "Find ssh")

    fun install(): TermuxCommand =
        TermuxCommand.bash("pkg install -y \"$1\"", PACKAGE, background = false, label = "Install openssh")
}
