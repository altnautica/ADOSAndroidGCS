package com.altnautica.gcs.feature.configure.wizard

import com.altnautica.gcs.core.agent.fcsetup.FcSetupApi
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
import com.altnautica.gcs.core.agent.fcsetup.SetupWizardState
import com.altnautica.gcs.core.agent.fcsetup.StepReply
import com.altnautica.gcs.core.agent.fcsetup.WizardReply
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.model.DeviceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** A wizard the agent lost on a restart reads interrupted, offers Continue, and resumes where it stopped. */
@OptIn(ExperimentalCoroutinesApi::class)
class WizardResumeTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun step(id: String, state: SetupStepState, actions: List<SetupAction> = emptyList()) =
        SetupStep("ground", id, "setup.$id.title", state = state, actions = actions)

    private val detect = step("detect", SetupStepState.Done).copy(result = JsonObject(mapOf("firmware" to JsonPrimitive("ardupilot"))))

    private val interrupted = SetupWizard(
        "ground",
        state = SetupWizardState.Interrupted,
        steps = listOf(detect, step("frame", SetupStepState.WaitingUser), step("accel", SetupStepState.Pending)),
    )

    private inner class Backend : SetupBackend {
        val calls = mutableListOf<String>()
        var wizard = interrupted
        var running: SetupRunning? = null

        override suspend fun overview(): AgentResult<FcSetupOverview> =
            AgentResult.Ok(FcSetupOverview(uid = "fc", running = running, wizards = listOf(wizard)))

        override fun stream(): Flow<FcSetupEvent> = flow { awaitCancellation() }

        override suspend fun resume(wizard: String): AgentResult<WizardReply> {
            calls += "resume $wizard"
            val apply = SetupAction("apply", "setup.action.apply", SetupActionStyle.Primary)
            this.wizard = interrupted.copy(
                state = SetupWizardState.Running,
                steps = listOf(detect, step("frame", SetupStepState.WaitingUser, listOf(apply)), step("accel", SetupStepState.Pending)),
            )
            running = SetupRunning("ground", "frame")
            return AgentResult.Ok(WizardReply(wizard = this.wizard))
        }

        override suspend fun start(wizard: String): AgentResult<WizardReply> {
            calls += "start $wizard"
            return AgentResult.Ok(WizardReply())
        }

        override suspend fun action(wizard: String, step: String, action: String, args: JsonObject?) = AgentResult.Ok(StepReply())

        override suspend fun runStep(wizard: String, step: String) = AgentResult.Ok(StepReply())

        override suspend fun abort(wizard: String) = AgentResult.Ok(WizardReply())

        override suspend fun revert(snapshot: String) = AgentResult.Ok(RevertReply())
    }

    @Test
    fun anInterruptedWizardOffersContinueAndShowsNoDeadEndStep() = runTest(dispatcher) {
        val vm = WizardViewModel(DeviceId("d"), WizardFocus(FcSetupApi.WIZARD_GROUND), { MutableStateFlow(Backend()) }, { key, _ -> key })
        val state = vm.state.value
        assertEquals(SetupWizardState.Interrupted, state.wizard!!.state)
        // The waiting frame step has no actions until the run resumes: the overview shows instead.
        assertNull(state.step)
        assertEquals(PrimaryKind.Resume, SetupOverview.primary(state.wizard!!.state))
    }

    @Test
    fun continueResumesAndLandsOnTheFrameStepWithItsApply() = runTest(dispatcher) {
        val backend = Backend()
        val vm = WizardViewModel(DeviceId("d"), null, { MutableStateFlow(backend) }, { key, _ -> key })
        vm.resume(FcSetupApi.WIZARD_GROUND)
        assertEquals(listOf("resume ground"), backend.calls)
        val state = vm.state.value
        assertEquals(SetupWizardState.Running, state.wizard!!.state)
        assertEquals("frame", state.step!!.step)
        assertEquals(listOf("apply"), state.step!!.actions.map { it.id })
        // The detected firmware survived the restart.
        assertEquals(JsonPrimitive("ardupilot"), state.wizard!!.steps.first().result!!["firmware"])
    }
}
