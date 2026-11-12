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
import kotlinx.coroutines.test.TestScope
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

    private var now = 0L

    private fun capture(uiNavigation: Boolean = false) = object : InputCapture {
        override fun consumesChord(chord: String) = false
        override val capturesEnter = true
        override val uiNavigation = uiNavigation
    }

    private fun router(capture: InputCapture? = capture()): AdosInputRouter {
        val context = mockk<Context>(relaxed = true) { every { getSystemService(InputManager::class.java) } returns null }
        return AdosInputRouter(context, MonotonicClock { now }).also { it.capture = capture }
    }

    private fun key(action: Int, code: Int, repeat: Int, gamepad: Boolean = false, device: Int = 1) = mockk<KeyEvent> {
        every { this@mockk.action } returns action
        every { keyCode } returns code
        every { repeatCount } returns repeat
        every { isFromSource(any()) } returns gamepad
        every { metaState } returns 0
        every { getUnicodeChar(any()) } returns 0
        every { deviceId } returns device
        every { this@mockk.device } returns null
    }

    /** Presses and releases a gamepad key after [holdMs]; returns whether down and up were consumed. */
    private fun press(r: AdosInputRouter, code: Int, holdMs: Long = 50): Pair<Boolean, Boolean> {
        val down = r.onKey(key(KeyEvent.ACTION_DOWN, code, repeat = 0, gamepad = true))
        now += holdMs
        val up = r.onKey(key(KeyEvent.ACTION_UP, code, repeat = 0, gamepad = true))
        return down to up
    }

    /** A joystick move with the given axes and every other axis centred. */
    private fun motion(device: Int, vararg values: Pair<Int, Float>) = mockk<MotionEvent> {
        every { isFromSource(InputDevice.SOURCE_JOYSTICK) } returns true
        every { actionMasked } returns MotionEvent.ACTION_MOVE
        every { this@mockk.device } returns null
        every { deviceId } returns device
        every { historySize } returns 0
        every { getAxisValue(any()) } returns 0f
        values.forEach { (axis, v) -> every { getAxisValue(axis) } returns v }
    }

    /** A joystick move with the left stick X at [leftX] and every other axis centred. */
    private fun stick(leftX: Float, device: Int) = motion(device, MotionEvent.AXIS_X to leftX)

    private fun TestScope.collectUi(r: AdosInputRouter): MutableList<UiInput> {
        val out = mutableListOf<UiInput>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { r.uiInput.collect { out += it } }
        return out
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

    @Test
    fun `outside the cockpit Y is consumed and emits Details, never a click`() = runTest {
        val r = router(capture = null)
        val ui = collectUi(r)
        assertEquals(true to true, press(r, KeyEvent.KEYCODE_BUTTON_Y))
        assertEquals(listOf<UiInput>(UiInput.Command(UiCommand.Details)), ui)
        assertTrue(r.lastInputController.value)
    }

    @Test
    fun `outside the cockpit A activates and B goes back`() = runTest {
        val r = router(capture = null)
        val ui = collectUi(r)
        assertEquals(true to true, press(r, KeyEvent.KEYCODE_BUTTON_A))
        assertEquals(true to true, press(r, KeyEvent.KEYCODE_BUTTON_B))
        assertEquals(listOf(UiInput.Activate(true), UiInput.Activate(false), UiInput.Back), ui)
    }

    @Test
    fun `Home tap opens the Quick menu and a hold goes Home`() = runTest {
        val r = router(capture = null)
        val ui = collectUi(r)
        press(r, KeyEvent.KEYCODE_BUTTON_MODE, holdMs = 200)
        press(r, KeyEvent.KEYCODE_BUTTON_MODE, holdMs = 700)
        assertEquals(listOf<UiInput>(UiInput.Command(UiCommand.QuickMenu), UiInput.Command(UiCommand.Home)), ui)
    }

    @Test
    fun `Start tap is the screen menu and a hold the Quick menu`() = runTest {
        val r = router(capture = null)
        val ui = collectUi(r)
        press(r, KeyEvent.KEYCODE_BUTTON_START, holdMs = 100)
        press(r, KeyEvent.KEYCODE_BUTTON_START, holdMs = 650)
        assertEquals(listOf<UiInput>(UiInput.Command(UiCommand.Menu), UiInput.Command(UiCommand.QuickMenu)), ui)
    }

    @Test
    fun `X tap is More and a hold only ends with More up`() = runTest {
        val r = router(capture = null)
        val ui = collectUi(r)
        press(r, KeyEvent.KEYCODE_BUTTON_X, holdMs = 100)
        press(r, KeyEvent.KEYCODE_BUTTON_X, holdMs = 400)
        val c = { u: UiCommand -> UiInput.Command(u) }
        assertEquals(
            listOf(c(UiCommand.MoreDown), c(UiCommand.MoreUp), c(UiCommand.More), c(UiCommand.MoreDown), c(UiCommand.MoreUp)),
            ui,
        )
    }

    @Test
    fun `stick buttons and repeats are consumed without UI input`() = runTest {
        val r = router(capture = null)
        val ui = collectUi(r)
        assertEquals(true to true, press(r, KeyEvent.KEYCODE_BUTTON_THUMBL))
        assertEquals(true to true, press(r, KeyEvent.KEYCODE_BUTTON_1))
        r.onKey(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_L1, repeat = 0, gamepad = true))
        assertTrue(r.onKey(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_L1, repeat = 1, gamepad = true)))
        assertEquals(listOf<UiInput>(UiInput.Command(UiCommand.PrevTab)), ui)
    }

    @Test
    fun `a gamepad D-pad key is left to focus navigation but still updates the bitmask`() = runTest {
        val r = router(capture = null)
        assertFalse(r.onKey(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, repeat = 0, gamepad = true)))
        assertEquals(1 shl GamepadButton.DPAD_DOWN, r.buttons.value)
    }

    @Test
    fun `a keyboard centre key is not consumed`() = runTest {
        val r = router(capture = null)
        assertFalse(r.onKey(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, repeat = 0, gamepad = false)))
        assertFalse(r.onKey(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, repeat = 0, gamepad = false)))
    }

    @Test
    fun `the hat holds a navigation direction until it centres`() = runTest {
        val r = router(capture = null)
        val ui = collectUi(r)
        assertTrue(r.onMotion(motion(1, MotionEvent.AXIS_HAT_X to -1f)))
        assertTrue(r.onMotion(motion(1, MotionEvent.AXIS_HAT_X to -1f)))
        assertTrue(r.onMotion(motion(1)))
        assertEquals(listOf(UiInput.Direction(NavDirection.Left), UiInput.Direction(null)), ui)
    }

    @Test
    fun `the left stick navigates with hysteresis`() = runTest {
        val r = router(capture = null)
        val ui = collectUi(r)
        r.onMotion(motion(1, MotionEvent.AXIS_Y to 0.5f))
        r.onMotion(motion(1, MotionEvent.AXIS_Y to 0.7f))
        r.onMotion(motion(1, MotionEvent.AXIS_Y to 0.45f))
        r.onMotion(motion(1, MotionEvent.AXIS_Y to 0.3f))
        // Axis Y is positive with the stick pulled down.
        assertEquals(listOf(UiInput.Direction(NavDirection.Down), UiInput.Direction(null)), ui)
    }

    @Test
    fun `touch makes touch the last input`() = runTest {
        val r = router(capture = null)
        press(r, KeyEvent.KEYCODE_BUTTON_A)
        assertTrue(r.lastInputController.value)
        r.onTouch()
        assertFalse(r.lastInputController.value)
    }

    @Test
    fun `with a flight capture A is a button event and no UI input`() = runTest {
        val r = router()
        val ui = collectUi(r)
        val events = mutableListOf<InputEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { r.events.collect { events += it } }
        assertEquals(true to true, press(r, KeyEvent.KEYCODE_BUTTON_A, holdMs = 30))
        assertEquals(listOf(InputEvent.Button(GamepadButton.A, true, 0), InputEvent.Button(GamepadButton.A, false, 30)), events)
        assertTrue(ui.isEmpty())
        // The sticks fly, so they never navigate.
        r.onMotion(stick(0.9f, device = 1))
        assertTrue(ui.isEmpty())
    }

    @Test
    fun `with a flight capture PS belongs to the cockpit and only a Start hold reaches the app`() = runTest {
        val r = router()
        val ui = collectUi(r)
        val events = mutableListOf<InputEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { r.events.collect { events += it } }
        // A PS tap brakes and a 2 s hold returns home in the cockpit: neither may open the Quick menu or go Home.
        press(r, KeyEvent.KEYCODE_BUTTON_MODE, holdMs = 100)
        press(r, KeyEvent.KEYCODE_BUTTON_MODE, holdMs = 2_100)
        press(r, KeyEvent.KEYCODE_BUTTON_START, holdMs = 100)
        press(r, KeyEvent.KEYCODE_BUTTON_START, holdMs = 700)
        assertEquals(listOf<UiInput>(UiInput.Command(UiCommand.QuickMenu)), ui)
        assertTrue("the cockpit still sees PS", events.any { it is InputEvent.Button && it.button == GamepadButton.HOME })
    }

    @Test
    fun `a cockpit sheet navigating with buttons still leaves PS to the cockpit`() = runTest {
        val r = router(capture(uiNavigation = true))
        val ui = collectUi(r)
        press(r, KeyEvent.KEYCODE_BUTTON_MODE, holdMs = 2_100)
        assertTrue(ui.isEmpty())
    }

    @Test
    fun `in a cockpit sheet a kill chord's Circle does not go back`() = runTest {
        val r = router(capture(uiNavigation = true))
        val ui = collectUi(r)
        r.onKey(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_L2, repeat = 0, gamepad = true))
        r.onKey(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_R2, repeat = 0, gamepad = true))
        ui.clear()
        press(r, KeyEvent.KEYCODE_BUTTON_B, holdMs = 1_500)
        assertFalse("Circle in a chord must not close the sheet", UiInput.Back in ui)
    }

    @Test
    fun `in a cockpit sheet an Options and Circle chord neither goes back nor opens the menu`() = runTest {
        val r = router(capture(uiNavigation = true))
        val ui = collectUi(r)
        r.onKey(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_START, repeat = 0, gamepad = true))
        press(r, KeyEvent.KEYCODE_BUTTON_B, holdMs = 1_500)
        r.onKey(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_START, repeat = 0, gamepad = true))
        assertTrue(ui.isEmpty())
    }

    @Test
    fun `in a cockpit sheet a lone Circle still goes back after a chord ends`() = runTest {
        val r = router(capture(uiNavigation = true))
        val ui = collectUi(r)
        r.onKey(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_START, repeat = 0, gamepad = true))
        press(r, KeyEvent.KEYCODE_BUTTON_B)
        r.onKey(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_START, repeat = 0, gamepad = true))
        press(r, KeyEvent.KEYCODE_BUTTON_B)
        assertEquals(listOf<UiInput>(UiInput.Back), ui)
    }

    @Test
    fun `a chord started under a held Cross releases it at once and its later release confirms nothing`() = runTest {
        val r = router(capture(uiNavigation = true))
        val ui = collectUi(r)
        r.onKey(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A, repeat = 0, gamepad = true))
        assertEquals(listOf<UiInput>(UiInput.Activate(true)), ui)
        ui.clear()
        // Circle while Cross is held starts a chord: the held confirm must end now, not on a
        // later Cross-up, and its release is cancelled so the focused control does not click.
        r.onKey(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_B, repeat = 0, gamepad = true))
        assertEquals(listOf<UiInput>(UiInput.Activate(pressed = false, canceled = true)), ui)
        assertEquals(KeyEvent.FLAG_CANCELED, (ui.single() as UiInput.Activate).keyFlags)
        ui.clear()
        r.onKey(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_A, repeat = 0, gamepad = true))
        r.onKey(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_B, repeat = 0, gamepad = true))
        assertTrue("nothing after the chord start: no second release, no Back", ui.isEmpty())
    }

    @Test
    fun `losing the pad under a held Cross cancels the release`() = runTest {
        val pad = gamepadDevice()
        every { InputDevice.getDeviceIds() } returns intArrayOf(1)
        every { InputDevice.getDevice(any()) } returns pad
        val r = router(capture = null)
        val ui = collectUi(r)
        r.onKey(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A, repeat = 0, gamepad = true, device = 1))
        ui.clear()
        every { InputDevice.getDeviceIds() } returns IntArray(0)
        r.refreshDevices()
        assertEquals(listOf<UiInput>(UiInput.Activate(pressed = false, canceled = true)), ui)
        assertEquals(KeyEvent.FLAG_CANCELED, (ui.single() as UiInput.Activate).keyFlags)
    }

    @Test
    fun `a plain Cross release is not cancelled`() {
        assertEquals(0, UiInput.Activate(pressed = false).keyFlags)
    }

    @Test
    fun `a chord started under a held More ends the hold without a More tap`() = runTest {
        val r = router(capture(uiNavigation = true))
        val ui = collectUi(r)
        r.onKey(key(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_X, repeat = 0, gamepad = true))
        ui.clear()
        press(r, KeyEvent.KEYCODE_BUTTON_B)
        r.onKey(key(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_X, repeat = 0, gamepad = true))
        assertEquals(listOf<UiInput>(UiInput.Command(UiCommand.MoreUp)), ui)
    }

    @Test
    fun `a capture overlay navigates with buttons while the sticks keep flying`() = runTest {
        val r = router(capture(uiNavigation = true))
        val ui = collectUi(r)
        press(r, KeyEvent.KEYCODE_BUTTON_A)
        r.onMotion(stick(0.9f, device = 1))
        assertEquals(0.9f, r.axes.value.leftX, 0.001f)
        r.onMotion(motion(1, MotionEvent.AXIS_HAT_Y to 1f))
        assertEquals(listOf(UiInput.Activate(true), UiInput.Activate(false), UiInput.Direction(NavDirection.Down)), ui)
    }

    @Test
    fun `analog triggers read 0 to 1 and fall back to brake and gas`() = runTest {
        val r = router(capture = null)
        r.onMotion(motion(1, MotionEvent.AXIS_BRAKE to 0.5f, MotionEvent.AXIS_GAS to 1f))
        assertEquals(0.5f, r.axes.value.leftTrigger, 0.001f)
        assertEquals(1f, r.axes.value.rightTrigger, 0.001f)
    }

    @Test
    fun `older DualShock drivers report the triggers on RX and RY`() = runTest {
        val r = router(capture = null)
        r.onMotion(motion(1, MotionEvent.AXIS_RX to 0.25f, MotionEvent.AXIS_RY to 0.75f))
        assertEquals(0.25f, r.axes.value.leftTrigger, 0.001f)
        assertEquals(0.75f, r.axes.value.rightTrigger, 0.001f)
        // The right stick stays on Z / RZ.
        assertEquals(0f, r.axes.value.rightX, 0f)
    }

    @Test
    fun `suppressed stick navigation keeps the stick reading but moves no focus`() = runTest {
        val r = router(capture = null)
        val ui = collectUi(r)
        r.suppressStickNavigation = true
        r.onMotion(stick(0.9f, device = 1))
        assertEquals(0.9f, r.axes.value.leftX, 0.001f)
        r.onMotion(motion(1, MotionEvent.AXIS_HAT_X to 1f))
        assertEquals(listOf<UiInput>(UiInput.Direction(NavDirection.Right)), ui)
    }
}
