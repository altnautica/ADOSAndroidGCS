package com.altnautica.gcs.core.link

import com.altnautica.gcs.core.link.direct.DirectEndpoint
import com.altnautica.gcs.core.link.manual.ManualControlInput
import com.altnautica.gcs.core.link.manual.MavlinkManualControlStreamer
import com.altnautica.gcs.core.link.vehicle.VehicleRepository
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.FcVariant
import com.altnautica.gcs.core.model.Liveness
import com.altnautica.gcs.core.model.NodeProfile
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.model.Reach
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkUnitsTest {

    @Test
    fun `direct endpoints parse from node ids`() {
        assertEquals(DirectEndpoint.Udp(14550), DirectEndpoint.parse("udp:14550"))
        assertEquals(DirectEndpoint.Tcp("192.168.1.50", 5760), DirectEndpoint.parse("tcp:192.168.1.50:5760"))
        assertEquals(DirectEndpoint.Tcp("fe80::1", 5760), DirectEndpoint.parse("tcp:fe80::1:5760"))
        assertNull(DirectEndpoint.parse("udp:99999"))
        assertNull(DirectEndpoint.parse("demo-copter"))
        assertEquals(DeviceId("tcp:sitl.local:5762"), DirectEndpoint.Tcp("sitl.local", 5762).nodeId)
    }

    @Test
    fun `manual control scales sticks and keeps throttle positive`() {
        val m = MavlinkManualControlStreamer.encode(1, ManualControlInput(pitch = 0.5f, roll = -1.5f, throttle = 0.25f, yaw = 1f, buttons = 0x1_0005))
        assertEquals(500, m.x)
        assertEquals(-1000, m.y)
        assertEquals(250, m.z)
        assertEquals(1000, m.r)
        assertEquals(0x0005, m.buttons)
        assertEquals(1, m.target)
    }

    @Test
    fun `node summaries from several sources merge reaches best-first`() {
        val id = DeviceId("drone-1")
        fun summary(reaches: List<Reach>, variant: FcVariant, liveness: Liveness) =
            NodeSummary(id, "Drone", NodeProfile.Drone, reaches, liveness, null, variant, null, null)
        val merged = VehicleRepository.mergeSummaries(
            listOf(
                summary(listOf(Reach.CloudQueue), FcVariant.Unknown, Liveness.Stale),
                summary(listOf(Reach.Lan), FcVariant.MavlinkArdupilot, Liveness.Live),
                summary(listOf(Reach.ViaGround(DeviceId("gs")), Reach.Lan), FcVariant.Unknown, Liveness.Offline),
            ),
        )!!
        assertEquals(listOf(Reach.Lan, Reach.ViaGround(DeviceId("gs")), Reach.CloudQueue), merged.reaches)
        assertEquals(FcVariant.MavlinkArdupilot, merged.fcVariant)
        assertEquals(Liveness.Live, merged.liveness)
    }
}
