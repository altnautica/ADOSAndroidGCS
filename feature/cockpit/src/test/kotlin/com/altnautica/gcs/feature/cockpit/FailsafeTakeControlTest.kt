package com.altnautica.gcs.feature.cockpit

import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.agent.http.AgentError
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.agent.nav.AutonomyFeedApi
import com.altnautica.gcs.core.agent.node.LocalNodeApis
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.skills.SkillNotice
import com.altnautica.gcs.core.skills.SkillReason
import com.altnautica.gcs.feature.cockpit.chip.ChipButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Take control never cancels the flight controller's failsafe return, from the chip or the Mode sheet. */
@OptIn(ExperimentalCoroutinesApi::class)
class FailsafeTakeControlTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** A pilot whose FC flies RTL in the air, [failsafe] or not; every `action` lands in [actions] and is answered ok. */
    private class ReturningPilot(private val scope: TestScope, failsafe: Boolean) : AutonomyFeedApi {
        val actions = mutableListOf<String>()
        private val data = """{"owner":"fc_mode","control_mode":"ados","armed":true,"landed":false,""" +
            """"fc_mode":6,"fc_mode_name":"RTL","fc_failsafe":$failsafe}"""

        override fun detections() = emptyFlow<String>()
        override fun navStream(outbound: Flow<String>) = flow<String> { awaitCancellation() }

        override fun pilotStream(outgoing: Flow<String>) = callbackFlow {
            trySend("""{"kind":"pilot","ts_ms":${scope.testScheduler.currentTime},"data":$data}""")
            val reader = launch {
                outgoing.collect { text ->
                    val o = Json.parseToJsonElement(text).jsonObject
                    if (o["op"]?.jsonPrimitive?.content != "action") return@collect
                    val name = o.getValue("name").jsonPrimitive.content
                    actions += name
                    trySend("""{"kind":"action_result","name":"$name","id":"${o["id"]?.jsonPrimitive?.content}","ok":true}""")
                }
            }
            awaitClose { reader.cancel() }
        }

        override suspend fun navStatus(view: String?): AgentResult<JsonObject>? = null
        private val absent = AgentResult.Err(AgentError.FeatureAbsent)
        override suspend fun visionDemand(body: JsonObject) = absent
        override suspend fun visionStatus() = absent
        override suspend fun setDetector(modelId: String) = absent
        override suspend fun mapLive(since: Long) = absent

        val apis: Set<LocalNodeApis> = setOf(
            object : LocalNodeApis {
                override fun handles(id: DeviceId) = false
                override fun capture(id: DeviceId) = null
                override fun sensors(id: DeviceId) = null
                override fun capturePreview(id: DeviceId, camera: String?) = null
                override fun autonomy(id: DeviceId) = this@ReturningPilot
            },
        )
    }

    private fun TestScope.returningVm(pilot: ReturningPilot): CockpitViewModel {
        val vehicle = FakeVehicle()
        vehicle.heartbeatAt(testScheduler.currentTime, armed = true, mode = "RTL")
        val vm = testCockpitVm(vehicle, pilot.apis)
        // The Mode sheet's state is shared only while the screen collects it.
        backgroundScope.launch { vm.modeSheet.collect {} }
        advanceTimeBy(600)
        runCurrent()
        return vm
    }

    @Test
    fun `the chip's Take control during a failsafe sends nothing and reads the failsafe refusal`() = runTest(dispatcher) {
        val pilot = ReturningPilot(this, failsafe = true)
        val vm = returningVm(pilot)
        val notices = mutableListOf<SkillNotice>()
        backgroundScope.launch { vm.notices.collect { notices += it } }
        runCurrent()

        vm.onChipButton(ChipButton.TakeControl)
        advanceTimeBy(100)
        runCurrent()

        assertTrue(pilot.actions.isEmpty())
        assertEquals(listOf(SkillReason.FAILSAFE), notices.map { it.reasonKey })
        vm.viewModelScope.cancel()
    }

    @Test
    fun `the chip's Take control outside a failsafe reaches the pilot`() = runTest(dispatcher) {
        val pilot = ReturningPilot(this, failsafe = false)
        val vm = returningVm(pilot)

        vm.onChipButton(ChipButton.TakeControl)
        advanceTimeBy(100)
        runCurrent()

        assertEquals(listOf("take_control"), pilot.actions)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `the Mode sheet's Take control is disabled for the failsafe while it lasts`() = runTest(dispatcher) {
        val vm = returningVm(ReturningPilot(this, failsafe = true))
        vm.openModeSheet()
        runCurrent()
        assertEquals(SkillReason.FAILSAFE, vm.modeSheet.value!!.takeControlReasonKey)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `the Mode sheet offers Take control outside a failsafe`() = runTest(dispatcher) {
        val vm = returningVm(ReturningPilot(this, failsafe = false))
        vm.openModeSheet()
        runCurrent()
        assertNull(vm.modeSheet.value!!.takeControlReasonKey)
        vm.viewModelScope.cancel()
    }
}
