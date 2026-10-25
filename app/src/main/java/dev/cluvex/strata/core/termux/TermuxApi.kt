package dev.cluvex.strata.core.termux

import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr

object TermuxApi {
    const val PACKAGE = "com.termux"
    const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
    const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
    const val PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND"

    const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
    const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    const val EXTRA_STDIN = "com.termux.RUN_COMMAND_STDIN"
    const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    const val EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"
    const val EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
    const val EXTRA_COMMAND_DESCRIPTION = "com.termux.RUN_COMMAND_COMMAND_DESCRIPTION"
    const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

    const val RESULT_BUNDLE = "result"
    const val RESULT_STDOUT = "stdout"
    const val RESULT_STDOUT_ORIGINAL_LENGTH = "stdout_original_length"
    const val RESULT_STDERR = "stderr"
    const val RESULT_STDERR_ORIGINAL_LENGTH = "stderr_original_length"
    const val RESULT_EXIT_CODE = "exitCode"
    const val RESULT_ERR = "err"
    const val RESULT_ERRMSG = "errmsg"

    const val ERR_SUCCESS = -1

    const val SESSION_ACTION_NEW_CURRENT = "2"

    const val PREFIX = "/data/data/com.termux/files/usr"
    const val HOME = "/data/data/com.termux/files/home"
    const val BASH = "$PREFIX/bin/bash"

    const val ENABLE_EXTERNAL_APPS =
        "mkdir -p ~/.termux && echo 'allow-external-apps = true' >> ~/.termux/termux.properties && termux-reload-settings"

    const val SETUP_STORAGE = "termux-setup-storage"

    const val FDROID_URL = "https://f-droid.org/packages/com.termux/"
    const val GITHUB_URL = "https://github.com/termux/termux-app/releases"
}

data class TermuxCommand(
    val executable: String,
    val arguments: List<String> = emptyList(),
    val workdir: String? = null,
    val stdin: String? = null,
    val background: Boolean = true,
    val label: String? = null,
    val description: String? = null,
) {
    fun extras(): Map<String, Any> = buildMap {
        put(TermuxApi.EXTRA_COMMAND_PATH, executable)
        put(TermuxApi.EXTRA_ARGUMENTS, arguments.toTypedArray())
        put(TermuxApi.EXTRA_BACKGROUND, background)
        workdir?.let { put(TermuxApi.EXTRA_WORKDIR, it) }
        stdin?.let { put(TermuxApi.EXTRA_STDIN, it) }
        if (!background) put(TermuxApi.EXTRA_SESSION_ACTION, TermuxApi.SESSION_ACTION_NEW_CURRENT)
        label?.let { put(TermuxApi.EXTRA_COMMAND_LABEL, it) }
        description?.let { put(TermuxApi.EXTRA_COMMAND_DESCRIPTION, it) }
    }

    companion object {
        fun bash(
            script: String,
            vararg args: String,
            background: Boolean = true,
            workdir: String? = null,
            label: String? = null,
        ) = TermuxCommand(
            executable = TermuxApi.BASH,
            arguments = listOf("-c", script, "strata") + args,
            workdir = workdir,
            background = background,
            label = label,
        )
    }
}

data class TermuxResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int?,
    val err: Int,
    val errmsg: String?,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean,
) {
    val ok: Boolean get() = err == TermuxApi.ERR_SUCCESS && exitCode == 0

    val issue: TermuxIssue?
        get() = when {
            err != TermuxApi.ERR_SUCCESS && errmsg.orEmpty().contains("allow-external-apps") -> TermuxIssue.ExternalAppsDisabled
            err != TermuxApi.ERR_SUCCESS -> TermuxIssue.Failed(errmsg?.lineSequence()?.firstOrNull { it.isNotBlank() } ?: tr(R.string.termux_couldnt_run))
            exitCode == 0 -> null
            STORAGE_DENIED.containsMatchIn(stderr) -> TermuxIssue.StorageNotGranted
            exitCode == 127 -> TermuxIssue.CommandNotFound
            else -> TermuxIssue.ExitCode(exitCode)
        }

    companion object {
        private val STORAGE_DENIED = Regex("(/storage/|/sdcard).*(Permission denied|Operation not permitted)|(Permission denied|Operation not permitted).*(/storage/|/sdcard)")

        fun parse(get: (String) -> Any?): TermuxResult {
            val stdout = get(TermuxApi.RESULT_STDOUT) as? String ?: ""
            val stderr = get(TermuxApi.RESULT_STDERR) as? String ?: ""
            fun originalLength(key: String) = (get(key) as? String)?.toIntOrNull() ?: (get(key) as? Int)
            return TermuxResult(
                stdout = stdout,
                stderr = stderr,
                exitCode = get(TermuxApi.RESULT_EXIT_CODE) as? Int,
                err = get(TermuxApi.RESULT_ERR) as? Int ?: TermuxApi.ERR_SUCCESS,
                errmsg = get(TermuxApi.RESULT_ERRMSG) as? String,
                stdoutTruncated = (originalLength(TermuxApi.RESULT_STDOUT_ORIGINAL_LENGTH) ?: 0) > stdout.length,
                stderrTruncated = (originalLength(TermuxApi.RESULT_STDERR_ORIGINAL_LENGTH) ?: 0) > stderr.length,
            )
        }
    }
}

sealed interface TermuxIssue {
    data object ExternalAppsDisabled : TermuxIssue

    data object StorageNotGranted : TermuxIssue

    data object CommandNotFound : TermuxIssue

    data class ExitCode(val code: Int?) : TermuxIssue
    data class Failed(val message: String) : TermuxIssue
}
