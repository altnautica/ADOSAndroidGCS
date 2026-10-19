package com.altnautica.gcs.core.agent.sensors

import com.altnautica.gcs.core.agent.capture.AgentCaptureApi
import com.altnautica.gcs.core.agent.capture.CaptureExposure
import com.altnautica.gcs.core.agent.capture.CaptureStorageLeft
import com.altnautica.gcs.core.agent.http.AgentJson
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class SensorsApiTest {

    @Test
    fun storageRemainingAcceptsIntegerAndDecimal() {
        fun dec(v: String) = AgentJson.json.decodeFromString(CaptureStorageLeft.serializer(), """{"est_remaining_s":$v}""").estRemainingS
        assertEquals(14400.0, dec("14400")!!, 0.0)
        assertEquals(14400.0, dec("14400.0")!!, 0.0)
    }

    @Test
    fun unlockSendsLockedFalse() {
        val body = AgentJson.json.encodeToString(CaptureExposure.serializer(), CaptureExposure(locked = false))
        assertTrue(body, body.contains("\"locked\":false"))
    }

    @Test
    fun loraNodeIdsCoverTheU32Range() {
        val beacon = AgentJson.json.decodeFromString(LoraBeacon.serializer(), """{"node_id":4294967295}""")
        assertEquals(4294967295L, beacon.nodeId)
        val status = AgentJson.json.decodeFromString(LoraStatus.serializer(), """{"present":true,"node_id":4294967295}""")
        assertEquals(4294967295L, status.nodeId)
    }

    @Test
    fun regionModuleSupportIsUnknownWhenNull() {
        val plan = AgentJson.json.decodeFromString(LoraRegionPlan.serializer(), """{"code":"IN","supported_by_module":null}""")
        assertNull(plan.supportedByModule)
    }

    @Test
    fun followCountryIsAnExplicitNullRegion() {
        assertEquals("""{"region":null}""", AgentSensorsApi.regionBody(null).toString())
        assertEquals("""{"region":"EU"}""", AgentSensorsApi.regionBody("EU").toString())
    }

    @Test
    fun baseBodiesMatchTheRoute() {
        assertEquals("""{"mode":"off"}""", AgentSensorsApi.baseBody(BaseModeRequest.Off).toString())
        assertEquals(
            """{"mode":"survey","min_dur_s":300,"accuracy_m":2.0}""",
            AgentSensorsApi.baseBody(BaseModeRequest.Survey(300, 2.0)).toString(),
        )
        assertEquals(
            """{"mode":"fixed","lat_deg":12.5,"lon_deg":77.5,"h_ellipsoid_m":920.0}""",
            AgentSensorsApi.baseBody(BaseModeRequest.Fixed(12.5, 77.5, 920.0)).toString(),
        )
        assertEquals("""{"mode":"off","force":true}""", AgentSensorsApi.baseBody(BaseModeRequest.Off, force = true).toString())
    }

    @Test
    fun captureRoutesRideThePluginMount() {
        assertEquals("api/plugins/com.altnautica.world-engine/x/atlas/capture/state", AgentCaptureApi.route("atlas/capture/state"))
    }
}
