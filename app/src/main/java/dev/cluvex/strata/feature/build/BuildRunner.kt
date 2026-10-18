package dev.cluvex.strata.feature.build

import dev.cluvex.strata.R
import dev.cluvex.strata.core.build.BuildListener
import dev.cluvex.strata.core.build.BuildRun
import dev.cluvex.strata.core.build.BuildTracker
import dev.cluvex.strata.core.dex.SmaliSyntaxException
import dev.cluvex.strata.core.i18n.tr
import dev.cluvex.strata.core.sound.UiSound
import dev.cluvex.strata.core.sound.UiSounds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun <T> runBuild(
    title: String,
    subject: String,
    plan: List<String>,
    publish: (BuildRun) -> Unit,
    summary: (T) -> String,
    work: suspend (BuildListener) -> T,
): Result<T> {
    val tracker = BuildTracker(title, subject, plan, onChange = publish)
    val result = try {
        Result.success(withContext(Dispatchers.Default) { work(tracker) })
    } catch (e: CancellationException) {
        tracker.fail(tr(R.string.build_stopped))
        throw e
    } catch (e: Throwable) {
        if (e is VirtualMachineError && e !is OutOfMemoryError) throw e
        Result.failure(e)
    }
    result.onSuccess {
        tracker.finish(summary(it))
        UiSounds.play(UiSound.SUCCESS)
    }
    result.onFailure { e ->
        tracker.fail(e, details(e))
        UiSounds.play(UiSound.ERROR)
    }
    return result
}

fun details(e: Throwable): List<String> = when (e) {
    is SmaliSyntaxException -> e.errors.map { it.toString() }
    else -> emptyList()
}
