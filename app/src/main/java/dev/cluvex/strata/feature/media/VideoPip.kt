package dev.cluvex.strata.feature.media

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
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

    fun enter(activity: Activity) {
        if (!isSupported(activity)) return
        runCatching { activity.enterPictureInPictureMode(buildParams()) }
    }

    private fun buildParams(): PictureInPictureParams =
        PictureInPictureParams.Builder().setAspectRatio(aspect).build()
}
