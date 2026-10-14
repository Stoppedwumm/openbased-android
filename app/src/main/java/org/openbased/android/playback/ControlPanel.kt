package org.openbased.android.playback

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerControlView
import org.openbased.android.R

/**
 * Touch controls for the screen without the video: title, always-visible transport controls (with subtitle and
 * audio track choice), and buttons to swap screens and close.
 */
@OptIn(UnstableApi::class)
fun controlPanel(context: Context, player: Player, title: String, onSwap: () -> Unit, onClose: () -> Unit): View =
    LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundColor(Color.rgb(18, 18, 22))
        setPadding(32, 32, 32, 32)
        addView(TextView(context).apply {
            text = title
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        })
        addView(TextView(context).apply {
            setText(R.string.playing_on_other_screen)
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
        })
        addView(
            PlayerControlView(context).apply {
                this.player = player
                showTimeoutMs = 0
                setShowSubtitleButton(true)
                show()
            },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )
        addView(LinearLayout(context).apply {
            gravity = Gravity.CENTER
            addView(Button(context).apply {
                setText(R.string.swap_screens)
                setOnClickListener { onSwap() }
            })
            addView(Button(context).apply {
                setText(R.string.close_player)
                setOnClickListener { onClose() }
            })
        })
    }
