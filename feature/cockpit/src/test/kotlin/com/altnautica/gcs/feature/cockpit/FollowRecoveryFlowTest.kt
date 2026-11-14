package com.altnautica.gcs.feature.cockpit

import com.altnautica.gcs.core.agent.http.AgentError
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.agent.nav.AutonomyControlApi
import com.altnautica.gcs.core.agent.nav.AutonomyFeedApi
import com.altnautica.gcs.core.agent.node.LocalNodeApis
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.feature.cockpit.autonomy.FollowPromptAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Follow-me's recovery through the cockpit: holding for the operator keeps the designation
 * (searching again seeks the same subject), the waiting prompt's actions reach follow-me's
 * config and the designation, and a stop clears it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FollowRecoveryFlowTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class Agent : AutonomyFeedApi, AutonomyControlApi {
        val nav = MutableSharedFlow<String>(extraBufferCapacity = 64)
        val writes = mutableListOf<Triple<String, String, JsonElement>>()

        override fun detections() = emptyFlow<String>()
        override fun navStream(outbound: Flow<String>): Flow<String> = nav
        override fun pilotStream(outgoing: Flow<String>): Flow<String> = emptyFlow()

        private val absent = AgentResult.Err(AgentError.FeatureAbsent)
        override suspend fun visionDemand(body: JsonObject) = absent
        override suspend fun visionStatus() = absent
        override suspend fun setDetector(modelId: String) = absent
        override suspend fun mapLive(since: Long) = absent
        override suspend fun designate(body: JsonObject): AgentResult<JsonElement> = AgentResult.Ok(buildJsonObject { put("designated", true) })

        override suspend fun putPluginConfig(pluginId: String, key: String, value: JsonElement): AgentResult<JsonElement> {
            writes += Triple(pluginId, key, value)
            return AgentResult.Ok(buildJsonObject { put("ok", true) })
        }

        val apis: Set<LocalNodeApis> = setOf(
            object : LocalNodeApis {
                override fun handles(id: DeviceId) = false
                override fun capture(id: DeviceId) = null
                override fun sensors(id: DeviceId) = null
                override fun capturePreview(id: DeviceId, camera: String?) = null
                override fun autonomy(id: DeviceId) = this@Agent
            },
        )
    }

    @Test
    fun `holding for the operator keeps the designation and the prompt's actions reach follow-me`() = runTest(dispatcher) {
        val agent = Agent()
        val vm = testCockpitVm(FakeVehicle(), agent.apis)
        val events = mutableListOf<CockpitEvent>()
        backgroundScope.launch { vm.events.collect { events += it } }
        fun follow(phase: String, extra: String = "") =
            agent.nav.tryEmit("""{"kind":"follow","ts_ms":1,"data":{"phase":"$phase","track_id":12,"operator_resume_seq":3$extra}}""")
        fun step() {
            advanceTimeBy(100)
            runCurrent()
        }
        step()
        follow("following")
        step()
        assertEquals(12, vm.autonomy.designation.value?.trackId)

        follow("searching", ""","occluder":{"ne":[1,1],"kind":"wide"}""")
        step()
        agent.nav.tryEmit("""{"kind":"follow_event","ts_ms":1,"topic":"follow.lost","data":{"track_id":12,"reason":"search_exhausted","awaiting_operator":true}}""")
        follow("holding-await-operator", ""","hold_reason":"search-exhausted"""")
        step()
        assertEquals("the waiting hold keeps the subject", 12, vm.autonomy.designation.value?.trackId)

        vm.followPrompt.run(FollowPromptAction.Resume, 3)
        vm.followPrompt.run(FollowPromptAction.Stop, 3)
        step()
        assertEquals(
            listOf("operator_resume_seq" to JsonPrimitive(4), "active" to JsonPrimitive(false)),
            agent.writes.map { it.second to it.third },
        )
        assertTrue(agent.writes.all { it.first == "com.altnautica.follow-me" })

        vm.followPrompt.run(FollowPromptAction.Redesignate, 3)
        step()
        assertNull("pick a target drops the designation", vm.autonomy.designation.value)
        assertTrue(CockpitEvent.PickTarget in events)

        follow("following")
        step()
        agent.nav.tryEmit("""{"kind":"follow_event","ts_ms":1,"topic":"follow.reacquired","data":{"track_id":12,"similarity":0.9,"gap_s":3.0}}""")
        step()
        assertTrue("the toast names what hid the subject: $events", events.any { it is CockpitEvent.Reacquired && it.occluder == "wide" })
        agent.nav.tryEmit("""{"kind":"follow_event","ts_ms":1,"topic":"follow.stopped","data":{"reason":"lease-revoked"}}""")
        step()
        assertNull("a stopped follow drops the designation", vm.autonomy.designation.value)
    }
}
