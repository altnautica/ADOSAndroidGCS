package com.altnautica.gcs.core.link.vehicle

import com.altnautica.gcs.core.agent.telemetry.TelemetrySnapshotSource
import com.altnautica.gcs.core.data.vault.CredentialVault
import com.altnautica.gcs.core.link.mavlink.ElapsedClock
import com.altnautica.gcs.core.link.queue.QueuedCommandSink
import com.altnautica.gcs.core.link.queue.QueuedVehicle
import com.altnautica.gcs.core.model.CommandResult
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.FcVariant
import com.altnautica.gcs.core.model.Liveness
import com.altnautica.gcs.core.model.MavlinkByteTransport
import com.altnautica.gcs.core.model.NodeProfile
import com.altnautica.gcs.core.model.NodeSource
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.model.Reach
import com.altnautica.gcs.core.model.TransportProvider
import com.altnautica.gcs.core.model.VehicleCommand
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QueuedRoutingTest {

    private val id = DeviceId("drone-q")

    private class FakeSink : QueuedCommandSink {
        val calls = mutableListOf<Pair<DeviceId, VehicleCommand>>()
        override fun supports(reach: Reach) = reach == Reach.CloudQueue
        override suspend fun run(node: DeviceId, command: VehicleCommand): CommandResult {
            calls += node to command
            return CommandResult.Queued("receipt-1")
        }
    }

    /** Supports LAN only and never opens, so a LAN node stays a MAVLink vehicle waiting for its link. */
    private object LanOnly : TransportProvider {
        override fun supports(reach: Reach) = reach == Reach.Lan
        override suspend fun open(node: NodeSummary, reach: Reach): MavlinkByteTransport = error("offline")
    }

    private fun TestScope.repository(reaches: List<Reach>, sink: QueuedCommandSink): VehicleRepository {
        val source = object : NodeSource {
            override val nodes: Flow<List<NodeSummary>> = flowOf(
                listOf(NodeSummary(id, "Drone", NodeProfile.Drone, reaches, Liveness.Live, null, FcVariant.MavlinkArdupilot, null, null)),
            )
        }
        val vault = mockk<CredentialVault> { coEvery { mavlinkSigningKey(any()) } returns null }
        val clock = ElapsedClock { testScheduler.currentTime }
        val scope: CoroutineScope = backgroundScope
        return VehicleRepository(
            nodeSources = setOf(source),
            providers = setOf(LanOnly),
            snapshots = mockk<TelemetrySnapshotSource>(),
            queueSinks = setOf(sink),
            vault = vault,
            activity = LinkActivity(),
            replays = ReplayVehicles(clock, scope),
            clock = clock,
            scope = scope,
        )
    }

    @Test
    fun `queue-only node becomes a queued vehicle whose commands go to the sink`() = runTest {
        val sink = FakeSink()
        val vehicle = repository(listOf(Reach.CloudQueue), sink).vehicle(id).filterNotNull().first()
        assertTrue(vehicle is QueuedVehicle)
        assertEquals(Reach.CloudQueue, vehicle.reach.value)
        assertNull(vehicle.telemetry.attitude.value)
        assertNull(vehicle.telemetry.heartbeat.value)
        assertTrue(vehicle.control.availableModes.value.isEmpty())
        assertEquals(CommandResult.Queued("receipt-1"), vehicle.control.run(VehicleCommand.Arm()))
        assertEquals(listOf(id to VehicleCommand.Arm()), sink.calls)
    }

    @Test
    fun `a reach with a transport wins over the queue`() = runTest {
        val sink = FakeSink()
        val vehicle = repository(listOf(Reach.Lan, Reach.CloudQueue), sink).vehicle(id).filterNotNull().first()
        assertTrue(vehicle is MavlinkVehicle)
        assertTrue(sink.calls.isEmpty())
    }
}
