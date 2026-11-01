package dev.cluvex.strata.core.media

import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.Locale

/** Video lengths for the badge on file-list thumbnails. Cached, so scrolling a long folder does not read the same file twice. */
object VideoDurations {

    private val cache = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean = size > 3000
    }
    private val gate = Semaphore(3)

    /** Length in milliseconds, or null when the file has none or cannot be read. [modified] keeps the cache honest after the file changes. */
    suspend fun of(path: String, modified: Long): Long? {
        val key = "$path|$modified"
        synchronized(cache) { cache[key] }?.let { return it.takeIf { ms -> ms > 0 } }
        val ms = gate.withPermit { withContext(Dispatchers.IO) { read(path) } }
        synchronized(cache) { cache[key] = ms }
        return ms.takeIf { it > 0 }
    }

    private fun read(path: String): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (_: Throwable) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** 0:13, 12:05, 1:02:30 */
    fun format(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%d:%02d", m, sec)
    }
}
