package org.openbased.android.playback

import android.view.KeyEvent
import androidx.media3.common.Player

/**
 * Connects the player screen with its companion on the other display. The player screen owns the player; the
 * companion only shows it (video, or controls) and reports what the user does there.
 */
object PlaybackHolder {

    /** What the player screen offers its companion. */
    interface Host {
        val player: Player?
        val title: String
        fun swapScreens()
        fun close()
        fun handleKey(event: KeyEvent): Boolean
        /** The companion could not open on the requested display. */
        fun companionFailed()
    }

    @Volatile var host: Host? = null
    @Volatile var companion: CompanionActivity? = null
}
