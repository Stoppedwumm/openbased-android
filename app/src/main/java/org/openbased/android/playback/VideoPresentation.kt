package org.openbased.android.playback

import android.app.Presentation
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Display
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
class VideoPresentation(context: Context, display: Display, private val player: Player) :
    Presentation(context, display) {

    private lateinit var view: PlayerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    /** Releases the video surface, so the player can render on the activity's screen again. */
    fun detach() {
        if (::view.isInitialized) view.player = null
    }
}
