package dev.cluvex.strata.feature.media

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Rational
import dev.cluvex.strata.R
import androidx.media3.common.Player
import androidx.media3.common.VideoSize

/**
 * Picture-in-picture for the video player: the video keeps playing in a small floating window,
 * only when the PiP button is pressed.
 * Needs Android 8 (API 26) or newer; on older versions [isSupported] is false and the button is hidden.
 */
object VideoPip {
    @Volatile
    private var aspect: Rational = Rational(16, 9)

    fun isSupported(activity: Activity): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    /** Remembers the video's shape so the floating window gets the right proportions. */
    fun update(size: VideoSize) {
        if (size.width > 0 && size.height > 0) {
            // The system only accepts ratios between 1:2.39 and 2.39:1.
            val ratio = (size.width * size.pixelWidthHeightRatio / size.height).coerceIn(0.42f, 2.39f)
            aspect = Rational((ratio * 1000).toInt(), 1000)
        }
    }

    /** Broadcast actions sent by the buttons inside the floating window. */
    const val ACTION_TOGGLE = "dev.cluvex.strata.PIP_TOGGLE_PLAYBACK"
    const val ACTION_PREVIOUS = "dev.cluvex.strata.PIP_PREVIOUS"
    const val ACTION_NEXT = "dev.cluvex.strata.PIP_NEXT"

    /** Headphones button: leave the floating window and keep playing the audio only, in the background. */
    const val ACTION_AUDIO_ONLY = "dev.cluvex.strata.PIP_AUDIO_ONLY"

    val ALL_ACTIONS = listOf(ACTION_TOGGLE, ACTION_PREVIOUS, ACTION_NEXT, ACTION_AUDIO_ONLY)

    fun enter(activity: Activity, player: Player?) {
        if (!isSupported(activity)) return
        runCatching { activity.enterPictureInPictureMode(buildParams(activity, player)) }
    }

    /** Refreshes the floating window's buttons (play/pause icon, previous/next enabled state). */
    fun refresh(activity: Activity, player: Player?) {
        if (!isSupported(activity)) return
        runCatching { activity.setPictureInPictureParams(buildParams(activity, player)) }
    }

    private fun action(activity: Activity, code: Int, action: String, icon: Int, title: String, enabled: Boolean = true): RemoteAction {
        val intent = PendingIntent.getBroadcast(
            activity,
            code,
            Intent(action).setPackage(activity.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return RemoteAction(Icon.createWithResource(activity, icon), title, title, intent).also { it.isEnabled = enabled }
    }

    private fun buildParams(activity: Activity, player: Player?): PictureInPictureParams {
        val playing = player?.playWhenReady == true
        // Previous is never disabled: with no earlier video it restarts the current one.
        val hasNext = player?.hasNextMediaItem() == true
        // A disabled Next also gets a dimmed icon, because some devices ignore RemoteAction.isEnabled.
        val previous = action(activity, 1, ACTION_PREVIOUS, R.drawable.ic_pip_previous, activity.getString(R.string.media_pip_previous))
        val toggle = action(
            activity, 0, ACTION_TOGGLE,
            if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            activity.getString(if (playing) R.string.media_pip_pause else R.string.media_pip_play),
        )
        val next = action(
            activity, 2, ACTION_NEXT,
            if (hasNext) R.drawable.ic_pip_next else R.drawable.ic_pip_next_disabled,
            activity.getString(R.string.media_pip_next), hasNext,
        )
        val audio = action(activity, 3, ACTION_AUDIO_ONLY, R.drawable.ic_pip_headphones, activity.getString(R.string.media_pip_audio_only))

        // Some devices allow fewer than 4 buttons; keep the most important ones first.
        val max = activity.maxNumPictureInPictureActions
        val actions = when {
            max >= 4 -> listOf(previous, toggle, next, audio)
            max == 3 -> listOf(toggle, next, audio)
            else -> listOf(toggle)
        }
        return PictureInPictureParams.Builder()
            .setAspectRatio(aspect)
            .setActions(actions)
            .build()
    }
}
