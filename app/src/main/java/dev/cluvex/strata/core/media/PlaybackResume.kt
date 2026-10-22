package dev.cluvex.strata.core.media

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Player

/** Audio and video keep separate resume data (and have separate on/off settings). */
enum class ResumeKind(val prefsName: String) {
    AUDIO("playback_positions_audio"),
    VIDEO("playback_positions_video"),
}

/**
 * Remembers where each audio/video file stopped so it can resume from there next time.
 * Keyed by file path; stored as "positionMs;savedAtMs" in a small SharedPreferences file per [ResumeKind].
 */
object PlaybackPositions {
    private const val MIN_SAVE_MS = 5_000L
    private const val END_MARGIN_MS = 5_000L
    private const val MAX_ENTRIES = 300
    private const val PRUNE_COUNT = 50

    private fun prefs(ctx: Context, kind: ResumeKind) = ctx.applicationContext.getSharedPreferences(kind.prefsName, Context.MODE_PRIVATE)

    /** Forgets every saved position of one kind (used when the user turns resuming off). */
    fun clearAll(ctx: Context, kind: ResumeKind) {
        prefs(ctx, kind).edit().clear().apply()
    }

    fun get(ctx: Context, kind: ResumeKind, path: String): Long =
        prefs(ctx, kind).getString(path, null)?.substringBefore(';')?.toLongOrNull()?.takeIf { it > 0 } ?: 0L

    fun clear(ctx: Context, kind: ResumeKind, path: String) {
        if (path.isEmpty()) return
        val p = prefs(ctx, kind)
        if (p.contains(path)) p.edit().remove(path).apply()
    }

    /** Saves [positionMs]; clears the entry instead when it's near the start or the end of the file. */
    fun save(ctx: Context, kind: ResumeKind, path: String, positionMs: Long, durationMs: Long = C.TIME_UNSET) {
        if (path.isEmpty()) return
        val nearEnd = durationMs != C.TIME_UNSET && durationMs > 0 && positionMs >= durationMs - END_MARGIN_MS
        if (positionMs < MIN_SAVE_MS || nearEnd) {
            clear(ctx, kind, path)
            return
        }
        val p = prefs(ctx, kind)
        val edit = p.edit()
        val all = p.all
        if (all.size >= MAX_ENTRIES && !all.containsKey(path)) {
            all.entries
                .sortedBy { (it.value as? String)?.substringAfter(';', "")?.toLongOrNull() ?: 0L }
                .take(PRUNE_COUNT)
                .forEach { edit.remove(it.key) }
        }
        edit.putString(path, "$positionMs;${System.currentTimeMillis()}").apply()
    }
}

/** Attach to a [Player] whose media ids are file paths; call [saveNow] periodically and before releasing. */
class ResumeListener(
    private val ctx: Context,
    private val kind: ResumeKind,
    private val player: Player,
    private val enabled: () -> Boolean,
) : Player.Listener {

    fun saveNow() {
        if (!enabled()) return
        val id = player.currentMediaItem?.mediaId ?: return
        if (player.playbackState == Player.STATE_ENDED) PlaybackPositions.clear(ctx, kind, id)
        else PlaybackPositions.save(ctx, kind, id, player.currentPosition, player.duration)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (!isPlaying) saveNow()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        if (!enabled()) return
        if (playbackState == Player.STATE_ENDED) player.currentMediaItem?.mediaId?.let { PlaybackPositions.clear(ctx, kind, it) }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (!enabled()) return
        val prev = oldPosition.mediaItem ?: return
        if (oldPosition.mediaItemIndex == newPosition.mediaItemIndex && prev.mediaId == newPosition.mediaItem?.mediaId) return
        if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) PlaybackPositions.clear(ctx, kind, prev.mediaId)
        else PlaybackPositions.save(ctx, kind, prev.mediaId, oldPosition.positionMs)
    }
}
