package org.openbased.android.playback

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Display
import android.view.KeyEvent
import android.view.WindowManager
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * Shows the video, with subtitles and no controls, full screen on a second display (the other panel of a
 * dual-screen device, or an external display). The controls stay on the activity's screen.
 */
@OptIn(UnstableApi::class)
class VideoPresentation(
    context: Context,
    display: Display,
    private val player: Player,
    /** Keys pressed while this window has focus go to the player screen, so Back closes playback. */
    private val onKey: (KeyEvent) -> Boolean,
) : Presentation(context, display) {

    private lateinit var view: PlayerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setCancelable(false)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view = PlayerView(context).apply {
            useController = false
            setShutterBackgroundColor(Color.BLACK)
            setBackgroundColor(Color.BLACK)
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            player = this@VideoPresentation.player
        }
        setContentView(view)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean = onKey(event) || super.dispatchKeyEvent(event)

    /** Releases the video surface, so the player can render on the activity's screen again. */
    fun detach() {
        if (::view.isInitialized) view.player = null
    }
}
