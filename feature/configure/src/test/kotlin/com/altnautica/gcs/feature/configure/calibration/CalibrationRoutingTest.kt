package com.altnautica.gcs.feature.configure.calibration

import com.altnautica.gcs.core.agent.fcsetup.FcSetupEvent
import com.altnautica.gcs.core.agent.fcsetup.FcSetupOverview
import com.altnautica.gcs.core.agent.fcsetup.RevertReply
import com.altnautica.gcs.core.agent.fcsetup.SetupAction
import com.altnautica.gcs.core.agent.fcsetup.SetupActionStyle
import com.altnautica.gcs.core.agent.fcsetup.SetupBackend
import com.altnautica.gcs.core.agent.fcsetup.SetupConfirm
import com.altnautica.gcs.core.agent.fcsetup.SetupStep
import com.altnautica.gcs.core.agent.fcsetup.SetupStepState
import com.altnautica.gcs.core.agent.fcsetup.SetupWizard
import com.altnautica.gcs.core.agent.fcsetup.StepReply
import com.altnautica.gcs.core.agent.fcsetup.WizardReply
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.link.calibration.CalibrationKind
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.feature.configure.FakeCalibrationService
import com.altnautica.gcs.feature.configure.FakeParamService
import com.altnautica.gcs.feature.configure.FakeVehicle
import com.altnautica.gcs.feature.configure.common.ConfigureTarget
import com.altnautica.gcs.feature.configure.common.ConfigureTargetSource
import com.altnautica.gcs.feature.configure.wizard.SetupBackendSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Where a calibration runs: on an agent-connected vehicle it is the setup engine's step
 * (`POST .../ground/steps/{step}/run`, then step actions); on a direct link the app drives
 * the flight controller through its MAVLink calibration service.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CalibrationRoutingTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val clock = 50_000L
    private val vehicle = FakeVehicle(tMs = clock)
    private val cal = FakeCalibrationService()
    private val target = MutableStateFlow<ConfigureTarget?>(vehicle.target(FakeParamService(), cal))

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    /** Records every engine call; `accel` answers waiting for the first pose. */
    private class RecordingBackend : SetupBackend {
        val calls = mutableListOf<String>()
        private val accel = SetupStep(
            wizard = "ground",
            step = "accel",
            titleKey = "setup.accel.title",
            state = SetupStepState.WaitingUser,
            image = "accel_level",
            actions = listOf(SetupAction("next_pose", "setup.action.next_pose", SetupActionStyle.Primary, SetupConfirm.None)),
        )

        override suspend fun overview() = AgentResult.Ok(
            FcSetupOverview(uid = "fc", wizards = listOf(SetupWizard("ground", steps = listOf(accel.copy(state = SetupStepState.Done))))),
        )

        override fun stream(): Flow<FcSetupEvent> = flow { awaitCancellation() }

        override suspend fun start(wizard: String): AgentResult<WizardReply> {
            calls += "start $wizard"
            return AgentResult.Ok(WizardReply())
        }

        override suspend fun action(wizard: String, step: String, action: String, args: JsonObject?): AgentResult<StepReply> {
            calls += "action $wizard/$step/$action"
            return AgentResult.Ok(StepReply(step = accel.copy(state = SetupStepState.Running, actions = emptyList())))
        }

        override suspend fun runStep(wizard: String, step: String): AgentResult<StepReply> {
            calls += "run $wizard/$step"
            return AgentResult.Ok(StepReply(step = accel))
        }

        override suspend fun abort(wizard: String): AgentResult<WizardReply> {
            calls += "abort $wizard"
            return AgentResult.Ok(WizardReply())
        }

        override suspend fun revert(snapshot: String) = AgentResult.Ok(RevertReply())
    }

    private fun vm(backend: SetupBackend?) = CalibrationViewModel(
        DeviceId("d"),
        ConfigureTargetSource { target },
        { clock },
        flowOf(Unit),
        SetupBackendSource { flowOf(backend) },
    )

    @Test
    fun agentConnectedVehicleRunsTheEngineStep() = runTest(dispatcher) {
        val backend = RecordingBackend()
        val vm = vm(backend)
        assertTrue(vm.state.value.agent)

        vm.start(CalibrationKind.Accel)
        assertEquals(listOf("run ground/accel"), backend.calls)
        assertTrue("the MAVLink service is not used", cal.calls.isEmpty())
        assertEquals("accel", vm.state.value.agentStep?.step)
        assertTrue(vm.state.value.running)

        vm.agentAction("next_pose", null)
        assertEquals(listOf("run ground/accel", "action ground/accel/next_pose"), backend.calls)
        assertTrue(cal.calls.isEmpty())
    }

    @Test
    fun agentConnectedVehicleOffersOnlyCalibrationsTheEngineRuns() = runTest(dispatcher) {
        val backend = RecordingBackend()
        val vm = vm(backend)
        assertFalse(vm.state.value.offers(CalibrationKind.Gyro))
        assertTrue(vm.state.value.offers(CalibrationKind.Compass))
        vm.start(CalibrationKind.Gyro)
        assertTrue(backend.calls.isEmpty())
        assertTrue(cal.calls.isEmpty())
    }

    @Test
    fun directLinkDrivesTheMavlinkCalibrationService() = runTest(dispatcher) {
        val vm = vm(null)
        assertFalse(vm.state.value.agent)
        assertTrue(vm.state.value.offers(CalibrationKind.Gyro))

        vm.start(CalibrationKind.Accel)
        assertEquals(listOf("accel"), cal.calls)

        vm.agentAction("next_pose", null)
        assertEquals("no agent step to act on", listOf("accel"), cal.calls)
    }
}
