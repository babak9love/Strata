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

    /** Broadcast action sent by the play/pause button inside the floating window. */
    const val ACTION_TOGGLE = "dev.cluvex.strata.PIP_TOGGLE_PLAYBACK"

    fun enter(activity: Activity, playing: Boolean) {
        if (!isSupported(activity)) return
        runCatching { activity.enterPictureInPictureMode(buildParams(activity, playing)) }
    }

    /** Swaps the play/pause icon of the floating window to match the player. */
    fun refresh(activity: Activity, playing: Boolean) {
        if (!isSupported(activity)) return
        runCatching { activity.setPictureInPictureParams(buildParams(activity, playing)) }
    }

    private fun buildParams(activity: Activity, playing: Boolean): PictureInPictureParams {
        val title = if (playing) "Pause" else "Play"
        val icon = Icon.createWithResource(
            activity,
            if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
        )
        val intent = PendingIntent.getBroadcast(
            activity,
            0,
            Intent(ACTION_TOGGLE).setPackage(activity.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return PictureInPictureParams.Builder()
            .setAspectRatio(aspect)
            .setActions(listOf(RemoteAction(icon, title, title, intent)))
            .build()
    }
}
