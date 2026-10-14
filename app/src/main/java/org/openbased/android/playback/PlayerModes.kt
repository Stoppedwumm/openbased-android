package org.openbased.android.playback

import android.view.KeyEvent

/** Decisions for picture-in-picture, dual screens and game controllers, kept free of Android state for tests. */
object PlayerModes {

    /** Android rejects picture-in-picture aspect ratios outside 1:2.39 … 2.39:1. */
    private const val MAX_RATIO = 2.39

    /**
     * The picture-in-picture aspect ratio for a video of [width] × [height], clamped to what Android accepts, as
     * numerator and denominator; 16:9 when the size is unknown.
     */
    fun pipAspect(width: Int, height: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 16 to 9
        val ratio = width.toDouble() / height
        return when {
            ratio > MAX_RATIO -> 239 to 100
            ratio < 1 / MAX_RATIO -> 100 to 239
            else -> width to height
        }
    }

    /** A display the app could put video on. */
    data class Screen(val id: Int, val presentation: Boolean, val valid: Boolean = true)

    /**
     * The display for the video when the player runs on [currentId]: another valid display that can show a
     * presentation, preferring the lowest ID (on dual-screen consoles, the built-in second panel), or null.
     */
    fun otherScreen(screens: List<Screen>, currentId: Int): Int? =
        screens.filter { it.valid && it.presentation && it.id != currentId }.minByOrNull { it.id }?.id

    /** Actions for game controller and keyboard buttons, so the player works without touching the screen. */
    enum class KeyAction { PLAY_PAUSE, SEEK_BACK, SEEK_FORWARD, SWAP_SCREENS, TOGGLE_CONTROLS }

    /**
     * D-pad left/right are left alone while the controls are focused, so they still move between buttons.
     */
    fun keyAction(keyCode: Int, controlsVisible: Boolean): KeyAction? = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_SPACE -> if (controlsVisible) null else KeyAction.PLAY_PAUSE
        KeyEvent.KEYCODE_BUTTON_L1 -> KeyAction.SEEK_BACK
        KeyEvent.KEYCODE_BUTTON_R1 -> KeyAction.SEEK_FORWARD
        KeyEvent.KEYCODE_DPAD_LEFT -> if (controlsVisible) null else KeyAction.SEEK_BACK
        KeyEvent.KEYCODE_DPAD_RIGHT -> if (controlsVisible) null else KeyAction.SEEK_FORWARD
        KeyEvent.KEYCODE_BUTTON_SELECT -> KeyAction.SWAP_SCREENS
        KeyEvent.KEYCODE_BUTTON_Y -> KeyAction.TOGGLE_CONTROLS
        else -> null
    }
}
