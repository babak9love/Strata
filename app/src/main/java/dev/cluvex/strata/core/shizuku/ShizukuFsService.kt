package dev.cluvex.strata.core.shizuku

import android.os.IBinder
import com.topjohnwu.superuser.nio.FileSystemManager
import java.io.InputStreamReader

class ShizukuFsService : IShizukuFileService.Stub() {
    override fun destroy() {
        System.exit(0)
    }

    override fun getFileSystemService(): IBinder = FileSystemManager.getService()

    /**
     * Runs [command] with `sh -c` in this (Shizuku) process and returns "<exit code>\n<output>".
     * stderr is merged into the output. The exit code is [TIMEOUT_CODE] if the command had to be killed.
     */
    override fun exec(command: String, timeoutMs: Long): String = execInternal(command, timeoutMs, null)

    /** Like [exec], but also passes the output to [callback] in chunks while the command is running. */
    override fun execStreaming(command: String, timeoutMs: Long, callback: IShellOutput?): String =
        execInternal(command, timeoutMs, callback)

    private fun execInternal(command: String, timeoutMs: Long, callback: IShellOutput?): String {
        val process = try {
            ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        } catch (e: Exception) {
            return "127\n" + (e.message ?: e.javaClass.simpleName)
        }
        runCatching { process.outputStream.close() }

        val out = StringBuilder()
        val reader = Thread {
            runCatching {
                // A reader decodes UTF-8 across chunk boundaries, so a multi-byte character is never split.
                val input = InputStreamReader(process.inputStream, Charsets.UTF_8)
                val buf = CharArray(4096)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    synchronized(out) {
                        val room = MAX_OUTPUT - out.length
                        if (room > 0) out.append(buf, 0, minOf(n, room))
                    }
                    // If the app side is gone the call fails; the command still runs to its end.
                    if (callback != null) runCatching { callback.onOutput(String(buf, 0, n)) }
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

        // timeoutMs <= 0 means no limit.
        val unlimited = timeoutMs <= 0L
        val deadline = if (unlimited) Long.MAX_VALUE else System.currentTimeMillis() + timeoutMs.coerceIn(1_000L, 600_000L)
        var code = -1
        var finished = false
        while (!finished) {
            try {
                code = process.exitValue()
                finished = true
            } catch (_: IllegalThreadStateException) {
                if (!unlimited && System.currentTimeMillis() >= deadline) break
                try {
                    Thread.sleep(50)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }
        if (!finished) {
            process.destroy()
            code = TIMEOUT_CODE
        }
        runCatching { reader.join(1_000) }
        val text = synchronized(out) { out.toString() }
        return "$code\n$text"
    }

    private companion object {
        const val MAX_OUTPUT = 200_000
        const val TIMEOUT_CODE = -1
    }
}
