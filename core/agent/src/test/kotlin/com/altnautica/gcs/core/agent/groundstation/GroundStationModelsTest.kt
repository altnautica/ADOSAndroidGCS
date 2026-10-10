package com.altnautica.gcs.core.agent.groundstation

import com.altnautica.gcs.core.agent.http.AgentJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroundStationModelsTest {

    private val json = AgentJson.json

    @Test
    fun `status with missing optional fields and unknown extras decodes`() {
        val payload = """
            {
              "profile": "ground-station",
              "paired_drone": {"device_id": null},
              "link": {"channel": 149, "state": "connecting", "mcs_index": null},
              "gcs": {"clients": []},
              "network": {"ap_ssid": "ADOS-GS-40BB"},
              "system": {"cpu_pct": 12.5},
              "recording": false,
              "video": {"recording": false, "recording_filename": null},
              "role": {"current": "direct", "supported": ["direct", "relay", "receiver"]},
              "mesh": {},
              "relay_proxy": null
            }
        """.trimIndent()

        val status = json.decodeFromString(StationStatus.serializer(), payload)

        assertEquals("ground-station", status.profile)
        assertEquals(149, status.link.channel)
        assertNull(status.link.rssiDbm)
        assertNull(status.pairedDrone.deviceId)
        assertEquals("ADOS-GS-40BB", status.network.apSsid)
        assertNull(status.system.agentVersion)
        assertEquals("direct", status.role.current)
        assertNull(status.mesh.up)
    }

    @Test
    fun `stale mesh block with null readings decodes`() {
        val payload = """
            {"up": null, "peer_count": null, "selected_gateway": null, "stale": true}
        """.trimIndent()

        val mesh = json.decodeFromString(MeshSnapshot.serializer(), payload)

        assertNull(mesh.up)
        assertNull(mesh.peerCount)
    }

    @Test
    fun `camera switch with unobserved acknowledgement decodes accepted as null`() {
        val payload = """
            {"camera_id": "2", "sent": true, "accepted": null,
             "reason": "sent to the flight controller; its acknowledgement is not observed"}
        """.trimIndent()

        val response = json.decodeFromString(CameraSwitchResponse.serializer(), payload)

        assertEquals("2", response.cameraId)
        assertEquals(true, response.sent)
        assertNull(response.accepted)
    }

    @Test
    fun `wfb update body carries only the fields the agent accepts`() {
        assertEquals(
            """{"channel":149}""",
            json.encodeToString(WfbUpdate.serializer(), WfbUpdate(channel = 149)),
        )
        assertEquals(
            """{"channel":149,"force":true}""",
            json.encodeToString(WfbUpdate.serializer(), WfbUpdate(channel = 149, force = true)),
        )
    }

    @Test
    fun `wfb write result decodes the persist outcome`() {
        val result = json.decodeFromString(
            WfbWriteResult.serializer(),
            """{"channel": 149, "persisted": true}""",
        )

        assertEquals(149, result.channel)
        assertTrue(result.persisted)
        assertNull(result.persistError)
    }
}
