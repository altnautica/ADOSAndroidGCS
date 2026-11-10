package com.altnautica.gcs.feature.cockpit

import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.agent.http.AgentError
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.agent.nav.AutonomyFeedApi
import com.altnautica.gcs.core.agent.node.LocalNodeApis
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.skills.SkillNotice
import com.altnautica.gcs.core.skills.hud.SpecText
import com.altnautica.gcs.core.skills.hud.refusalText
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
import org.junit.Before
import org.junit.Test

/** Abort during a launch reaches the pilot before the vehicle is armed. */
@OptIn(ExperimentalCoroutinesApi::class)
class LaunchAbortTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /**
     * A pilot in its launch `arm` step, disarmed on the ground. Every `action` it receives
     * lands in [actions] (by name) and is answered ok, or refused with [refuse] when set.
     */
    private class LaunchingPilot(private val scope: TestScope, private val refuse: String?) : AutonomyFeedApi {
        val actions = mutableListOf<String>()
        private val data = """{"owner":"pilot","armed":false,"landed":true,"sequence":{"step":"arm","after":"hold"}}"""

        override fun detections() = emptyFlow<String>()
        override fun navStream(outbound: Flow<String>) = flow<String> { awaitCancellation() }

        override fun pilotStream(outgoing: Flow<String>) = callbackFlow {
            trySend("""{"kind":"pilot","ts_ms":${scope.testScheduler.currentTime},"data":$data}""")
            val reader = launch {
                outgoing.collect { text ->
                    val o = Json.parseToJsonElement(text).jsonObject
                    if (o["op"]?.jsonPrimitive?.content != "action") return@collect
                    val name = o.getValue("name").jsonPrimitive.content
                    val id = o["id"]?.jsonPrimitive?.content
                    actions += name
                    val answer = if (refuse == null) "\"ok\":true" else "\"ok\":false,\"err\":\"$refuse\""
                    trySend("""{"kind":"action_result","name":"$name","id":"$id",$answer}""")
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
                override fun autonomy(id: DeviceId) = this@LaunchingPilot
            },
        )
    }

    private fun TestScope.launchingVm(pilot: LaunchingPilot): CockpitViewModel {
        val vehicle = FakeVehicle()
        vehicle.heartbeatAt(testScheduler.currentTime, armed = false)
        val vm = testCockpitVm(vehicle, pilot.apis)
        backgroundScope.launch { vm.flow.collect {} }
        advanceTimeBy(600)
        runCurrent()
        assertEquals("the launch is in its arm step", "arm", vm.flow.value.launch?.step?.wire)
        return vm
    }

    @Test
    fun `the chip's Abort in the launch arm step sends the pilot pause while disarmed`() = runTest(dispatcher) {
        val pilot = LaunchingPilot(this, refuse = null)
        val vm = launchingVm(pilot)

        vm.onChipButton(ChipButton.Abort)
        advanceTimeBy(100)
        runCurrent()

        assertEquals(listOf("pause"), pilot.actions)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `a refused Abort reads the spec's abort refusal with the pilot's reason`() = runTest(dispatcher) {
        val pilot = LaunchingPilot(this, refuse = "failsafe")
        val vm = launchingVm(pilot)
        val notices = mutableListOf<SkillNotice>()
        backgroundScope.launch { vm.notices.collect { notices += it } }
        runCurrent()

        vm.onChipButton(ChipButton.Abort)
        advanceTimeBy(100)
        runCurrent()

        val strings = testSpec.setupStrings
        val expected = strings.format("flow.launch.abort_refused", mapOf("reason" to SpecText(strings).refusalText("failsafe")))
        assertEquals(listOf("pause"), pilot.actions)
        assertEquals(listOf(expected), notices.map { it.message })
        vm.viewModelScope.cancel()
    }

    @Test
    fun `the banner's Abort sends the same pilot pause`() = runTest(dispatcher) {
        val pilot = LaunchingPilot(this, refuse = null)
        val vm = launchingVm(pilot)

        vm.abortLaunch()
        advanceTimeBy(100)
        runCurrent()

        assertEquals(listOf("pause"), pilot.actions)
        vm.viewModelScope.cancel()
    }
}
