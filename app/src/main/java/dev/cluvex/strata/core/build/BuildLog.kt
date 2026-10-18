package dev.cluvex.strata.core.build

import dev.cluvex.strata.R
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.i18n.trPlural
import java.util.Locale

enum class StepStatus { WAITING, RUNNING, DONE, FAILED, SKIPPED }

enum class LogLevel { INFO, WARN, ERROR }

data class BuildStep(
    val title: String,
    val status: StepStatus = StepStatus.WAITING,
    val detail: String = "",
    val startedAt: Long = 0L,
    val endedAt: Long = 0L,
    val done: Int = 0,
    val total: Int = 0,
) {
    val millis: Long get() = if (startedAt > 0L && endedAt >= startedAt) endedAt - startedAt else 0L
}

data class LogLine(val at: Long, val level: LogLevel, val text: String, val step: Int)

data class BuildRun(
    val title: String,
    val subject: String = "",
    val steps: List<BuildStep> = emptyList(),
    val lines: List<LogLine> = emptyList(),
    val startedAt: Long = 0L,
    val endedAt: Long = 0L,
    val finished: Boolean = false,
    val failure: String? = null,
    val summary: String = "",
    val warnings: Int = 0,
    val dropped: Int = 0,
) {
    val failed: Boolean get() = failure != null

    val running: Int get() = steps.indexOfFirst { it.status == StepStatus.RUNNING }

    val progress: Float
        get() {
            if (finished && !failed) return 1f
            if (steps.isEmpty()) return 0f
            var sum = 0f
            for (s in steps) {
                sum += when (s.status) {
                    StepStatus.DONE, StepStatus.SKIPPED -> 1f
                    StepStatus.RUNNING -> if (s.total > 0) 0.9f * s.done.coerceAtMost(s.total) / s.total else 0.3f
                    StepStatus.FAILED, StepStatus.WAITING -> 0f
                }
            }
            return (sum / steps.size).coerceIn(0f, 1f)
        }

    fun elapsed(now: Long): Long = ((if (finished) endedAt else now) - startedAt).coerceAtLeast(0L)

    fun text(): String = buildString {
        append(title)
        if (subject.isNotEmpty()) append(" — ").append(subject)
        append('\n')
        append(
            when {
                failed -> tr(R.string.build_failed_after, seconds(elapsed(endedAt)))
                finished -> tr(R.string.build_done_in, seconds(elapsed(endedAt)))
                else -> tr(R.string.build_running)
            },
        )
        append("\n\n")
        for (s in steps) {
            append(
                when (s.status) {
                    StepStatus.DONE -> "✓ "
                    StepStatus.FAILED -> "✗ "
                    StepStatus.RUNNING -> "▸ "
                    StepStatus.SKIPPED -> "– "
                    StepStatus.WAITING -> "· "
                },
            )
            append(s.title)
            if (s.millis > 0) append(" (").append(seconds(s.millis)).append(')')
            if (s.detail.isNotEmpty()) append(": ").append(s.detail)
            append('\n')
        }
        append('\n')
        if (dropped > 0) append(trPlural(R.plurals.build_lines_not_kept, dropped, number(dropped))).append('\n')
        for (l in lines) {
            append(stamp(l.at - startedAt)).append(' ')
            append(
                when (l.level) {
                    LogLevel.INFO -> l.text
                    LogLevel.WARN -> tr(R.string.build_log_warning, l.text)
                    LogLevel.ERROR -> tr(R.string.build_log_error, l.text)
                },
            )
            append('\n')
        }
    }

    companion object {
        fun seconds(millis: Long): String = tr(R.string.build_seconds, String.format(Locale.ROOT, "%.1f", millis / 1000.0))

        fun stamp(millis: Long): String = String.format(Locale.ROOT, "+%.2f", millis.coerceAtLeast(0L) / 1000.0)

        fun size(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024L * 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
            else -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0))
        }

        fun count(n: Int, one: String, many: String = one + "s"): String = if (n == 1) "1 $one" else String.format(Locale.ROOT, "%,d %s", n, many)

        fun number(n: Int): String = String.format(Locale.ROOT, "%,d", n)
    }
}

interface BuildListener {
    fun step(title: String)
    fun info(text: String)
    fun warn(text: String)
    fun progress(done: Int, total: Int)

    companion object {
        val NONE: BuildListener = object : BuildListener {
            override fun step(title: String) = Unit
            override fun info(text: String) = Unit
            override fun warn(text: String) = Unit
            override fun progress(done: Int, total: Int) = Unit
        }
    }
}

class BuildTracker(
    title: String,
    subject: String = "",
    plan: List<String> = emptyList(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val throttleMillis: Long = 80L,
    private val maxLines: Int = 2000,
    private val onChange: (BuildRun) -> Unit,
) : BuildListener {

    private val lock = Any()
    private var state: BuildRun
    private var lastEmit = 0L

    init {
        val now = clock()
        state = BuildRun(title, subject, plan.map { BuildStep(it) }, startedAt = now)
        lastEmit = now
        onChange(state)
    }

    val run: BuildRun get() = synchronized(lock) { state }

    override fun step(title: String) = update(force = true) { s, now ->
        val closed = s.steps.map { if (it.status == StepStatus.RUNNING) it.copy(status = StepStatus.DONE, endedAt = now) else it }
        val planned = closed.indexOfFirst { it.title == title && it.status == StepStatus.WAITING }
        val steps = if (planned >= 0) {
            closed.mapIndexed { i, st ->
                when {
                    i == planned -> st.copy(status = StepStatus.RUNNING, startedAt = now)
                    i < planned && st.status == StepStatus.WAITING -> st.copy(status = StepStatus.SKIPPED)
                    else -> st
                }
            }
        } else {
            closed + BuildStep(title, StepStatus.RUNNING, startedAt = now)
        }
        val index = if (planned >= 0) planned else steps.lastIndex
        appended(s.copy(steps = steps), LogLine(now, LogLevel.INFO, "▸ $title", index))
    }

    override fun info(text: String) = line(LogLevel.INFO, text)

    override fun warn(text: String) = line(LogLevel.WARN, text)

    fun error(text: String) = line(LogLevel.ERROR, text)

    override fun progress(done: Int, total: Int) = update(force = false) { s, _ ->
        val i = s.running
        if (i < 0) s else s.copy(steps = s.steps.mapIndexed { k, st -> if (k == i) st.copy(done = done, total = total) else st })
    }

    fun finish(summary: String = "") = update(force = true) { s, now ->
        if (s.finished) return@update s
        val steps = s.steps.map {
            when (it.status) {
                StepStatus.RUNNING -> it.copy(status = StepStatus.DONE, endedAt = now)
                StepStatus.WAITING -> it.copy(status = StepStatus.SKIPPED)
                else -> it
            }
        }
        val done = s.copy(steps = steps, finished = true, endedAt = now, summary = summary)
        if (summary.isEmpty()) done else appended(done, LogLine(now, LogLevel.INFO, summary, -1))
    }

    fun fail(error: Throwable, details: List<String> = emptyList()) = fail(describe(error), details)

    fun fail(message: String, details: List<String> = emptyList()) = update(force = true) { s, now ->
        if (s.finished) return@update s
        var target = s.running
        if (target < 0) target = s.steps.indexOfFirst { it.status == StepStatus.WAITING }
        val steps = s.steps.mapIndexed { i, st ->
            if (i == target) st.copy(status = StepStatus.FAILED, detail = message.lineSequence().first(), endedAt = now, startedAt = if (st.startedAt > 0) st.startedAt else now) else st
        }
        var next = s.copy(steps = steps, finished = true, endedAt = now, failure = message)
        for (d in details) next = appended(next, LogLine(now, LogLevel.ERROR, d, target))
        if (details.isEmpty()) for (l in message.lines()) next = appended(next, LogLine(now, LogLevel.ERROR, l, target))
        next
    }

    private fun line(level: LogLevel, text: String) = update(force = level != LogLevel.INFO) { s, now ->
        val i = s.running
        var next = appended(s, LogLine(now, level, text, i))
        if (level == LogLevel.WARN) next = next.copy(warnings = next.warnings + 1)
        if (i >= 0 && level == LogLevel.INFO) next = next.copy(steps = next.steps.mapIndexed { k, st -> if (k == i) st.copy(detail = text) else st })
        next
    }

    private fun appended(s: BuildRun, line: LogLine): BuildRun {
        val lines = s.lines + line
        if (lines.size <= maxLines) return s.copy(lines = lines)
        val cut = lines.size - maxLines
        return s.copy(lines = lines.drop(cut), dropped = s.dropped + cut)
    }

    private inline fun update(force: Boolean, change: (BuildRun, Long) -> BuildRun) {
        synchronized(lock) {
            val now = clock()
            state = change(state, now)
            if (force || state.finished || now - lastEmit >= throttleMillis) {
                lastEmit = now
                onChange(state)
            }
        }
    }

    companion object {
        fun describe(error: Throwable): String {
            val parts = ArrayList<String>()
            var e: Throwable? = error
            var depth = 0
            while (e != null && depth < 5) {
                val m = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                if (parts.none { it == m || it.contains(m) }) parts += m
                e = e.cause
                depth++
            }
            return parts.joinToString("\n")
        }
    }
}
