package org.openbased.android.playback

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.openbased.android.playback.PlayerModes.KeyAction
import org.openbased.android.playback.PlayerModes.Screen

class PlayerModesTest {

    @Test
    fun pipAspectRatiosStayWithinAndroidsLimits() {
        assertEquals(1920 to 1080, PlayerModes.pipAspect(1920, 1080))
        assertEquals(239 to 100, PlayerModes.pipAspect(3840, 1200))
        assertEquals(100 to 239, PlayerModes.pipAspect(400, 1600))
        assertEquals(16 to 9, PlayerModes.pipAspect(0, 0))
    }

    @Test
    fun videoGoesToAnotherPresentationScreen() {
        val screens = listOf(Screen(0, false), Screen(2, true), Screen(5, true), Screen(7, true, valid = false))
        assertEquals(2, PlayerModes.otherScreen(screens, currentId = 0))
        // The player was moved to the second screen: the video goes to the first one if it can show it.
        assertEquals(5, PlayerModes.otherScreen(screens, currentId = 2))
        assertNull(PlayerModes.otherScreen(listOf(Screen(0, false)), currentId = 0))
    }

    @Test
    fun controllerButtonsMapToPlayerActions() {
        assertEquals(KeyAction.PLAY_PAUSE, PlayerModes.keyAction(KeyEvent.KEYCODE_BUTTON_A, controlsVisible = false))
        assertNull(PlayerModes.keyAction(KeyEvent.KEYCODE_BUTTON_A, controlsVisible = true))
        assertEquals(KeyAction.SEEK_FORWARD, PlayerModes.keyAction(KeyEvent.KEYCODE_BUTTON_R1, controlsVisible = true))
        assertNull(PlayerModes.keyAction(KeyEvent.KEYCODE_DPAD_LEFT, controlsVisible = true))
        assertEquals(KeyAction.SEEK_BACK, PlayerModes.keyAction(KeyEvent.KEYCODE_DPAD_LEFT, controlsVisible = false))
        assertEquals(KeyAction.SWAP_SCREENS, PlayerModes.keyAction(KeyEvent.KEYCODE_BUTTON_SELECT, controlsVisible = false))
    }
}
