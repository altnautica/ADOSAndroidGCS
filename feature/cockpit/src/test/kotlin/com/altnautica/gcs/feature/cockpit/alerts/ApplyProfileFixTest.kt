package com.altnautica.gcs.feature.cockpit.alerts

import com.altnautica.gcs.core.agent.fcsetup.FcSetupEvent
import com.altnautica.gcs.core.agent.fcsetup.FcSetupOverview
import com.altnautica.gcs.core.agent.fcsetup.RevertReply
import com.altnautica.gcs.core.agent.fcsetup.SetupAction
import com.altnautica.gcs.core.agent.fcsetup.SetupActionStyle
import com.altnautica.gcs.core.agent.fcsetup.SetupBackend
import com.altnautica.gcs.core.agent.fcsetup.SetupRunning
import com.altnautica.gcs.core.agent.fcsetup.SetupStep
import com.altnautica.gcs.core.agent.fcsetup.SetupStepState
import com.altnautica.gcs.core.agent.fcsetup.SetupWizard
import com.altnautica.gcs.core.agent.fcsetup.StepReply
import com.altnautica.gcs.core.agent.fcsetup.WizardReply
import com.altnautica.gcs.core.agent.http.AgentError
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.feature.cockpit.testSpec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ApplyProfileFixTest {

    private fun row(name: String, value: Double, have: Double?) = JsonObject(
        mapOf("name" to JsonPrimitive(name), "value" to JsonPrimitive(value), "have" to JsonPrimitive(have)),
    )

    private fun profileStep(state: SetupStepState = SetupStepState.WaitingUser, reboots: Boolean = true, blockers: List<String> = emptyList()) = SetupStep(
        wizard = "ground",
        step = "ados_profile",
        titleKey = "setup.ados_profile.title",
        args = JsonObject(
            mapOf(
                "params" to JsonArray(listOf(row("MAV_GCS_SYSID", 1.0, 255.0), row("FS_GCS_ENABLE", 1.0, 1.0), row("AVOID_MARGIN", 1.3, 1.2999999523))),
                "reboots" to JsonPrimitive(reboots),
            ),
        ),
        state = state,
        actions = listOf(SetupAction("apply", "setup.action.apply", SetupActionStyle.Primary)),
        blockers = blockers,
    )

    private class FakeBackend(var step: SetupStep) : SetupBackend {
        var runReply: AgentResult<StepReply>? = null
        var running: SetupRunning? = null
        val events = MutableSharedFlow<FcSetupEvent>(extraBufferCapacity = 8)
        val calls = mutableListOf<String>()
        override suspend fun overview(): AgentResult<FcSetupOverview> =
            AgentResult.Ok(FcSetupOverview(uid = "fc", running = running, wizards = listOf(SetupWizard("ground", steps = listOf(step)))))
        override fun stream(): Flow<FcSetupEvent> = events
        override suspend fun start(wizard: String): AgentResult<WizardReply> = error("unused")
        override suspend fun resume(wizard: String): AgentResult<WizardReply> = error("unused")
        override suspend fun action(wizard: String, step: String, action: String, args: JsonObject?): AgentResult<StepReply> {
            calls += "action:$action"
            return AgentResult.Ok(StepReply(step = this.step.copy(state = SetupStepState.Running)))
        }
        override suspend fun runStep(wizard: String, step: String): AgentResult<StepReply> {
            calls += "run:$step"
            return runReply ?: AgentResult.Ok(StepReply(step = this.step))
        }
        override suspend fun abort(wizard: String): AgentResult<WizardReply> {
            calls += "abort:$wizard"
            return AgentResult.Ok(WizardReply())
        }
        override suspend fun revert(snapshot: String): AgentResult<RevertReply> = error("unused")
    }

    private fun TestScope.fix(b: FakeBackend?) = ApplyProfileFix(this, testSpec.setupStrings) { b }

    @Test
    fun reviewListsOnlyTheChangedRowsAndTheRestart() = runTest {
        val b = FakeBackend(profileStep())
        val fix = fix(b)
        fix.read("fc_profile|x")
        runCurrent()
        val review = (fix.state.value.phase as ApplyPhase.Review).review
        assertEquals(listOf("MAV_GCS_SYSID"), review.changes.map { it.name })
        assertEquals("255 → 1", review.changes.single().let { "${ProfileReview.number(it.have!!)} → ${it.value}" })
        // A float read back with single-precision noise counts as already set.
        assertEquals(listOf("FS_GCS_ENABLE", "AVOID_MARGIN"), review.same.map { it.name })
        assertTrue(review.reboots)
        assertEquals("apply", review.actionId)
        assertEquals("fc_profile|x", fix.state.value.owner)
    }

    @Test
    fun holdWritesThenFollowsTheStepToDone() = runTest {
        val b = FakeBackend(profileStep())
        val fix = fix(b)
        fix.read("row")
        runCurrent()
        fix.write()
        runCurrent()
        assertEquals(ApplyPhase.Writing, fix.state.value.phase)
        b.events.emit(FcSetupEvent.Step(0, profileStep(state = SetupStepState.Done)))
        runCurrent()
        assertEquals(ApplyPhase.Done, fix.state.value.phase)
        assertEquals(listOf("run:ados_profile", "action:apply"), b.calls)
    }

    @Test
    fun cancelAbortsTheGroundWizard() = runTest {
        val b = FakeBackend(profileStep())
        val fix = fix(b)
        fix.read("row")
        runCurrent()
        fix.cancel()
        runCurrent()
        assertEquals(ApplyState(), fix.state.value)
        assertEquals("abort:ground", b.calls.last())
    }

    @Test
    fun leavingAnOpenReviewAbortsIt() = runTest {
        val b = FakeBackend(profileStep())
        val fix = fix(b)
        fix.read("row")
        runCurrent()
        fix.leave()
        runCurrent()
        assertEquals("abort:ground", b.calls.last())
    }

    @Test
    fun wizardRunningOnTheProfileStepResumesTheReview() = runTest {
        val b = FakeBackend(profileStep())
        b.runReply = AgentResult.Err(AgentError.Server(409, "wizard_running", "wizard_running"))
        b.running = SetupRunning("ground", "ados_profile")
        val fix = fix(b)
        fix.read("row")
        runCurrent()
        assertTrue(fix.state.value.phase is ApplyPhase.Review)
    }

    @Test
    fun wizardRunningElsewhereReadsItsSpecTextNotTheCode() = runTest {
        val b = FakeBackend(profileStep())
        b.runReply = AgentResult.Err(AgentError.Server(409, "wizard_running", "wizard_running"))
        b.running = SetupRunning("ground", "accel")
        val fix = fix(b)
        fix.read("row")
        runCurrent()
        assertEquals(ApplyPhase.Error("setup.err.wizard_running", ApplyFailure.Refused), fix.state.value.phase)
    }

    @Test
    fun refusalCodeOnlyTheBlockersWordIsMappedThere() = runTest {
        val b = FakeBackend(profileStep())
        b.runReply = AgentResult.Err(AgentError.Server(409, "setup_unavailable", "setup_unavailable"))
        val fix = fix(b)
        fix.read("row")
        runCurrent()
        assertEquals(ApplyPhase.Error("setup.blocker.setup_unavailable", ApplyFailure.Refused), fix.state.value.phase)
    }

    @Test
    fun unknownCodeFallsBackToLocalWords() = runTest {
        val b = FakeBackend(profileStep())
        b.runReply = AgentResult.Err(AgentError.Server(409, "no_such_code", "no_such_code"))
        val fix = fix(b)
        fix.read("row")
        runCurrent()
        assertEquals(ApplyPhase.Error(null, ApplyFailure.Refused), fix.state.value.phase)
    }

    @Test
    fun failedStepReadsItsBlocker() = runTest {
        val b = FakeBackend(profileStep(state = SetupStepState.Failed, blockers = listOf("setup.blocker.armed")))
        val fix = fix(b)
        fix.read("row")
        runCurrent()
        assertEquals(ApplyPhase.Error("setup.blocker.armed", ApplyFailure.Failed), fix.state.value.phase)
    }

    @Test
    fun noSetupEngineIsUnreachable() = runTest {
        val fix = fix(null)
        fix.read("row")
        runCurrent()
        assertEquals(ApplyPhase.Error(null, ApplyFailure.Unreachable), fix.state.value.phase)
    }
}
