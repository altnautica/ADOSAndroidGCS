package com.altnautica.gcs.core.skills

import com.altnautica.gcs.core.model.ArmRequirement
import com.altnautica.gcs.core.model.CommandResult
import com.altnautica.gcs.core.model.ConfirmPolicy
import com.altnautica.gcs.core.model.ControlMode
import com.altnautica.gcs.core.model.PilotProfile
import com.altnautica.gcs.core.model.PilotState
import com.altnautica.gcs.core.model.Skill
import com.altnautica.gcs.core.model.SkillCategory
import com.altnautica.gcs.core.model.SkillContext
import com.altnautica.gcs.core.model.SkillContributor
import com.altnautica.gcs.core.model.SkillSource
import com.altnautica.gcs.core.model.SkillState
import com.altnautica.gcs.core.skills.builtin.IDLE_STATE
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SkillSessionPilotFramesTest {
    /** A skill that counts how often its state is computed. */
    private class Counting : Skill {
        var calls = 0
        override val id = "counting"
        override val labelKey = "counting"
        override val icon = "counting"
        override val category = SkillCategory.Utility
        override val source = SkillSource.Builtin
        override val toggle = false
        override val confirm: ConfirmPolicy? = null
        override val armRequirement = ArmRequirement.Any
        override val cooldownMs = 0L
        override val defaultBinding = null
        override fun state(ctx: SkillContext): SkillState = IDLE_STATE.also { calls++ }
        override suspend fun activate(ctx: SkillContext, args: Map<String, Any?>) = CommandResult.Accepted
    }

    // Fails on the base commit: every pilot frame (a new receive time) re-merged every skill.
    @Test
    fun `pilot frames that change no field a state reads re-merge no skill`() = runTest(UnconfinedTestDispatcher()) {
        val clock = MonotonicClock { testScheduler.currentTime }
        val skill = Counting()
        val pilot = MutableStateFlow<PilotState?>(adosPilot())
        SkillSession(
            vehicle = MutableStateFlow(FakeVehicle()),
            checklistReady = MutableStateFlow(true),
            pilot = pilot,
            pilotLink = MutableStateFlow(null),
            registry = SkillRegistry(setOf(object : SkillContributor { override val skills = flowOf(listOf<Skill>(skill)) }), testSpec),
            activator = SkillActivator(clock),
            clock = clock,
            scope = backgroundScope,
        )
        runCurrent()
        val before = skill.calls
        assertTrue(before > 0)
        // Ten frames inside one ticker period, differing only in receive time and input age.
        repeat(10) { i -> pilot.value = adosPilot().copy(receivedAtMs = i + 1L, inputAgeMs = i.toLong()) }
        runCurrent()
        assertEquals(before, skill.calls)
        // A field a state reads does re-merge.
        pilot.value = adosPilot().copy(profile = PilotProfile.entries.first { it != PilotProfile.Normal }, receivedAtMs = 20L)
        runCurrent()
        assertEquals(before + 1, skill.calls)
    }

    /** A session over the built-ins with a connected, fresh pilot channel. */
    private fun TestScope.session(vehicle: FakeVehicle, pilot: MutableStateFlow<PilotState?>): SkillSession {
        val clock = MonotonicClock { testScheduler.currentTime }
        return SkillSession(
            vehicle = MutableStateFlow(vehicle),
            checklistReady = MutableStateFlow(true),
            pilot = pilot,
            pilotLink = MutableStateFlow(FakePilotLink(connected = true)),
            registry = SkillRegistry(emptySet(), testSpec),
            activator = SkillActivator(clock),
            clock = clock,
            scope = backgroundScope,
        )
    }

    @Test
    fun `a frame changing only the control mode re-derives the mode skill`() = runTest(UnconfinedTestDispatcher()) {
        // No FC heartbeat: only the pilot channel can carry a mode change.
        val pilot = MutableStateFlow<PilotState?>(adosPilot())
        val s = session(FakeVehicle(), pilot)
        runCurrent()
        assertTrue(s.states.value.getValue("set-mode").enabled)
        pilot.value = adosPilot().copy(controlMode = ControlMode.FcManual, receivedAtMs = 1L)
        runCurrent()
        assertEquals(SkillReason.NO_FC_LINK, s.states.value.getValue("set-mode").reasonKey)
    }

    @Test
    fun `a frame changing only the fc failsafe re-derives pause`() = runTest(UnconfinedTestDispatcher()) {
        advanceTimeBy(10_000)
        val vehicle = FakeVehicle().apply { heartbeat(armed = true, tMs = testScheduler.currentTime) }
        val pilot = MutableStateFlow<PilotState?>(adosPilot())
        val s = session(vehicle, pilot)
        runCurrent()
        assertTrue(s.states.value.getValue("pause").enabled)
        pilot.value = adosPilot().copy(fcFailsafe = true, receivedAtMs = 1L)
        runCurrent()
        assertFalse(s.states.value.getValue("pause").enabled)
        assertEquals(SkillReason.FAILSAFE, s.states.value.getValue("pause").reasonKey)
    }

    @Test
    fun `a frame changing only landed re-derives disarm`() = runTest(UnconfinedTestDispatcher()) {
        advanceTimeBy(10_000)
        val vehicle = FakeVehicle().apply { heartbeat(armed = true, tMs = testScheduler.currentTime) }
        val pilot = MutableStateFlow<PilotState?>(adosPilot())
        val s = session(vehicle, pilot)
        runCurrent()
        assertEquals(SkillReason.NOT_LANDED, s.states.value.getValue("disarm").reasonKey)
        pilot.value = adosPilot().copy(landed = true, receivedAtMs = 1L)
        runCurrent()
        assertTrue(s.states.value.getValue("disarm").enabled)
        assertEquals(null, s.states.value.getValue("disarm").reasonKey)
    }
}
