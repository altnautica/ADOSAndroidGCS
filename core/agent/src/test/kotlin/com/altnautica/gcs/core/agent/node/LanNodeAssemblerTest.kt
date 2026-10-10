package com.altnautica.gcs.core.agent.node

import com.altnautica.gcs.core.agent.discovery.DiscoveredAgent
import com.altnautica.gcs.core.agent.model.StatusFull
import com.altnautica.gcs.core.data.vault.NodeCredential
import com.altnautica.gcs.core.model.BlockReason
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.FcVariant
import com.altnautica.gcs.core.model.Liveness
import com.altnautica.gcs.core.model.NodeProfile
import com.altnautica.gcs.core.model.Reach
import org.junit.Assert.assertEquals
import org.junit.Test

class LanNodeAssemblerTest {

    private val now = 100_000L

    private fun cred(id: String, name: String, profile: String) =
        NodeCredential(deviceId = id, apiKey = "k-$id", name = name, lastIp = "192.168.1.50", lastPort = 8080, profile = profile)

    private fun discovered(id: String, paired: Boolean) = DiscoveredAgent(
        serviceName = "svc-$id", deviceId = id, name = "Found $id", version = "1.0", board = null,
        paired = paired, profile = "drone", role = null, code = null, host = "192.168.1.70", port = 8080, lastSeenMs = now,
    )

    @Test
    fun `liveness bands`() {
        assertEquals(Liveness.Live, LanNodeAssembler.liveness(now - 44_000, now))
        assertEquals(Liveness.Stale, LanNodeAssembler.liveness(now - 50_000, now))
        assertEquals(Liveness.Offline, LanNodeAssembler.liveness(now - 61_000, now))
        assertEquals(Liveness.Offline, LanNodeAssembler.liveness(null, now))
    }

    @Test
    fun `paired nodes, carried drone and unpaired discovery merge`() {
        val nodes = LanNodeAssembler.assemble(
            stored = listOf(cred("gs01", "Ground", "ground-station"), cred("d1", "Drone one", "drone")),
            discovered = listOf(discovered("d9", paired = false), discovered("d1", paired = true)),
            polls = mapOf(
                "gs01" to NodePoll(StatusFull(profile = "ground-station"), now - 1_000, carriedDroneId = "d2", agentVersion = "1.2.3"),
                "d1" to NodePoll(StatusFull(profile = "drone", fcFirmware = "px4"), now - 2_000, null, null),
            ),
            nowMs = now,
        ).associateBy { it.id.value }

        val gs = nodes.getValue("gs01")
        assertEquals(NodeProfile.GroundStation, gs.profile)
        assertEquals(listOf<Reach>(Reach.Lan), gs.reaches)
        assertEquals(DeviceId("d2"), gs.carriedDroneId)
        assertEquals("1.2.3", gs.agentVersion)

        assertEquals(FcVariant.MavlinkPx4, nodes.getValue("d1").fcVariant)
        assertEquals(listOf<Reach>(Reach.Lan), nodes.getValue("d1").reaches)

        val carried = nodes.getValue("d2")
        assertEquals(listOf<Reach>(Reach.ViaGround(DeviceId("gs01"))), carried.reaches)
        assertEquals(Liveness.Live, carried.liveness)

        val unpaired = nodes.getValue("d9")
        assertEquals(listOf<Reach>(Reach.Blocked(BlockReason.NotPaired)), unpaired.reaches)
        assertEquals(4, nodes.size)
    }

    @Test
    fun `carried drone that is also paired gains the relayed reach after lan`() {
        val nodes = LanNodeAssembler.assemble(
            stored = listOf(cred("gs01", "Ground", "ground-station"), cred("d2", "Drone two", "drone")),
            discovered = emptyList(),
            polls = mapOf("gs01" to NodePoll(StatusFull(profile = "ground-station"), now, "d2", null)),
            nowMs = now,
        ).associateBy { it.id.value }
        assertEquals(listOf(Reach.Lan, Reach.ViaGround(DeviceId("gs01"))), nodes.getValue("d2").reaches)
        assertEquals(Liveness.Offline, nodes.getValue("d2").liveness)
    }

    @Test
    fun `fc variant mapping`() {
        assertEquals(FcVariant.MavlinkArdupilot, LanNodeAssembler.fcVariant(StatusFull(fcFirmware = "ardupilot")))
        assertEquals(FcVariant.Msp, LanNodeAssembler.fcVariant(StatusFull(fcVariant = "betaflight")))
        assertEquals(FcVariant.Unknown, LanNodeAssembler.fcVariant(null))
    }
}
