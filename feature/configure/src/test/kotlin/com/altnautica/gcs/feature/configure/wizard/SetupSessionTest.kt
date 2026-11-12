package com.altnautica.gcs.feature.configure.wizard

import com.altnautica.gcs.core.agent.fcsetup.FcSetupEvent
import com.altnautica.gcs.core.agent.fcsetup.FcSetupOverview
import com.altnautica.gcs.core.agent.fcsetup.RevertReply
import com.altnautica.gcs.core.agent.fcsetup.SetupBackend
import com.altnautica.gcs.core.agent.fcsetup.SetupStep
import com.altnautica.gcs.core.agent.fcsetup.SetupStepState
import com.altnautica.gcs.core.agent.fcsetup.SetupWizard
import com.altnautica.gcs.core.agent.fcsetup.StepReply
import com.altnautica.gcs.core.agent.fcsetup.WizardReply
import com.altnautica.gcs.core.agent.http.AgentResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SetupSessionTest {
    private val outputs = SetupStep(wizard = "ground", step = "outputs", titleKey = "setup.outputs.title", state = SetupStepState.Done)

    /** Records calls; `runStep` waits on [stepGate]; the first stream sends a step, then drops. */
    private class Backend(private val waiting: SetupStep) : SetupBackend {
        val calls = mutableListOf<String>()
        var stepGate: CompletableDeferred<Unit>? = null
        var dropFirstStream = false
        private var streams = 0

        override suspend fun overview() = AgentResult.Ok(
            FcSetupOverview(uid = "fc", wizards = listOf(SetupWizard("ground", steps = listOf(waiting.copy(state = SetupStepState.Done))))),
        )

        override fun stream(): Flow<FcSetupEvent> = flow {
            if (dropFirstStream && streams++ == 0) {
                emit(FcSetupEvent.Step(1, waiting))
                throw java.io.IOException("link dropped")
            }
            awaitCancellation()
        }

        override suspend fun start(wizard: String): AgentResult<WizardReply> {
            calls += "start"
            return AgentResult.Ok(WizardReply())
        }

        override suspend fun action(wizard: String, step: String, action: String, args: JsonObject?): AgentResult<StepReply> {
            calls += "action $action"
            return AgentResult.Ok(StepReply())
        }

        override suspend fun runStep(wizard: String, step: String): AgentResult<StepReply> {
            calls += "run $step"
            stepGate?.await()
            return AgentResult.Ok(StepReply())
        }

        override suspend fun abort(wizard: String): AgentResult<WizardReply> {
            calls += "abort"
            return AgentResult.Ok(WizardReply())
        }

        override suspend fun revert(snapshot: String) = AgentResult.Ok(RevertReply())
    }

    @Test
    fun abortGoesOutWhileAnotherRequestIsInFlight() = runTest {
        val backend = Backend(outputs.copy(state = SetupStepState.WaitingUser))
        val session = SetupSession(backend, backgroundScope)
        session.open()
        runCurrent()

        backend.stepGate = CompletableDeferred()
        session.runStep("ground", "outputs")
        runCurrent()
        assertTrue(session.state.value.busy)

        session.abort("ground")
        runCurrent()
        assertEquals(listOf("run outputs", "abort"), backend.calls)

        // Other requests still wait their turn.
        session.action("ground", "outputs", "next")
        runCurrent()
        assertEquals(listOf("run outputs", "abort"), backend.calls)
    }

    @Test
    fun aDroppedStreamMarksStepsStaleUntilTheNextOverview() = runTest {
        val waiting = outputs.copy(state = SetupStepState.WaitingUser)
        val backend = Backend(waiting).apply { dropFirstStream = true }
        val session = SetupSession(backend, backgroundScope)
        session.open()
        runCurrent()

        val s = session.state.value
        assertEquals(SetupStepState.WaitingUser, s.step("ground", "outputs")?.state)
        assertTrue("the last step update is not live after the drop", s.stale)
        assertTrue(s.actionsBlocked)

        session.action("ground", "outputs", "hold_props_off")
        runCurrent()
        assertTrue("a stale step action is not sent", backend.calls.isEmpty())

        advanceTimeBy(2_001)
        runCurrent()
        assertFalse(session.state.value.stale)
        assertEquals(SetupStepState.Done, session.state.value.step("ground", "outputs")?.state)
    }
}
