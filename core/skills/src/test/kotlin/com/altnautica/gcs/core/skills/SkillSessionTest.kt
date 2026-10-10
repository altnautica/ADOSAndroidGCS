package com.altnautica.gcs.core.skills

import com.altnautica.gcs.core.model.Reach
import com.altnautica.gcs.core.model.Vehicle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SkillSessionTest {
    private val commandSkills = listOf("arm", "rth", "land", "takeoff", "kill", "abort", "mode.loiter")

    private fun TestScope.session(vehicle: Vehicle?): SkillSession {
        val clock = MonotonicClock { testScheduler.currentTime }
        return SkillSession(
            vehicle = MutableStateFlow(vehicle),
            checklistReady = MutableStateFlow(true),
            registry = SkillRegistry(emptySet()),
            activator = SkillActivator(clock),
            clock = clock,
            scope = backgroundScope,
        )
    }

    @Test
    fun `a 3_5 s old heartbeat disables every command skill with noFcLink`() = runTest {
        advanceTimeBy(10_000)
        val vehicle = FakeVehicle()
        vehicle.heartbeat(armed = true, tMs = testScheduler.currentTime - 3_500)
        val s = session(vehicle)
        runCurrent()
        for (id in commandSkills) {
            val state = s.states.value.getValue(id)
            assertFalse(id, state.enabled)
            assertEquals(id, SkillReason.NO_FC_LINK, state.reasonKey)
        }
    }

    @Test
    fun `link freshness flips the bar without any other telemetry change`() = runTest {
        advanceTimeBy(10_000)
        val vehicle = FakeVehicle()
        vehicle.heartbeat(armed = true, tMs = testScheduler.currentTime)
        val s = session(vehicle)
        runCurrent()
        assertTrue(s.states.value.getValue("rth").enabled)
        assertTrue(s.states.value.getValue("land").enabled)

        // No new heartbeat: within one 500 ms sample after the 3 s limit the bar goes stale.
        advanceTimeBy(3_600)
        runCurrent()
        assertFalse(s.states.value.getValue("rth").enabled)
        assertEquals(SkillReason.NO_FC_LINK, s.states.value.getValue("land").reasonKey)

        // A fresh heartbeat brings the skills back.
        vehicle.heartbeat(armed = true, tMs = testScheduler.currentTime)
        advanceTimeBy(600)
        runCurrent()
        assertTrue(s.states.value.getValue("rth").enabled)
        assertTrue(s.states.value.getValue("land").enabled)
    }

    @Test
    fun `disarmed fresh link enables arm and disables land`() = runTest {
        advanceTimeBy(10_000)
        val vehicle = FakeVehicle()
        vehicle.heartbeat(armed = false, tMs = testScheduler.currentTime)
        val s = session(vehicle)
        runCurrent()
        assertTrue(s.states.value.getValue("arm").enabled)
        assertEquals(SkillReason.NOT_ARMED, s.states.value.getValue("land").reasonKey)
        assertEquals(SkillReason.ALREADY_DISARMED, s.states.value.getValue("disarm").reasonKey)
    }

    @Test
    fun `phone radio reach is receive-only`() = runTest {
        advanceTimeBy(10_000)
        val vehicle = FakeVehicle()
        vehicle.heartbeat(armed = false, tMs = testScheduler.currentTime)
        val s = session(vehicle)
        runCurrent()
        vehicle.reach.value = Reach.PhoneRadio
        runCurrent()
        assertEquals(SkillReason.RECEIVE_ONLY, s.states.value.getValue("arm").reasonKey)
    }

    @Test
    fun `no vehicle disables commands and the bar hides argument-only skills`() = runTest {
        val s = session(null)
        runCurrent()
        assertEquals(SkillReason.NO_FC_LINK, s.states.value.getValue("arm").reasonKey)
        val bar = s.barSkills.value.map { it.id }
        assertFalse("set-mode" in bar)
        assertFalse("fly-here" in bar)
        assertTrue("kill" in bar)
        // Flight skills come before safety skills.
        assertTrue(bar.indexOf("takeoff") < bar.indexOf("kill"))
    }

    @Test
    fun `a rejected command is reported as a notice`() = runTest {
        advanceTimeBy(10_000)
        val vehicle = FakeVehicle()
        vehicle.heartbeat(armed = false, tMs = testScheduler.currentTime)
        vehicle.control.result = com.altnautica.gcs.core.model.CommandResult.Rejected(4, "PreArm: GPS")
        val s = session(vehicle)
        runCurrent()
        val notices = mutableListOf<SkillNotice>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { s.notices.collect { notices += it } }
        runCurrent()
        s.activate("arm", emptyMap()) { ConfirmResult.Confirmed() }
        runCurrent()
        assertEquals(SkillNotice("arm", NoticeLevel.Error, SkillReason.COMMAND_REJECTED, "PreArm: GPS"), notices.single())
        job.cancel()
    }
}
