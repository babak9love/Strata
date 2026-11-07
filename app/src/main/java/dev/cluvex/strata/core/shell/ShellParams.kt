package dev.cluvex.strata.core.shell

import android.content.Context

/**
 * The {package} placeholder of a saved command: where the package name of an app goes. The app is chosen when the
 * command runs, and the command is run with the name written in. Only a well-formed package name is ever written
 * into a command, so a picked or typed value can never add anything else to the shell line.
 */
object ShellParams {
    const val PACKAGE = "{package}"
    private val VALID = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

    fun needsPackage(command: String): Boolean = command.contains(PACKAGE)

    fun isValidPackage(name: String): Boolean = VALID.matches(name)

    /** The same saved command (same id, so its run and log stay together) with the package name written in. */
    fun resolve(command: SavedCommand, packageName: String): SavedCommand {
        require(isValidPackage(packageName)) { "Not a package name" }
        return command.copy(command = command.command.replace(PACKAGE, packageName))
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("shell_ui", Context.MODE_PRIVATE)

    /** The app picked the last time this command ran, so the picker can offer it first. */
    fun lastPackage(context: Context, commandId: String): String? =
        prefs(context).getString("last_pkg_$commandId", null)?.takeIf { isValidPackage(it) }

    fun rememberPackage(context: Context, commandId: String, packageName: String) {
        prefs(context).edit().putString("last_pkg_$commandId", packageName).apply()
    }
}
