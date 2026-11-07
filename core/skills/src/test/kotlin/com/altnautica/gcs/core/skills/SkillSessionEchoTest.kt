package com.altnautica.gcs.core.skills

import com.altnautica.gcs.core.model.ArmRequirement
import com.altnautica.gcs.core.model.Binding
import com.altnautica.gcs.core.model.CommandResult
import com.altnautica.gcs.core.model.ConfirmPolicy
import com.altnautica.gcs.core.model.Skill
import com.altnautica.gcs.core.model.SkillCategory
import com.altnautica.gcs.core.model.SkillContext
import com.altnautica.gcs.core.model.SkillContributor
import com.altnautica.gcs.core.model.SkillSource
import com.altnautica.gcs.core.model.SkillState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Toggle echoes, their timeout, and a Stop queued while the skill's link is stale. */
@OptIn(ExperimentalCoroutinesApi::class)
class SkillSessionEchoTest {
    /** An extension toggle whose reported state the test drives, as the extension's echo would. */
    private class Toggle : Skill {
        override val id = "p:orbit"
        override val labelKey = "Orbit"
        override val icon = "x"
        override val category = SkillCategory.Behavior
        override val source = SkillSource.Extension("p")
        override val toggle = true
        override val confirm: ConfirmPolicy? = null
        override val armRequirement = ArmRequirement.Any
        override val cooldownMs = 0L
        override val defaultBinding: Binding? = null
        var reported = SkillState(enabled = true, active = false, reasonKey = null, cooldownEndsAtMs = null, charges = null)
        var stopResult: CommandResult = CommandResult.Accepted
        val calls = mutableListOf<String>()
        override fun state(ctx: SkillContext) = reported
        override suspend fun activate(ctx: SkillContext, args: Map<String, Any?>): CommandResult {
            calls += "activate"
            return CommandResult.Accepted
        }
        override suspend fun deactivate(ctx: SkillContext): CommandResult {
            calls += "deactivate"
            return stopResult
        }
    }

    private fun TestScope.session(skill: Toggle): Pair<SkillSession, FakeVehicle> {
        advanceTimeBy(10_000)
        val vehicle = FakeVehicle()
        vehicle.heartbeat(armed = true, tMs = testScheduler.currentTime)
        val clock = MonotonicClock { testScheduler.currentTime }
        val contributor = object : SkillContributor {
            override val skills: Flow<List<Skill>> = MutableStateFlow(listOf(skill))
        }
        val s = SkillSession(
            vehicle = MutableStateFlow(vehicle),
            checklistReady = MutableStateFlow(true),
            pilot = MutableStateFlow(null),
            pilotLink = MutableStateFlow(null),
            registry = SkillRegistry(setOf(contributor), testSpec),
            activator = SkillActivator(clock),
            clock = clock,
            scope = backgroundScope,
        )
        runCurrent()
        return s to vehicle
    }

    private fun TestScope.collect(s: SkillSession): MutableList<SkillNotice> {
        val notices = mutableListOf<SkillNotice>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { s.notices.collect { notices += it } }
        return notices
    }

    @Test
    fun `an echoed start clears the pending ring without a notice`() = runTest {
        val skill = Toggle()
        val (s, vehicle) = session(skill)
        val notices = collect(s)
        s.activate(skill.id, emptyMap()) { ConfirmResult.Confirmed() }
        runCurrent()
        assertTrue(s.states.value.getValue(skill.id).pending)

        skill.reported = skill.reported.copy(active = true)
        vehicle.heartbeat(armed = true, tMs = testScheduler.currentTime + 1_000)
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(s.states.value.getValue(skill.id).pending)
        assertTrue(s.states.value.getValue(skill.id).active)
        assertTrue(notices.isEmpty())
    }

    @Test
    fun `a start without its echo reverts after the timeout with a no-response notice`() = runTest {
        val skill = Toggle()
        val (s, vehicle) = session(skill)
        val notices = collect(s)
        s.activate(skill.id, emptyMap()) { ConfirmResult.Confirmed() }
        runCurrent()
        assertTrue(s.states.value.getValue(skill.id).pending)

        advanceTimeBy(SkillTiming.ECHO_TIMEOUT_MS - 1_000)
        vehicle.heartbeat(armed = true, tMs = testScheduler.currentTime)
        runCurrent()
        assertTrue("still waiting inside the timeout", s.states.value.getValue(skill.id).pending)

        advanceTimeBy(1_500)
        runCurrent()
        val state = s.states.value.getValue(skill.id)
        assertFalse(state.pending)
        assertFalse(state.active)
        val notice = notices.single()
        assertEquals(SkillNotice(skill.id, NoticeLevel.Warning, SkillReason.NO_RESPONSE, null, mapOf("skill" to "Orbit")), notice)
    }

    @Test
    fun `a stop pressed while stale is queued and sent once the link returns`() = runTest {
        val skill = Toggle()
        skill.reported = skill.reported.copy(active = true, stale = true)
        skill.stopResult = CommandResult.Timeout
        val (s, vehicle) = session(skill)
        val notices = collect(s)

        s.stop(skill.id)
        runCurrent()
        assertTrue(s.states.value.getValue(skill.id).stopQueued)
        assertEquals(SkillReason.STOP_QUEUED, notices.first().reasonKey)
        assertEquals(listOf("deactivate"), skill.calls)

        // The link returns: the queued stop goes out once, and the ring waits for the inactive echo.
        skill.reported = skill.reported.copy(stale = false)
        skill.stopResult = CommandResult.Accepted
        vehicle.heartbeat(armed = true, tMs = testScheduler.currentTime + 1_000)
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(listOf("deactivate", "deactivate"), skill.calls)
        val state = s.states.value.getValue(skill.id)
        assertFalse(state.stopQueued)
        assertTrue(state.pending)

        skill.reported = skill.reported.copy(active = false)
        advanceTimeBy(1_000)
        runCurrent()
        assertFalse(s.states.value.getValue(skill.id).pending)
    }
}
