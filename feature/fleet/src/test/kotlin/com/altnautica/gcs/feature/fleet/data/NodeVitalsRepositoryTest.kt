package com.altnautica.gcs.feature.fleet.data

import com.altnautica.gcs.core.agent.AgentClientFactory
import com.altnautica.gcs.core.agent.groundstation.PairedDrone
import com.altnautica.gcs.core.agent.model.SnapshotBattery
import com.altnautica.gcs.core.agent.model.StatusFull
import com.altnautica.gcs.core.agent.model.TelemetrySnapshot
import com.altnautica.gcs.core.agent.node.AgentStatusRepository
import com.altnautica.gcs.core.agent.node.NodePoll
import com.altnautica.gcs.core.link.vehicle.VehicleRepository
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.FcVariant
import com.altnautica.gcs.core.model.Liveness
import com.altnautica.gcs.core.model.NodeProfile
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.model.Reach
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Fleet cards read agent nodes from the status poll alone; no vehicle link is
 * opened or read, and only a value the agent does not know shows as unknown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NodeVitalsRepositoryTest {

    private val polls = HashMap<String, NodePoll>()
    private val status = mockk<AgentStatusRepository> {
        // mockk hands an inline DeviceId over unboxed.
        every { observe(any()) } answers { flowOf(polls.getValue(firstArg<Any>().let { (it as? DeviceId)?.value ?: it as String })) }
    }
    private val vehicles = mockk<VehicleRepository> {
        every { openVehicles } returns MutableStateFlow(emptyMap())
    }
    private var now = 100_000L
    private val repo = NodeVitalsRepository(status, vehicles, mockk<AgentClientFactory>()).also { it.clock = { now } }

    private fun node(id: String, vararg reaches: Reach) =
        NodeSummary(DeviceId(id), id, NodeProfile.Drone, reaches.toList(), Liveness.Live, null, FcVariant.Unknown, null, null)

    private fun poll(telemetry: TelemetrySnapshot? = null, carried: PairedDrone? = null, lastOkMs: Long? = now - 1_000) =
        NodePoll(StatusFull(profile = "drone", telemetry = telemetry), lastOkMs, carried?.deviceId, null, carried)

    private fun vitals(n: NodeSummary) = runBlocking { repo.vitals(n).first() }

    @Test
    fun `last good status is not shown as current once the poll goes stale`() = runTest {
        val auto = TelemetrySnapshot(armed = true, mode = "AUTO", battery = SnapshotBattery(remaining = 76))
        polls["d1"] = poll(auto, lastOkMs = now - NodeVitalsRepository.MAX_POLL_AGE_MS)
        assertEquals(NodeVitals.UNKNOWN, repo.vitals(node("d1", Reach.Lan)).first())

        polls["d1"] = poll(auto, lastOkMs = null)
        assertEquals(NodeVitals.UNKNOWN, repo.vitals(node("d1", Reach.Lan)).first())
    }

    @Test
    fun `a poller that goes quiet turns unknown when the last success ages out`() = runTest {
        polls["d1"] = poll(TelemetrySnapshot(mode = "AUTO"), lastOkMs = now - 2_000)
        val seen = mutableListOf<NodeVitals>()
        val job = launch { repo.vitals(node("d1", Reach.Lan)).toList(seen) }
        runCurrent()
        assertEquals(listOf(NodeVitals(null, "AUTO", null)), seen)
        advanceTimeBy(NodeVitalsRepository.MAX_POLL_AGE_MS - 2_000 - 1)
        assertEquals(1, seen.size)
        advanceTimeBy(2)
        assertEquals(listOf(NodeVitals(null, "AUTO", null), NodeVitals.UNKNOWN), seen)
        job.cancel()
    }

    @Test
    fun `lan drone reads mode, arm state and battery from the status poll`() {
        polls["d1"] = poll(TelemetrySnapshot(armed = true, mode = "AUTO", battery = SnapshotBattery(remaining = 76)))
        assertEquals(NodeVitals(76, "AUTO", true), vitals(node("d1", Reach.Lan)))
        verify(exactly = 0) { vehicles.openVehicles }
    }

    @Test
    fun `battery the flight controller does not know stays unknown, mode still shows`() {
        polls["d1"] = poll(TelemetrySnapshot(armed = false, mode = "MANUAL", battery = SnapshotBattery(voltage = 0.0, remaining = null)))
        assertEquals(NodeVitals(null, "MANUAL", false), vitals(node("d1", Reach.Lan)))
        polls["d1"] = poll(TelemetrySnapshot(armed = false, mode = "MANUAL", battery = SnapshotBattery(remaining = -1)))
        assertEquals(NodeVitals(null, "MANUAL", false), vitals(node("d1", Reach.Lan)))
    }

    @Test
    fun `drone reached only through its ground station reads the station's summary`() {
        polls["gs01"] = poll(carried = PairedDrone(deviceId = "d2", fcMode = "RTL", batteryPct = 64.4f))
        assertEquals(NodeVitals(64, "RTL", null), vitals(node("d2", Reach.ViaGround(DeviceId("gs01")))))
        verify(exactly = 0) { vehicles.openVehicles }
    }

    @Test
    fun `station summary for another drone or an unknown battery is not borrowed`() {
        polls["gs01"] = poll(carried = PairedDrone(deviceId = "other", fcMode = "RTL", batteryPct = 50f))
        assertEquals(NodeVitals.UNKNOWN, vitals(node("d2", Reach.ViaGround(DeviceId("gs01")))))
        polls["gs01"] = poll(carried = PairedDrone(deviceId = "d2", fcMode = "LOITER", batteryPct = -1f))
        assertEquals(NodeVitals(null, "LOITER", null), vitals(node("d2", Reach.ViaGround(DeviceId("gs01")))))
    }

    @Test
    fun `direct link with nothing open is unknown and opens nothing`() {
        assertEquals(NodeVitals.UNKNOWN, vitals(node("usb", Reach.DirectFc)))
        verify(exactly = 0) { status.observe(any()) }
    }
}
