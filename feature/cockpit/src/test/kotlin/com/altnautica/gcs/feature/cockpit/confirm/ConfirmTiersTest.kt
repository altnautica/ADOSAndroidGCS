package com.altnautica.gcs.feature.cockpit.confirm

import com.altnautica.gcs.core.model.ConfirmGesture
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.skills.ConfirmRequest
import com.altnautica.gcs.core.skills.ConfirmResult
import com.altnautica.gcs.core.skills.MonotonicClock
import com.altnautica.gcs.core.skills.builtin.SkillArgs
import com.altnautica.gcs.core.skills.input.GamepadButton
import com.altnautica.gcs.feature.cockpit.preflight.PreflightCheck
import com.altnautica.gcs.feature.cockpit.preflight.PreflightItem
import com.altnautica.gcs.feature.cockpit.testBuiltin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ConfirmTiersTest {
    private val arm = testBuiltin("arm")
    private val land = testBuiltin("land")
    private val takeoff = testBuiltin("takeoff")
    private val kill = testBuiltin("kill")

    @Test
    fun `hold completes at 800 ms and not before`() {
        val m = ConfirmHoldMachine(ConfirmGesture.Hold, 800, openedAtMs = 0)
        m.press(100)
        assertFalse(m.poll(899))
        assertTrue(m.poll(900))
        assertFalse("fires once", m.poll(1000))
    }

    @Test
    fun `releasing early resets the hold`() {
        val m = ConfirmHoldMachine(ConfirmGesture.Hold, 800, openedAtMs = 0)
        m.press(0)
        m.release()
        m.press(500)
        assertFalse(m.poll(1_200))
        assertTrue(m.poll(1_300))
    }

    @Test
    fun `slide held from a gamepad needs 1500 ms`() {
        val m = ConfirmHoldMachine(ConfirmGesture.Slide, 1_500, openedAtMs = 0)
        m.press(0)
        assertFalse(m.poll(1_000))
        m.release()
        m.press(2_000)
        assertTrue(m.poll(3_500))
    }

    @Test
    fun `guarded fires on a hold that starts inside the 3 s window`() {
        val m = ConfirmHoldMachine(ConfirmGesture.Guarded, 3_000, openedAtMs = 0)
        m.press(2_900)
        // Held past the window: the hold started in time, so it still fires.
        assertFalse(m.guardLapsed(3_500))
        assertFalse(m.poll(5_800))
        assertTrue(m.poll(5_900))
    }

    @Test
    fun `guarded lapses when no hold starts within 3 s`() {
        val m = ConfirmHoldMachine(ConfirmGesture.Guarded, 3_000, openedAtMs = 0)
        assertFalse(m.guardLapsed(3_000))
        assertTrue(m.guardLapsed(3_001))
        m.press(3_100)
        assertFalse(m.holding)
    }

    @Test
    fun `a disabled gesture ignores presses until enabled`() {
        val m = ConfirmHoldMachine(ConfirmGesture.Hold, 800, openedAtMs = 0)
        m.enabled = false
        m.press(0)
        assertFalse(m.poll(1_000))
        m.enabled = true
        m.press(1_000)
        assertTrue(m.poll(1_800))
    }

    @Test
    fun `the built-in confirm tiers come from the spec table`() {
        assertEquals(ConfirmGesture.Hold to 800, arm.confirm!!.gesture to arm.confirm!!.holdMs)
        assertEquals(ConfirmGesture.Hold to 1_500, takeoff.confirm!!.gesture to takeoff.confirm!!.holdMs)
        assertEquals(ConfirmGesture.Hold to 1_500, land.confirm!!.gesture to land.confirm!!.holdMs)
        assertEquals(ConfirmGesture.Guarded to 3_000, kill.confirm!!.gesture to kill.confirm!!.holdMs)
        assertEquals(null, testBuiltin("pause").confirm)
    }

    private fun TestScope.controller(held: Set<Int> = emptySet(), failing: List<PreflightCheck> = emptyList()) =
        ConfirmController(backgroundScope, MonotonicClock { testScheduler.currentTime }, { failing }, { it in held })

    private fun request(skill: com.altnautica.gcs.core.model.Skill, button: Int? = null, ready: Boolean = true) = ConfirmRequest(
        vehicleId = DeviceId("d"),
        skill = skill,
        policy = skill.confirm!!,
        args = button?.let { mapOf(SkillArgs.GAMEPAD_BUTTON to it) } ?: emptyMap(),
        gamepadButton = button,
        checklistReady = ready,
    )

    @Test
    fun `holding the gamepad button 800 ms confirms arm, releasing at 500 ms does not`() = runTest {
        val c = controller(held = setOf(GamepadButton.A))
        val first = async { c.confirm(request(arm, GamepadButton.A)) }
        runCurrent()
        advanceTimeBy(500)
        c.onGamepadButton(GamepadButton.A, pressed = false)
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(c.isOpen)
        c.cancel()
        assertEquals(ConfirmResult.Cancelled, first.await())

        val second = async { c.confirm(request(arm, GamepadButton.A)) }
        runCurrent()
        advanceTimeBy(820)
        runCurrent()
        assertEquals(ConfirmResult.Confirmed(), second.await())
    }

    @Test
    fun `enter holds a land sheet for 1500 ms`() = runTest {
        val c = controller()
        val result = async { c.confirm(request(land)) }
        runCurrent()
        c.onEnter(true)
        advanceTimeBy(1_400)
        runCurrent()
        assertTrue(c.isOpen)
        advanceTimeBy(120)
        runCurrent()
        assertEquals(ConfirmResult.Confirmed(), result.await())
    }

    @Test
    fun `a guarded kill sheet cancels itself after the 3 s window`() = runTest {
        val c = controller()
        val result = async { c.confirm(request(kill)) }
        runCurrent()
        assertEquals(3_000L, c.state.value!!.guardRemainingMs)
        assertEquals(GuardCountdown.Window(3.0f), c.state.value!!.guardCountdown())
        advanceTimeBy(3_100)
        runCurrent()
        assertEquals(ConfirmResult.Cancelled, result.await())
    }

    @Test
    fun `guarded kill fires only after a full 3 s hold started inside the window`() = runTest {
        val c = controller()
        val result = async { c.confirm(request(kill)) }
        runCurrent()
        advanceTimeBy(400)
        // First hold: released after 2 s, short of 3 s. Nothing fires.
        c.onTouchHold(true)
        advanceTimeBy(2_000)
        runCurrent()
        c.onTouchHold(false)
        advanceTimeBy(8)
        runCurrent()
        assertTrue("a 2 s hold must not fire Kill", c.isOpen)

        // Second hold, started inside the 3 s window: fires at 3 s, not before.
        c.onTouchHold(true)
        advanceTimeBy(2_980)
        runCurrent()
        assertTrue(c.isOpen)
        advanceTimeBy(40)
        runCurrent()
        assertEquals(ConfirmResult.Confirmed(), result.await())
    }

    @Test
    fun `takeoff confirms with the stepper altitude`() = runTest {
        val c = controller()
        val result = async { c.confirm(request(takeoff)) }
        runCurrent()
        assertEquals(com.altnautica.gcs.core.skills.builtin.TakeoffAltitude.DEFAULT_M, c.state.value!!.altitudeM)
        c.setAltitude(25f)
        c.onTouchHold(true)
        advanceTimeBy(1_520)
        runCurrent()
        assertEquals(ConfirmResult.Confirmed(mapOf(SkillArgs.ALTITUDE_M to 25f)), result.await())
    }

    @Test
    fun `an incomplete checklist needs the override before the gesture works`() = runTest {
        val failing = listOf(PreflightCheck(PreflightItem.Gps3d, passed = false, known = true))
        val c = controller(failing = failing)
        val result = async { c.confirm(request(arm, ready = false)) }
        runCurrent()
        assertEquals(failing, c.state.value!!.failingChecks)
        c.onEnter(true)
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue("gesture is locked", c.isOpen)
        c.onEnter(false)
        c.setOverride(true)
        c.onEnter(true)
        advanceTimeBy(820)
        runCurrent()
        assertEquals(ConfirmResult.Confirmed(checklistOverridden = true), result.await())
    }

    @Test
    fun `circle cancels an open sheet`() = runTest {
        val c = controller()
        val result = async { c.confirm(request(land, GamepadButton.L1)) }
        runCurrent()
        c.onGamepadButton(GamepadButton.B, pressed = true)
        assertEquals(ConfirmResult.Cancelled, result.await())
        assertFalse(c.isOpen)
    }

    @Test
    fun `d-pad up and down step the takeoff altitude by one metre`() = runTest {
        val c = controller()
        val result = async { c.confirm(request(takeoff, GamepadButton.L2)) }
        runCurrent()
        assertEquals(com.altnautica.gcs.core.skills.builtin.TakeoffAltitude.DEFAULT_M, c.state.value!!.altitudeM)
        c.onGamepadButton(GamepadButton.DPAD_UP, pressed = true)
        c.onGamepadButton(GamepadButton.DPAD_UP, pressed = false)
        c.onGamepadButton(GamepadButton.DPAD_UP, pressed = true)
        assertEquals(com.altnautica.gcs.core.skills.builtin.TakeoffAltitude.DEFAULT_M + 2f, c.state.value!!.altitudeM)
        c.onGamepadButton(GamepadButton.DPAD_DOWN, pressed = true)
        assertEquals(com.altnautica.gcs.core.skills.builtin.TakeoffAltitude.DEFAULT_M + 1f, c.state.value!!.altitudeM)
        c.onGamepadButton(GamepadButton.L2, pressed = true)
        advanceTimeBy(1_520)
        runCurrent()
        assertEquals(ConfirmResult.Confirmed(mapOf(SkillArgs.ALTITUDE_M to com.altnautica.gcs.core.skills.builtin.TakeoffAltitude.DEFAULT_M + 1f)), result.await())
    }

    @Test
    fun `d-pad leaves a sheet without an altitude alone`() = runTest {
        val c = controller()
        val result = async { c.confirm(request(land, GamepadButton.L1)) }
        runCurrent()
        c.onGamepadButton(GamepadButton.DPAD_UP, pressed = true)
        assertEquals(null, c.state.value!!.altitudeM)
        assertTrue(c.isOpen)
        c.cancel()
        assertEquals(ConfirmResult.Cancelled, result.await())
    }

    @Test
    fun `triangle toggles the checklist override only when checks fail`() = runTest {
        val failing = listOf(PreflightCheck(PreflightItem.Gps3d, passed = false, known = true))
        val c = controller(failing = failing)
        val result = async { c.confirm(request(arm, GamepadButton.A, ready = false)) }
        runCurrent()
        assertFalse(c.state.value!!.overrideChecklist)
        c.onGamepadButton(GamepadButton.Y, pressed = true)
        assertTrue(c.state.value!!.overrideChecklist)
        c.onGamepadButton(GamepadButton.Y, pressed = false)
        c.onGamepadButton(GamepadButton.Y, pressed = true)
        assertFalse(c.state.value!!.overrideChecklist)
        c.cancel()
        assertEquals(ConfirmResult.Cancelled, result.await())

        val clean = controller()
        val other = async { clean.confirm(request(land, GamepadButton.L1)) }
        runCurrent()
        clean.onGamepadButton(GamepadButton.Y, pressed = true)
        assertFalse(clean.state.value!!.overrideChecklist)
        clean.cancel()
        assertEquals(ConfirmResult.Cancelled, other.await())
    }
}
