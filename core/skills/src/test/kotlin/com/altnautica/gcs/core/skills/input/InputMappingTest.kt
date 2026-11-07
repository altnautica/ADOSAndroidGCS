package com.altnautica.gcs.core.skills.input

import android.view.KeyEvent
import com.altnautica.gcs.core.skills.binding.Ds4Bindings
import com.altnautica.gcs.core.skills.controls.AxisCalibration
import com.altnautica.gcs.core.skills.controls.StickMode
import com.altnautica.gcs.core.skills.controls.radialDeadzone
import com.altnautica.gcs.core.skills.testSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InputMappingTest {
    @Test
    fun `values inside the flat range read zero`() {
        assertEquals(0f, applyDeadZone(0.08f, 0.1f), 0f)
        assertEquals(0f, applyDeadZone(-0.1f, 0.1f), 0f)
    }

    @Test
    fun `travel outside the flat range is rescaled to full deflection`() {
        assertEquals(1f, applyDeadZone(1f, 0.1f), 1e-6f)
        assertEquals(-1f, applyDeadZone(-1f, 0.1f), 1e-6f)
        assertEquals(0.5f, applyDeadZone(0.55f, 0.1f), 1e-6f)
        assertEquals(0.3f, applyDeadZone(0.3f, 0f), 1e-6f)
    }

    @Test
    fun `the radial dead zone zeroes the centre and keeps direction and full travel`() {
        assertEquals(0f to 0f, radialDeadzone(0.05f, 0.05f, 0.08f))
        val (x, y) = radialDeadzone(1f, 0f, 0.08f)
        assertEquals(1f, x, 1e-6f)
        assertEquals(0f, y, 0f)
        val (dx, dy) = radialDeadzone(0.6f, 0.6f, 0.08f)
        assertEquals(dx, dy, 1e-6f)
    }

    @Test
    fun `calibration maps the pad's own centre and extents to the unit range`() {
        val c = AxisCalibration(centre = 0.1f, min = -0.8f, max = 0.9f)
        assertEquals(0f, c.normalise(0.1f), 1e-6f)
        assertEquals(1f, c.normalise(0.9f), 1e-6f)
        assertEquals(-1f, c.normalise(-0.8f), 1e-6f)
    }

    @Test
    fun `mode 2 puts throttle on the left stick and mode 1 swaps the Y axes`() {
        val a = GamepadAxes(leftX = 0.1f, leftY = 0.2f, rightX = 0.3f, rightY = 0.4f)
        assertArrayEquals(floatArrayOf(0.3f, 0.4f, 0.2f, 0.1f), StickMode.Mode2.axes(a), 0f)
        assertArrayEquals(floatArrayOf(0.3f, 0.2f, 0.4f, 0.1f), StickMode.Mode1.axes(a), 0f)
    }

    @Test
    fun `bound buttons never reach the manual control bitmask`() {
        val all = 0x3FFFF
        val spec = testSpec.ds4
        // The shipped map binds every button, so nothing leaks to the flight controller.
        assertEquals(0, Ds4Bindings.manualControlButtonMask(all, Ds4Bindings.boundButtonMask(spec, spec.bindings)))
        val onlyCross = spec.bindings.filter { it.control == "cross" && it.with.isEmpty() }
        val mask = Ds4Bindings.manualControlButtonMask(all, Ds4Bindings.boundButtonMask(spec, onlyCross))
        assertEquals(0, mask and (1 shl spec.stream.buttonsBitfield.getValue("cross")))
        assertEquals(1 shl spec.stream.buttonsBitfield.getValue("square"), mask and (1 shl spec.stream.buttonsBitfield.getValue("square")))
        // Bits above the 16 MANUAL_CONTROL carries (PS, touchpad) are always dropped.
        assertEquals(0, mask ushr 16)
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
