package com.altnautica.gcs.feature.cockpit

import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.agent.http.AgentError
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.agent.nav.AutonomyFeedApi
import com.altnautica.gcs.core.agent.node.LocalNodeApis
import com.altnautica.gcs.core.model.DeviceId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The flight-state chip's mission ETA comes from the agent's `ar` frame, which the agent
 * computes per leased camera and puts on the next waypoint only where it is in frame. The
 * overlay may follow another camera (a belly leg with no waypoint in frame) or none at all
 * (the video lost); the chip keeps its own lease on the forward camera, so the ETA stays.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MissionEtaLeaseTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /**
     * An agent flying a mission: every 200 ms the socket carries a pilot frame and, while its
     * lease draws `ar_route` or `ar_motion`, the `ar` frame narrowed to the leased camera the
     * way the nav hub does; the status route answers its `?view=` the same way. Only the
     * forward camera `main` has the next waypoint in frame.
     */
    private class MissionAgent(private val scope: TestScope) : AutonomyFeedApi {
        /** The socket's lease: the last `{"type":"view"}` frame, as `camera` to `layers`. */
        var lease: Pair<String, List<String>>? = null
        val polls = mutableListOf<String?>()

        private fun now() = scope.testScheduler.currentTime

        private fun ar(camera: String?, layers: List<String>): String? {
            if (camera == null || layers.none { it == "ar_route" || it == "ar_motion" }) return null
            val items = if (camera == MAIN) """[{"t":"wp","k":"wp:4","seq":4,"role":"next","x":0.5,"y":0.5,"dist_m":60,"eta_s":12}]""" else "[]"
            return """{"ts_ms":${now()},"cameras":{"$camera":{"frame_w":1280,"frame_h":720,"items":$items}},"mission":{"state":"active"}}"""
        }

        override fun detections() = emptyFlow<String>()

        override fun navStream(outbound: Flow<String>) = flow {
            scope.backgroundScope.launch {
                outbound.collect { text ->
                    val o = Json.parseToJsonElement(text).jsonObject
                    val camera = o["camera_id"]!!.jsonPrimitive.content
                    val layers = o["layers"]!!.jsonArray.map { it.jsonPrimitive.content }
                    // An empty camera or layer set releases the lease.
                    lease = if (camera.isEmpty() || layers.isEmpty()) null else camera to layers
                }
            }
            while (true) {
                emit("""{"kind":"pilot","ts_ms":${now()},"data":$PILOT}""")
                lease?.let { (camera, layers) -> ar(camera, layers) }?.let { emit("""{"kind":"ar","ts_ms":${now()},"data":$it}""") }
                delay(TICK_MS)
            }
        }

        override fun pilotStream(outgoing: Flow<String>) = flow {
            while (true) {
                emit("""{"kind":"pilot","ts_ms":${now()},"data":$PILOT}""")
                delay(TICK_MS)
            }
        }

        override suspend fun navStatus(view: String?): AgentResult<JsonObject>? {
            polls += view
            val camera = view?.substringBefore(':')
            val layers = view?.substringAfter(':')?.split(',').orEmpty()
            val ar = ar(camera, layers)?.let { """{"ts_ms":${now()},"age_ms":50,"data":$it}""" } ?: "null"
            return AgentResult.Ok(Json.parseToJsonElement("""{"ar":$ar}""") as JsonObject)
        }

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
                override fun autonomy(id: DeviceId) = this@MissionAgent
            },
        )
    }

    @Test
    fun `the chip keeps the mission ETA when the overlay moves to the belly camera or drops its lease`() = runTest(dispatcher) {
        val vehicle = FakeVehicle()
        vehicle.heartbeatAt(testScheduler.currentTime, armed = true, mode = "AUTO")
        val agent = MissionAgent(this)
        val vm = testCockpitVm(vehicle, agent.apis)
        val layers = vm.autonomy.layers

        // The overlay shows the forward camera: its own frames carry the ETA.
        var release = layers.attach(MAIN)
        advanceTimeBy(1_500)
        runCurrent()
        assertEquals("mission_active", vm.chip.value.ruleId)
        val withMain = vm.chip.value.primary.text
        assertTrue(withMain, "ETA" in withMain)

        // The overlay follows the belly leg, which has no waypoint in frame. Well past the
        // 2 s an `ar` frame stays fresh for the ETA, the chip still reads it.
        release()
        release = layers.attach(BELLY)
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals("the overlay's lease names the belly leg", BELLY, agent.lease?.first)
        assertEquals(withMain, vm.chip.value.primary.text)
        assertTrue("the chip leases the forward camera's route", "$MAIN:ar_route" in agent.polls)

        // No overlay at all (the video lost): the ETA stays.
        release()
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(null, agent.lease)
        assertEquals(withMain, vm.chip.value.primary.text)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `no ETA lease is polled while the overlay's own lease carries the forward route`() = runTest(dispatcher) {
        val vehicle = FakeVehicle()
        vehicle.heartbeatAt(testScheduler.currentTime, armed = true, mode = "AUTO")
        val agent = MissionAgent(this)
        val vm = testCockpitVm(vehicle, agent.apis)
        val release = vm.autonomy.layers.attach(MAIN)
        advanceTimeBy(4_000)
        runCurrent()
        assertTrue("ETA ${vm.chip.value.primary.text}", "ETA" in vm.chip.value.primary.text)
        assertEquals(emptyList<String?>(), agent.polls)
        release()
        vm.viewModelScope.cancel()
    }

    private companion object {
        const val MAIN = "main"
        const val BELLY = "belly"
        const val TICK_MS = 200L
        const val PILOT = """{"owner":"mission_fc","armed":true,"landed":false,"control_mode":"ados","mission":{"state":"active","seq":4,"total":6}}"""
    }
}
