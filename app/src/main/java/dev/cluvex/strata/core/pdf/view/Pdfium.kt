package dev.cluvex.strata.core.pdf.view

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.nio.ByteBuffer
import java.util.concurrent.Executors

object Pdfium {

    val dispatcher: CoroutineDispatcher by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "pdfium").apply { isDaemon = true } }.asCoroutineDispatcher()
    }

    @Volatile
    private var ready = false

    @Synchronized
    fun ensure(): Boolean {
        if (ready) return true
        ready = runCatching {
            val hostLibrary = System.getProperty("strata.pdfium")
            if (hostLibrary == null) System.loadLibrary("pdfium")
            System.loadLibrary("strata_native")
            nInit(hostLibrary) == 0
        }.getOrDefault(false)
        return ready
    }

    fun cancelRender() {
        if (ready) nCancel()
    }

    external fun nInit(path: String?): Int
    external fun nOpenPath(path: String, password: String?): Long
    external fun nOpenFd(fd: Int, password: String?): Long
    external fun nClose(doc: Long)
    external fun nPageSizes(doc: Long): FloatArray?
    external fun nCancel()
    external fun nRender(doc: Long, page: Int, buffer: ByteBuffer, width: Int, height: Int, startX: Int, startY: Int, sizeX: Int, sizeY: Int, flags: Int): Int
    external fun nPageTransform(doc: Long, page: Int): FloatArray?
    external fun nPageText(doc: Long, page: Int): FloatArray?
    external fun nLinks(doc: Long, page: Int): Array<Any?>?
    external fun nOutline(doc: Long): Array<Any?>?
    external fun nPageLabels(doc: Long): Array<String?>?
    external fun nMeta(doc: Long): Array<String?>?
}
