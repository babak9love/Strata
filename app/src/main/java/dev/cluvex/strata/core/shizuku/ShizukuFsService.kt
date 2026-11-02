package dev.cluvex.strata.core.shizuku

import android.os.IBinder
import com.topjohnwu.superuser.nio.FileSystemManager
import java.io.ByteArrayOutputStream

class ShizukuFsService : IShizukuFileService.Stub() {
    override fun destroy() {
        System.exit(0)
    }

    override fun getFileSystemService(): IBinder = FileSystemManager.getService()

    /**
     * Runs [command] with `sh -c` in this (Shizuku) process and returns "<exit code>\n<output>".
     * stderr is merged into the output. The exit code is [TIMEOUT_CODE] if the command had to be killed.
     */
    override fun exec(command: String, timeoutMs: Long): String {
        val process = try {
            ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        } catch (e: Exception) {
            return "127\n" + (e.message ?: e.javaClass.simpleName)
        }
        runCatching { process.outputStream.close() }

        val out = ByteArrayOutputStream()
        val reader = Thread {
            runCatching {
                val buf = ByteArray(8192)
                val input = process.inputStream
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    synchronized(out) {
                        val room = MAX_OUTPUT - out.size()
                        if (room > 0) out.write(buf, 0, minOf(n, room))
                    }
                }
            }
        }.apply {
            isDaemon = true
            start()
        }

        val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(1_000L, 600_000L)
        var code = -1
        var finished = false
        while (!finished) {
            try {
                code = process.exitValue()
                finished = true
            } catch (_: IllegalThreadStateException) {
                if (System.currentTimeMillis() >= deadline) break
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
        val text = synchronized(out) { out.toString("UTF-8") }
        return "$code\n$text"
    }

    private companion object {
        const val MAX_OUTPUT = 200_000
        const val TIMEOUT_CODE = -1
    }
}
