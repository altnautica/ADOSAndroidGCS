package com.altnautica.gcs.core.skills.input

import android.view.KeyEvent
import com.altnautica.gcs.core.skills.binding.Loadout
import com.altnautica.gcs.core.skills.binding.LoadoutSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InputMappingTest {
    @Test
    fun `values inside the flat range read zero`() {
        assertEquals(0f, applyDeadZone(0.08f, 0.1f, 0f), 0f)
        assertEquals(0f, applyDeadZone(-0.1f, 0.1f, 0f), 0f)
    }

    @Test
    fun `travel outside the flat range is rescaled to full deflection`() {
        assertEquals(1f, applyDeadZone(1f, 0.1f, 0f), 1e-6f)
        assertEquals(-1f, applyDeadZone(-1f, 0.1f, 0f), 1e-6f)
        assertEquals(0.5f, applyDeadZone(0.55f, 0.1f, 0f), 1e-6f)
        assertEquals(0.3f, applyDeadZone(0.3f, 0f, 0f), 1e-6f)
    }

    @Test
    fun `the operator dead zone adds to the hardware flat range and is capped`() {
        assertEquals(0f, applyDeadZone(0.25f, 0.1f, 0.15f), 0f)
        assertEquals(0.5f, applyDeadZone(0.625f, 0.1f, 0.15f), 1e-6f)
        assertEquals(0f, applyDeadZone(0.98f, 0.9f, 0.2f), 0f)
        assertEquals(1f, applyDeadZone(1f, 0.9f, 0.2f), 1e-6f)
    }

    @Test
    fun `reserved buttons never reach the manual control bitmask`() {
        val all = 0x1FFFF
        val mask = ReservedInputs.manualControlButtonMask(all, Loadout(slots = emptyList()))
        for (b in ReservedInputs.GAMEPAD_BUTTONS) assertEquals("button $b", 0, mask and (1 shl b))
        val expected = (1 shl GamepadButton.A) or (1 shl GamepadButton.X) or (1 shl GamepadButton.L1) or
            (1 shl GamepadButton.R1) or (1 shl GamepadButton.L2) or (1 shl GamepadButton.R2)
        assertEquals(expected, mask)
    }

    @Test
    fun `skill-bound buttons never reach the manual control bitmask`() {
        val all = 0x1FFFF
        assertEquals(0, ReservedInputs.manualControlButtonMask(all, Loadout.DEFAULT))
        val onlyArm = Loadout(slots = listOf(LoadoutSlot(0, "arm", null, GamepadButton.A)))
        val mask = ReservedInputs.manualControlButtonMask(all, onlyArm)
        assertEquals(0, mask and (1 shl GamepadButton.A))
        assertEquals(1 shl GamepadButton.X, mask and (1 shl GamepadButton.X))
    }

    @Test
    fun `gamepad key codes map to the standard layout`() {
        assertEquals(GamepadButton.A, GamepadButton.fromKeyCode(KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(GamepadButton.START, GamepadButton.fromKeyCode(KeyEvent.KEYCODE_BUTTON_START))
        assertEquals(GamepadButton.DPAD_LEFT, GamepadButton.fromKeyCode(KeyEvent.KEYCODE_DPAD_LEFT))
        assertNull(GamepadButton.fromKeyCode(KeyEvent.KEYCODE_A))
    }

    @Test
    fun `keyboard chords use a fixed modifier order`() {
        val shift = KeyEvent.META_SHIFT_ON
        assertEquals("shift+a", canonicalChord(KeyEvent.KEYCODE_A, shift, 'A'.code))
        assertEquals("ctrl+shift+s", canonicalChord(KeyEvent.KEYCODE_S, KeyEvent.META_CTRL_ON or shift, 0))
        assertEquals("f2", canonicalChord(KeyEvent.KEYCODE_F2, 0, 0))
        assertEquals("3", canonicalChord(KeyEvent.KEYCODE_NUMPAD_3, 0, '3'.code))
        assertEquals("shift+<", canonicalChord(KeyEvent.KEYCODE_COMMA, shift, '<'.code))
        assertEquals("`", canonicalChord(KeyEvent.KEYCODE_GRAVE, 0, '`'.code))
        assertNull(canonicalChord(KeyEvent.KEYCODE_SHIFT_LEFT, shift, 0))
    }
}
