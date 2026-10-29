package dev.cluvex.strata.feature.media

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * "Background play for this playback only". The headphones buttons (video bar, music player, floating window)
 * switch it on; the saved Background Play setting is never changed. It stays on until the notification's X or the
 * music player's Stop button is pressed, then Background Play is back to what the setting says. Main thread only.
 */
object BackgroundSession {
    var active by mutableStateOf(false)
        private set

    fun start() {
        active = true
    }

    fun end() {
        active = false
    }
}
