package com.altnautica.gcs.feature.cockpit

import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.agent.http.AgentError
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.agent.nav.AutonomyFeedApi
import com.altnautica.gcs.core.agent.node.LocalNodeApis
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.feature.cockpit.flow.StallAction
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
import java.io.File

/** The stall overlay's buttons reach the pilot as named actions on the pilot channel. */
@OptIn(ExperimentalCoroutinesApi::class)
class MissionStallActionTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** A pilot sending the agent's golden stall frame; every `action` it receives lands in [actions] and is accepted. */
    private class StalledPilot(private val scope: TestScope) : AutonomyFeedApi {
        val actions = mutableListOf<String>()
        private val data = File("../../core/agent/src/test/resources/fixtures/pilot-frame-stall.json").readText()

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
                override fun autonomy(id: DeviceId) = this@StalledPilot
            },
        )
    }

    @Test
    fun `skip, return and land from the stall overlay go to the pilot by name`() = runTest(dispatcher) {
        val pilot = StalledPilot(this)
        val vehicle = FakeVehicle()
        vehicle.heartbeatAt(testScheduler.currentTime, armed = true)
        val vm = testCockpitVm(vehicle, pilot.apis)
        backgroundScope.launch { vm.flow.collect {} }
        advanceTimeBy(600)
        runCurrent()
        val stall = checkNotNull(vm.flow.value.stall) { "the golden stall frame raises the overlay" }
        assertEquals(listOf(StallAction.Skip, StallAction.Return, StallAction.Land), stall.actions)

        for (a in stall.actions) {
            vm.onStallAction(a)
            advanceTimeBy(100)
            runCurrent()
        }

        assertEquals(listOf("mission_skip", "rth", "land"), pilot.actions)
        vm.viewModelScope.cancel()
    }
}
