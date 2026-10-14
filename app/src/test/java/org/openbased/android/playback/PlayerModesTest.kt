package org.openbased.android.playback

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.openbased.android.playback.PlayerModes.KeyAction
import org.openbased.android.playback.PlayerModes.Layout
import org.openbased.android.playback.PlayerModes.Role
import org.openbased.android.playback.PlayerModes.Screen

class PlayerModesTest {

    private val top = Screen(0, 1920, 1080)
    private val bottom = Screen(4, 1240, 1080)

    @Test
    fun pipAspectRatiosStayWithinAndroidsLimits() {
        assertEquals(1920 to 1080, PlayerModes.pipAspect(1920, 1080))
        assertEquals(239 to 100, PlayerModes.pipAspect(3840, 1200))
        assertEquals(100 to 239, PlayerModes.pipAspect(400, 1600))
        assertEquals(16 to 9, PlayerModes.pipAspect(0, 0))
    }

    @Test
    fun theVideoGoesToTheLargerScreen() {
        // Started on the main (top) panel of a dual-screen handheld: video here, controls below.
        assertEquals(Layout(4, Role.VIDEO), PlayerModes.dualLayout(listOf(top, bottom), currentId = 0))
        // Started on the small panel: controls here, video on the main panel.
        assertEquals(Layout(0, Role.CONTROLS), PlayerModes.dualLayout(listOf(top, bottom), currentId = 4))
        assertEquals(Layout(4, Role.CONTROLS), Layout(4, Role.VIDEO).swapped)
    }

    @Test
    fun virtualAndSwitchedOffDisplaysAreIgnored() {
        val recording = Screen(9, 3840, 2160, private = true)
        val off = Screen(5, 1240, 1080, on = false)
        assertNull(PlayerModes.dualLayout(listOf(top, recording, off), currentId = 0))
        assertNull(PlayerModes.dualLayout(listOf(top), currentId = 0))
        assertEquals(4, PlayerModes.dualLayout(listOf(top, recording, bottom), currentId = 0)!!.otherId)
    }

    @Test
    fun controllerButtonsMapToPlayerActions() {
        assertEquals(KeyAction.PLAY_PAUSE, PlayerModes.keyAction(KeyEvent.KEYCODE_BUTTON_A, controlsVisible = false))
        assertNull(PlayerModes.keyAction(KeyEvent.KEYCODE_BUTTON_A, controlsVisible = true))
        assertEquals(KeyAction.SEEK_FORWARD, PlayerModes.keyAction(KeyEvent.KEYCODE_BUTTON_R1, controlsVisible = true))
        assertNull(PlayerModes.keyAction(KeyEvent.KEYCODE_DPAD_LEFT, controlsVisible = true))
        assertEquals(KeyAction.SEEK_BACK, PlayerModes.keyAction(KeyEvent.KEYCODE_DPAD_LEFT, controlsVisible = false))
        assertEquals(KeyAction.SWAP_SCREENS, PlayerModes.keyAction(KeyEvent.KEYCODE_BUTTON_SELECT, controlsVisible = false))
        // Leaving always works, whatever is focused.
        assertEquals(KeyAction.CLOSE, PlayerModes.keyAction(KeyEvent.KEYCODE_BUTTON_B, controlsVisible = true))
        assertEquals(KeyAction.CLOSE, PlayerModes.keyAction(KeyEvent.KEYCODE_BACK, controlsVisible = false))
    }
}
