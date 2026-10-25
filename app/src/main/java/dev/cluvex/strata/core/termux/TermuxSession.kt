package dev.cluvex.strata.core.termux

import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

interface TermuxPort {
    fun isInstalled(): Boolean

    fun hasPermission(): Boolean

    suspend fun run(command: TermuxCommand, timeoutMs: Long? = null): TermuxRun
}

data class RunOutput(
    val stdout: String,
    val stderr: String,
    val exitCode: Int?,
    val millis: Long,
    val truncated: Boolean,
    val target: String = "",
)

data class TermuxUiState(
    val termuxInstalled: Boolean = false,
    val permission: Boolean = false,
    val installed: Map<String, String>? = null,
    val checking: Boolean = false,
    val running: Boolean = false,
    val output: RunOutput? = null,
    val runTarget: String? = null,
    val issue: TermuxIssue? = null,
    val terminalWaiting: Boolean = false,
    val terminalIsSsh: Boolean = false,
    val sshMissing: Boolean = false,
    val creating: Boolean = false,
    val createdProject: String? = null,
    val createError: String? = null,
    val message: String? = null,
) {
    val reachable: Boolean get() = termuxInstalled && permission

    fun isInstalled(language: Language): Boolean? = when {
        !termuxInstalled -> false
        installed == null -> null
        language.packages.isEmpty() -> true
        else -> installed.containsKey(language.id)
    }

    fun canRun(language: Language): Boolean = reachable && issue == null && isInstalled(language) == true

    val terminalBlocked: Boolean get() = terminalWaiting && (!reachable || issue != null)

    fun outputFor(target: String): RunOutput? = output?.takeIf { it.target == target }

    fun runningFor(target: String): Boolean = running && runTarget == target
}

class TermuxSession(private val termux: TermuxPort, private val scope: CoroutineScope) {

    private val _state = MutableStateFlow(TermuxUiState())
    val state: StateFlow<TermuxUiState> = _state.asStateFlow()

    private class PendingTerminal(val command: TermuxCommand, val check: TermuxCommand, val ssh: Boolean)

    private var runJob: Job? = null
    private var pending: PendingTerminal? = null
    private var terminalJob: Job? = null

    fun refresh() {
        val installed = termux.isInstalled()
        val permission = installed && termux.hasPermission()
        _state.update { it.copy(termuxInstalled = installed, permission = permission, issue = if (!installed || !permission) null else it.issue) }
        if (!installed || !permission) return
        if (_state.value.issue == null) continueTerminal()
        if (_state.value.checking) return
        _state.update { it.copy(checking = true) }
        scope.launch {
            when (val r = termux.run(TermuxLanguages.detectionCommand(), timeoutMs = DETECT_TIMEOUT)) {
                is TermuxRun.Done -> {
                    val setup = r.result.issue?.takeIf { it == TermuxIssue.ExternalAppsDisabled || it is TermuxIssue.Failed }
                    _state.update {
                        it.copy(
                            checking = false,
                            issue = setup,
                            installed = if (setup == null) TermuxLanguages.parseDetection(r.result.stdout) else it.installed,
                        )
                    }
                    if (setup == null) continueTerminal()
                }
                else -> settle(r)
            }
        }
    }

    fun run(path: String, interactive: Boolean, command: String? = null) {
        val language = TermuxLanguages.forFile(path) ?: return
        val file = File(path)
        val termuxCommand = if (command.isNullOrBlank()) TermuxLanguages.runCommand(language, file, interactive)
        else RunCommands.command(RunTarget.forFile(language, file), command, interactive)
        launchRun(path, language, termuxCommand, interactive)
    }

    fun runProject(dir: String, interactive: Boolean, command: String? = null) {
        val project = TermuxProjects.detect(File(dir)) ?: return
        val termuxCommand = if (command.isNullOrBlank()) TermuxProjects.runCommand(project, interactive)
        else RunCommands.command(RunTarget.forProject(project), command, interactive)
        launchRun(dir, project.kind.language, termuxCommand, interactive, project.kind.tools)
    }

    fun installProjectLibraries(dir: String, names: String) {
        val project = TermuxProjects.detect(File(dir)) ?: return
        val inside = File(project.dir, project.kind.markers.first())
        val command = TermuxLanguages.libraryCommand(project.kind.language, inside, names.split(' ', ',')) ?: return
        foreground(command, tr(R.string.termux_installing))
    }

    fun createProject(template: ProjectTemplate, parent: String, name: String) {
        val dir = File(parent)
        TermuxProjects.validate(template, name, dir.list()?.toList().orEmpty())?.let { problem ->
            _state.update { it.copy(createError = problem) }
            return
        }
        _state.update { it.copy(creating = true, createError = null, createdProject = null, issue = null) }
        scope.launch {
            when (val r = termux.run(TermuxProjects.createCommand(template, dir, name), timeoutMs = CREATE_TIMEOUT)) {
                is TermuxRun.Done -> {
                    val res = r.result
                    val setup = res.issue?.takeIf { it == TermuxIssue.ExternalAppsDisabled || it == TermuxIssue.StorageNotGranted || it is TermuxIssue.Failed }
                    val missingTool = res.issue == TermuxIssue.CommandNotFound && res.stderr.contains(": command not found")
                    _state.update { s ->
                        when {
                            res.ok -> s.copy(creating = false, createdProject = File(dir, name).path)
                            setup != null -> s.copy(creating = false, issue = setup)
                            missingTool -> s.copy(
                                creating = false,
                                createError = tr(R.string.termux_language_not_installed, template.language.displayName),
                                installed = s.installed.orEmpty() - template.languageId,
                            )
                            else -> s.copy(creating = false, createError = toolMessage(res))
                        }
                    }
                }
                else -> settle(r)
            }
        }
    }

    fun consumeCreatedProject() = _state.update { it.copy(createdProject = null) }

    fun clearCreateError() = _state.update { it.copy(createError = null) }

    private fun toolMessage(res: TermuxResult): String =
        res.stderr.ifBlank { res.stdout }.trim().lines().takeLast(6).joinToString("\n").ifBlank { tr(R.string.termux_stopped_exit, res.exitCode) }

    private fun launchRun(target: String, language: Language, command: TermuxCommand, interactive: Boolean, tools: Collection<String> = emptyList()) {
        runJob?.cancel()
        _state.update { it.copy(running = !interactive, runTarget = target, output = null, issue = null, message = null) }
        val started = System.currentTimeMillis()
        runJob = scope.launch {
            when (val r = termux.run(command)) {
                is TermuxRun.Done -> {
                    val res = r.result
                    val setup = res.issue?.takeIf { it == TermuxIssue.ExternalAppsDisabled || it == TermuxIssue.StorageNotGranted || it is TermuxIssue.Failed }
                    val missing = TermuxLanguages.programMissing(language, res, tools)
                    val ran = res.err == TermuxApi.ERR_SUCCESS && !missing && setup == null
                    _state.update { s ->
                        s.copy(
                            running = false,
                            issue = setup,
                            output = if (ran) RunOutput(res.stdout, res.stderr, res.exitCode, System.currentTimeMillis() - started, res.stdoutTruncated || res.stderrTruncated, target) else null,
                            installed = if (missing) s.installed.orEmpty() - language.id else s.installed,
                        )
                    }
                }
                TermuxRun.Opened -> _state.update { it.copy(running = false, message = tr(R.string.termux_opened)) }
                else -> settle(r)
            }
        }
    }

    fun stopWaiting() {
        runJob?.cancel()
        _state.update { it.copy(running = false, message = tr(R.string.termux_stopped_waiting)) }
    }

    fun install(language: Language) = foreground(TermuxLanguages.installCommand(language), tr(R.string.termux_installing_language, language.displayName))

    fun installLibraries(path: String, names: String) {
        val language = TermuxLanguages.forFile(path) ?: return
        val command = TermuxLanguages.libraryCommand(language, File(path), names.split(' ', ',')) ?: return
        foreground(command, tr(R.string.termux_installing))
    }

    fun setupStorage() = foreground(TermuxCommand.bash(TermuxApi.SETUP_STORAGE, background = false, label = "Storage access"), tr(R.string.termux_allow_storage))

    fun openSsh(host: String, port: Int, user: String, remoteDir: String? = null) {
        val command = runCatching { TermuxSsh.command(host, port, user, remoteDir) }.getOrElse {
            _state.update { s -> s.copy(message = it.message) }
            return
        }
        waitForTerminal(PendingTerminal(command, TermuxSsh.detection, ssh = true))
    }

    fun openTerminal(dir: String) =
        waitForTerminal(PendingTerminal(TermuxProjects.terminalCommand(File(dir)), READY_CHECK, ssh = false))

    fun cancelTerminal() {
        pending = null
        terminalJob?.cancel()
        _state.update { it.copy(terminalWaiting = false, issue = null) }
    }

    private fun waitForTerminal(terminal: PendingTerminal) {
        pending = terminal
        _state.update { it.copy(terminalWaiting = true, terminalIsSsh = terminal.ssh, sshMissing = false) }
        refresh()
    }

    fun installSsh() {
        _state.update { it.copy(sshMissing = false) }
        foreground(TermuxSsh.install(), tr(R.string.termux_installing_openssh))
    }

    fun dismissSsh() = _state.update { it.copy(sshMissing = false) }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun clearIssue() = _state.update { it.copy(issue = null) }

    private fun continueTerminal() {
        val terminal = pending ?: return
        if (!_state.value.reachable || terminalJob?.isActive == true) return
        terminalJob = scope.launch {
            when (val check = termux.run(terminal.check, timeoutMs = DETECT_TIMEOUT)) {
                is TermuxRun.Done -> {
                    val issue = check.result.issue
                    when {
                        issue == TermuxIssue.ExternalAppsDisabled || issue is TermuxIssue.Failed -> _state.update { it.copy(issue = issue) }
                        check.result.ok || !terminal.ssh -> {
                            terminalDone()
                            foreground(terminal.command, null)
                        }
                        else -> {
                            terminalDone()
                            _state.update { it.copy(sshMissing = true) }
                        }
                    }
                }
                else -> settle(check)
            }
        }
    }

    private fun terminalDone() {
        pending = null
        _state.update { it.copy(terminalWaiting = false) }
    }

    private fun foreground(command: TermuxCommand, message: String?) {
        scope.launch {
            when (val r = termux.run(command)) {
                TermuxRun.Opened -> message?.let { m -> _state.update { it.copy(message = m) } }
                else -> settle(r)
            }
        }
    }

    private fun settle(r: TermuxRun) {
        _state.update {
            when (r) {
                TermuxRun.NotInstalled -> it.copy(checking = false, running = false, creating = false, termuxInstalled = false)
                TermuxRun.NoPermission -> it.copy(checking = false, running = false, creating = false, permission = false)
                TermuxRun.TimedOut -> it.copy(
                    checking = false, running = false, creating = false,
                    issue = TermuxIssue.Failed(tr(R.string.termux_no_answer)),
                )
                is TermuxRun.Error -> it.copy(checking = false, running = false, creating = false, issue = TermuxIssue.Failed(r.message))
                else -> it.copy(checking = false, running = false, creating = false)
            }
        }
    }

    private companion object {
        const val DETECT_TIMEOUT = 20_000L

        const val CREATE_TIMEOUT = 180_000L

        val READY_CHECK = TermuxCommand.bash("true", label = "Check Termux")
    }
}
