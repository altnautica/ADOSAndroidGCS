package com.altnautica.gcs.core.agent

import com.altnautica.gcs.core.agent.groundstation.GsEvents
import com.altnautica.gcs.core.agent.http.AgentEndpoint
import com.altnautica.gcs.core.agent.mavlink.AgentMavlinkTransport
import com.altnautica.gcs.core.agent.model.SnapshotBattery
import com.altnautica.gcs.core.agent.model.SnapshotGps
import com.altnautica.gcs.core.agent.model.TelemetrySnapshot
import com.altnautica.gcs.core.agent.telemetry.SnapshotMapping
import com.altnautica.gcs.core.model.FlightMode
import com.altnautica.gcs.core.model.GpsFix
import com.altnautica.gcs.core.model.VehicleCommand
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MiscAgentTest {

    @Test
    fun `host strings become agent roots`() {
        assertEquals("http://192.168.1.50:8080/", AgentEndpoint.forHost("192.168.1.50", null)!!.baseUrlString)
        assertEquals("http://192.168.1.50:9000/", AgentEndpoint.forHost("192.168.1.50:9000", null)!!.baseUrlString)
        assertEquals("http://node.local:8080/", AgentEndpoint.forHost(" node.local/ ", null)!!.baseUrlString)
        assertEquals("http://[fe80::1]:8080/", AgentEndpoint.forHost("fe80::1", null)!!.baseUrlString)
        assertEquals("https://example.invalid/", AgentEndpoint.forHost("https://example.invalid/x", null)!!.baseUrlString)
        assertNull(AgentEndpoint.forHost("  ", null))
    }

    @Test
    fun `mavlink websocket targets the proxy port on the same host`() {
        val e = AgentEndpoint.forHost("192.168.1.50:8080", "k")!!
        assertEquals("ws://192.168.1.50:8765/", AgentMavlinkTransport.wsUrl(e))
        assertEquals("ws://[fe80::1]:8765/", AgentMavlinkTransport.wsUrl(AgentEndpoint.forHost("fe80::1", null)!!))
    }

    @Test
    fun `keepalive frames are dropped`() {
        assertNull(GsEvents.parse("""{"kind":"keepalive"}"""))
        assertNull(GsEvents.parse("not json"))
        assertEquals(JsonPrimitive("uplink"), GsEvents.parse("""{"kind":"uplink","active":"eth0"}""")!!["kind"])
    }

    @Test
    fun `snapshot mapping keeps unknowns unknown`() {
        val s = TelemetrySnapshot(
            armed = true,
            mode = "LOITER",
            battery = SnapshotBattery(voltage = 16.2, remaining = -1),
            gps = SnapshotGps(fixType = 6, satellites = 18),
        )
        assertNull(SnapshotMapping.battery(s, 1)!!.remainingPct)
        assertEquals(GpsFix.RtkFixed, SnapshotMapping.gps(s, 1)!!.fix)
        assertEquals(FlightMode(SnapshotMapping.UNKNOWN_CUSTOM_MODE, "LOITER"), SnapshotMapping.heartbeat(s, 1)!!.mode)
        assertNull(SnapshotMapping.position(s, 1))
        assertNull(SnapshotMapping.heartbeat(s.copy(mavlinkAlive = false), 1))
    }

    @Test
    fun `commands map to agent verbs`() {
        assertEquals("takeoff" to listOf(JsonPrimitive(12f)), SnapshotMapping.commandOf(VehicleCommand.Takeoff(12f)))
        assertEquals("killSwitch", SnapshotMapping.commandOf(VehicleCommand.Kill)!!.first)
        assertEquals("mode" to listOf(JsonPrimitive("RTL")), SnapshotMapping.commandOf(VehicleCommand.SetMode(FlightMode(6, "RTL"))))
        assertNull(SnapshotMapping.commandOf(VehicleCommand.MotorTest(1, 10f, 1f)))
    }
}
