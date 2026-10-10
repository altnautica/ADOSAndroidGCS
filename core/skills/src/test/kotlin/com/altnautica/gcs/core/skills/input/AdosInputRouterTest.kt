package com.altnautica.gcs.core.skills.input

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyEvent
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

    private fun key(action: Int, code: Int, repeat: Int, gamepad: Boolean = false) = mockk<KeyEvent> {
        every { this@mockk.action } returns action
        every { keyCode } returns code
        every { repeatCount } returns repeat
        every { isFromSource(any()) } returns gamepad
        every { metaState } returns 0
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
}
