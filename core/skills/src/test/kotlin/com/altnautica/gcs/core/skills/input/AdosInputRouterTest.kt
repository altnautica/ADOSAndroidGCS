package com.altnautica.gcs.core.skills.input

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.altnautica.gcs.core.skills.MonotonicClock
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AdosInputRouterTest {
    @Before
    fun setUp() {
        mockkStatic(InputDevice::class)
        every { InputDevice.getDeviceIds() } returns IntArray(0)
    }

    @After
    fun tearDown() = unmockkStatic(InputDevice::class)

    private fun router(): AdosInputRouter {
        val context = mockk<Context>(relaxed = true) { every { getSystemService(InputManager::class.java) } returns null }
        return AdosInputRouter(context, MonotonicClock { 0L }).also {
            it.capture = object : InputCapture {
                override fun consumesChord(chord: String) = false
                override val capturesEnter = true
            }
        }
    }

    private fun key(action: Int, code: Int, repeat: Int, gamepad: Boolean = false, device: Int = 1) = mockk<KeyEvent> {
        every { this@mockk.action } returns action
        every { keyCode } returns code
        every { repeatCount } returns repeat
        every { isFromSource(any()) } returns gamepad
        every { metaState } returns 0
        every { deviceId } returns device
    }

    /** A joystick move with the left stick X at [leftX] and every other axis centred. */
    private fun stick(leftX: Float, device: Int) = mockk<MotionEvent> {
        every { isFromSource(InputDevice.SOURCE_JOYSTICK) } returns true
        every { actionMasked } returns MotionEvent.ACTION_MOVE
        every { this@mockk.device } returns null
        every { deviceId } returns device
        every { historySize } returns 0
        every { getAxisValue(any()) } returns 0f
        every { getAxisValue(MotionEvent.AXIS_X) } returns leftX
    }

    private fun gamepadDevice(): InputDevice = mockk {
        every { isVirtual } returns false
        every { supportsSource(any()) } returns true
    }

    @Test
    fun `an Enter hold whose first down was swallowed starts on the first repeat`() = runTest {
        val r = router()
        val events = mutableListOf<InputEvent>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { r.events.collect { events += it } }
        // Leaving touch mode consumed the down with repeatCount 0; the window delivers the repeats.
        r.onKey(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, repeat = 1))
        r.onKey(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, repeat = 2))
        r.onKey(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, repeat = 0))
        assertEquals(listOf(InputEvent.Enter(true), InputEvent.Enter(false)), events)
        job.cancel()
    }

    @Test
    fun `a gamepad D-pad press whose first down was swallowed still registers`() = runTest {
        val r = router()
        r.onKey(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, repeat = 1, gamepad = true))
        assertEquals(1 shl GamepadButton.DPAD_LEFT, r.buttons.value)
        r.onKey(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT, repeat = 0, gamepad = true))
        assertEquals(0, r.buttons.value)
    }

    @Test
    fun `neutralize releases held buttons`() = runTest {
        val r = router()
        r.onKey(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A, repeat = 0, gamepad = true))
        assertEquals(1 shl GamepadButton.A, r.buttons.value)
        r.neutralize()
        assertEquals(0, r.buttons.value)
        assertEquals(GamepadAxes.Neutral, r.axes.value)
    }

    @Test
    fun `motion from a non-active pad is ignored`() = runTest {
        val r = router()
        r.onMotion(stick(0.5f, device = 1))
        assertEquals(1, r.activeDeviceId.value)
        assertEquals(0.5f, r.axes.value.leftX, 0.001f)
        r.onMotion(stick(-0.9f, device = 2))
        assertEquals(0.5f, r.axes.value.leftX, 0.001f)
    }

    @Test
    fun `a button press on another pad neutralizes and takes over`() = runTest {
        val r = router()
        r.onMotion(stick(0.5f, device = 1))
        r.onKey(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_X, repeat = 0, gamepad = true, device = 1))
        // A release from the other pad does not take over.
        r.onKey(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A, repeat = 0, gamepad = true, device = 2))
        assertEquals(1, r.activeDeviceId.value)
        r.onKey(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A, repeat = 0, gamepad = true, device = 2))
        assertEquals(2, r.activeDeviceId.value)
        assertEquals(1 shl GamepadButton.A, r.buttons.value)
        assertEquals(GamepadAxes.Neutral, r.axes.value)
    }

    @Test
    fun `removing the active pad centres the sticks while another pad stays`() = runTest {
        val pad = gamepadDevice()
        every { InputDevice.getDeviceIds() } returns intArrayOf(1, 2)
        every { InputDevice.getDevice(any()) } returns pad
        val r = router()
        assertTrue(r.gamepadConnected.value)
        r.onMotion(stick(0.5f, device = 1))
        r.onKey(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A, repeat = 0, gamepad = true, device = 1))
        every { InputDevice.getDeviceIds() } returns intArrayOf(2)
        r.refreshDevices()
        assertNull(r.activeDeviceId.value)
        assertEquals(GamepadAxes.Neutral, r.axes.value)
        assertEquals(0, r.buttons.value)
        assertTrue(r.gamepadConnected.value)
        every { InputDevice.getDeviceIds() } returns IntArray(0)
        r.refreshDevices()
        assertFalse(r.gamepadConnected.value)
    }
}
