package com.altnautica.gcs.core.skills

import com.altnautica.gcs.core.model.ArmRequirement
import com.altnautica.gcs.core.model.CommandResult
import com.altnautica.gcs.core.model.ConfirmPolicy
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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
}
