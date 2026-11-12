package com.altnautica.gcs.feature.node.groundstation

import com.altnautica.gcs.core.agent.AgentClient
import com.altnautica.gcs.core.agent.groundstation.GroundStationApi
import com.altnautica.gcs.core.agent.http.AgentError
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.link.node.NodeRepository
import com.altnautica.gcs.core.link.node.NodeVitalsRepository
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.FcVariant
import com.altnautica.gcs.core.model.Liveness
import com.altnautica.gcs.core.model.NodeProfile
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.model.Reach
import com.altnautica.gcs.feature.node.NodeTab
import com.altnautica.gcs.feature.node.common.NodeApis
import com.altnautica.gcs.feature.node.common.NodeClients
import com.altnautica.gcs.feature.node.common.WriteAction
import com.altnautica.gcs.feature.node.common.WriteLabel
import com.altnautica.gcs.feature.node.common.WriteState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GroundStationViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val station = NodeSummary(DeviceId("gs-1"), "gs-1", NodeProfile.GroundStation, listOf(Reach.Lan), Liveness.Live, null, FcVariant.Unknown, null, null)
    private val gsApi = mockk<GroundStationApi>()
    private val client = mockk<AgentClient>(relaxed = true) { every { groundStation } returns gsApi }

    /** Whether each join request carried `force`. */
    private val joinForces = mutableListOf<Boolean>()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        coEvery { gsApi.joinClientNetwork(any()) } answers {
            val forced = firstArg<JsonObject>()["force"] != null
            joinForces += forced
            if (forced) AgentResult.Ok(JsonObject(emptyMap())) else AgentResult.Err(AgentError.Armed(canForce = true))
        }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun vm() = GroundStationViewModel(
        "gs-1",
        mockk<NodeRepository> { every { node(any()) } returns flowOf(station) },
        mockk<NodeClients> { coEvery { clientFor(any()) } returns client },
        mockk<NodeApis>(relaxed = true),
        mockk<NodeVitalsRepository>(relaxed = true),
    )

    private fun network(apRunning: Boolean?) {
        val body = buildJsonObject {
            if (apRunning != null) {
                putJsonObject("ap") {
                    put("enabled", apRunning)
                    put("running", apRunning)
                    put("ssid", "station")
                }
            }
        }
        coEvery { client.requestJson("GET", "api/v1/ground-station/network", any(), any()) } returns AgentResult.Ok(body)
    }

    /** Runs [test] on a fresh view model and stops its poll even when an assertion fails. */
    private fun withStation(test: suspend (GroundStationViewModel) -> Unit) = runTest(dispatcher) {
        val vm = vm()
        try {
            test(vm)
        } finally {
            vm.show(null)
        }
    }

    @Test
    fun anArmedJoinOverrideWithTheAccessPointUpAsksTheAccessPointQuestionBeforeSending() {
        network(apRunning = true)
        withStation { vm ->
            vm.show(NodeTab.Network)

            vm.join("field", "secret12")
            assertEquals(WriteState.ForceRequired(WriteLabel(WriteAction.Join, "field")), vm.writeState.value)

            vm.writes.confirmForce()
            assertEquals(PendingJoin("field", "secret12"), vm.state.value.joinNeedsForce)
            assertEquals(WriteState.Idle, vm.writeState.value)
            assertEquals(listOf(false), joinForces)

            vm.join("field", "secret12", standDownAccessPoint = true)
            assertEquals(listOf(false, true), joinForces)
            assertEquals(WriteState.Done(WriteLabel(WriteAction.Join, "field")), vm.writeState.value)
        }
    }

    @Test
    fun anArmedJoinOverrideWithoutAnAccessPointRetriesAtOnce() {
        network(apRunning = null)
        withStation { vm ->
            vm.show(NodeTab.Network)

            vm.join("field", null)
            vm.writes.confirmForce()
            assertNull(vm.state.value.joinNeedsForce)
            assertEquals(listOf(false, true), joinForces)
        }
    }

    @Test
    fun retryReadsTheVisibleTabAgainAtOnce() {
        coEvery { client.requestJson("GET", "api/v1/ground-station/network", any(), any()) } returns
            AgentResult.Err(AgentError.Network(IOException("down")))
        withStation { vm ->
            vm.show(NodeTab.Network)
            assertTrue(vm.state.value.network is Load.Failed)
            coVerify(exactly = 1) { client.requestJson("GET", "api/v1/ground-station/network", any(), any()) }

            vm.retry()
            coVerify(exactly = 2) { client.requestJson("GET", "api/v1/ground-station/network", any(), any()) }
        }
    }
}
