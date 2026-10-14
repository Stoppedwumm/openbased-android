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

    /**
     * A display as Android reports it. [private] displays (screen recording, other apps' virtual displays) are
     * never used.
     */
    data class Screen(val id: Int, val width: Int, val height: Int, val on: Boolean = true, val private: Boolean = false) {
        val area: Long get() = width.toLong() * height
    }

    enum class Role { VIDEO, CONTROLS }

    /** Which display is the other one, and what this display shows. */
    data class Layout(val otherId: Int, val here: Role) {
        val swapped: Layout get() = copy(here = if (here == Role.VIDEO) Role.CONTROLS else Role.VIDEO)
    }

    /**
     * For a player on [currentId]: the largest other usable display, with the video on whichever of the two is
     * larger (on dual-screen handhelds, the main panel) and the controls on the other. Null with one screen.
     */
    fun dualLayout(screens: List<Screen>, currentId: Int): Layout? {
        val current = screens.firstOrNull { it.id == currentId } ?: return null
        val other = screens.filter { it.id != currentId && it.on && !it.private && it.area > 0 }
            .maxByOrNull { it.area } ?: return null
        return Layout(other.id, if (current.area >= other.area) Role.VIDEO else Role.CONTROLS)
    }

    /** Actions for game controller and keyboard buttons, so the player works without touching the screen. */
    enum class KeyAction { PLAY_PAUSE, SEEK_BACK, SEEK_FORWARD, SWAP_SCREENS, TOGGLE_CONTROLS, CLOSE }

    /**
     * D-pad left/right and A are left alone while on-screen controls are showing, so they still move between and
     * press buttons.
     */
    fun keyAction(keyCode: Int, controlsVisible: Boolean): KeyAction? = when (keyCode) {
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_SPACE -> if (controlsVisible) null else KeyAction.PLAY_PAUSE
        KeyEvent.KEYCODE_BUTTON_L1 -> KeyAction.SEEK_BACK
        KeyEvent.KEYCODE_BUTTON_R1 -> KeyAction.SEEK_FORWARD
        KeyEvent.KEYCODE_DPAD_LEFT -> if (controlsVisible) null else KeyAction.SEEK_BACK
        KeyEvent.KEYCODE_DPAD_RIGHT -> if (controlsVisible) null else KeyAction.SEEK_FORWARD
        KeyEvent.KEYCODE_BUTTON_SELECT -> KeyAction.SWAP_SCREENS
        KeyEvent.KEYCODE_BUTTON_Y -> KeyAction.TOGGLE_CONTROLS
        KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_BACK -> KeyAction.CLOSE
        else -> null
    }
}
