package com.altnautica.gcs.feature.cockpit.confirm

import com.altnautica.gcs.core.model.ConfirmGesture
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.skills.ConfirmRequest
import com.altnautica.gcs.core.skills.ConfirmResult
import com.altnautica.gcs.core.skills.MonotonicClock
import com.altnautica.gcs.core.skills.builtin.ArmSkill
import com.altnautica.gcs.core.skills.builtin.KillSkill
import com.altnautica.gcs.core.skills.builtin.LandSkill
import com.altnautica.gcs.core.skills.builtin.SkillArgs
import com.altnautica.gcs.core.skills.builtin.TakeoffSkill
import com.altnautica.gcs.core.skills.input.GamepadButton
import com.altnautica.gcs.feature.cockpit.preflight.PreflightCheck
import com.altnautica.gcs.feature.cockpit.preflight.PreflightItem
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
        val m = ConfirmHoldMachine(ConfirmGesture.Guarded, 1_500, openedAtMs = 0)
        m.press(2_900)
        // Held past the window: the hold started in time, so it still fires.
        assertFalse(m.guardLapsed(3_500))
        assertTrue(m.poll(4_400))
    }

    @Test
    fun `guarded lapses when no hold starts within 3 s`() {
        val m = ConfirmHoldMachine(ConfirmGesture.Guarded, 1_500, openedAtMs = 0)
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
    fun `holding gamepad button 0 for 1500 ms confirms arm, releasing at 1000 ms does not`() = runTest {
        val c = controller(held = setOf(GamepadButton.A))
        val first = async { c.confirm(request(ArmSkill, GamepadButton.A)) }
        runCurrent()
        advanceTimeBy(1_000)
        c.onGamepadButton(GamepadButton.A, pressed = false)
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(c.isOpen)
        c.cancel()
        assertEquals(ConfirmResult.Cancelled, first.await())

        val second = async { c.confirm(request(ArmSkill, GamepadButton.A)) }
        runCurrent()
        advanceTimeBy(1_520)
        runCurrent()
        assertEquals(ConfirmResult.Confirmed(), second.await())
    }

    @Test
    fun `enter holds a land sheet for 800 ms`() = runTest {
        val c = controller()
        val result = async { c.confirm(request(LandSkill)) }
        runCurrent()
        c.onEnter(true)
        advanceTimeBy(820)
        runCurrent()
        assertEquals(ConfirmResult.Confirmed(), result.await())
    }

    @Test
    fun `a guarded kill sheet cancels itself after the 3 s window`() = runTest {
        val c = controller()
        val result = async { c.confirm(request(KillSkill)) }
        runCurrent()
        assertEquals(3_000L, c.state.value!!.guardRemainingMs)
        advanceTimeBy(3_100)
        runCurrent()
        assertEquals(ConfirmResult.Cancelled, result.await())
    }

    @Test
    fun `takeoff confirms with the stepper altitude`() = runTest {
        val c = controller()
        val result = async { c.confirm(request(TakeoffSkill)) }
        runCurrent()
        assertEquals(10f, c.state.value!!.altitudeM)
        c.setAltitude(25f)
        c.onTouchHold(true)
        advanceTimeBy(820)
        runCurrent()
        assertEquals(ConfirmResult.Confirmed(mapOf(SkillArgs.ALTITUDE_M to 25f)), result.await())
    }

    @Test
    fun `an incomplete checklist needs the override before the gesture works`() = runTest {
        val failing = listOf(PreflightCheck(PreflightItem.Gps3d, passed = false, known = true))
        val c = controller(failing = failing)
        val result = async { c.confirm(request(ArmSkill, ready = false)) }
        runCurrent()
        assertEquals(failing, c.state.value!!.failingChecks)
        c.onSlideCompleted()
        c.onEnter(true)
        advanceTimeBy(2_000)
        runCurrent()
        assertTrue("gesture is locked", c.isOpen)
        c.onEnter(false)
        c.setOverride(true)
        c.onSlideCompleted()
        assertEquals(ConfirmResult.Confirmed(checklistOverridden = true), result.await())
    }
}
