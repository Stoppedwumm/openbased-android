package org.openbased.android.playback

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * Runs on the second display of a dual-screen device and shows the player screen's playback there: either the
 * video (full screen, no controls) or the touch controls. Closing it, or pressing Back, ends playback on both.
 */
@OptIn(UnstableApi::class)
class CompanionActivity : ComponentActivity() {

    private var video: PlayerView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val host = PlaybackHolder.host
        val player = host?.player
        val wanted = intent.getIntExtra(EXTRA_DISPLAY_ID, -1)
        if (host == null || player == null) {
            finish()
            return
        }
        if (displayId() != wanted) {
            // The device opened this on the wrong screen: it does not let apps start on the other display.
            host.companionFailed()
            finish()
            return
        }
        PlaybackHolder.companion = this
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val root = object : FrameLayout(this) {
            override fun dispatchKeyEvent(event: KeyEvent) =
                PlaybackHolder.host?.handleKey(event) == true || super.dispatchKeyEvent(event)
        }.apply { setBackgroundColor(Color.BLACK) }
        val content = if (intent.getStringExtra(EXTRA_ROLE) == PlayerModes.Role.VIDEO.name) {
            PlayerView(this).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(Color.BLACK)
                this.player = player
            }.also { video = it }
        } else {
            controlPanel(this, player, host.title, onSwap = { host.swapScreens() }, onClose = { host.close() })
        }
        root.addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                PlaybackHolder.host?.close() ?: finish()
            }
        })
    }

    /** Called by the player screen when it stops showing playback here. */
    fun release() {
        video?.player = null
        video = null
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        video?.player = null
        if (PlaybackHolder.companion === this) PlaybackHolder.companion = null
    }

    private fun displayId(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display?.displayId ?: -1
        else @Suppress("DEPRECATION") windowManager.defaultDisplay.displayId

    companion object {
        private const val EXTRA_DISPLAY_ID = "displayId"
        private const val EXTRA_ROLE = "role"

        fun intent(context: Context, displayId: Int, role: PlayerModes.Role): Intent =
            Intent(context, CompanionActivity::class.java)
                .putExtra(EXTRA_DISPLAY_ID, displayId)
                .putExtra(EXTRA_ROLE, role.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
    }
}
