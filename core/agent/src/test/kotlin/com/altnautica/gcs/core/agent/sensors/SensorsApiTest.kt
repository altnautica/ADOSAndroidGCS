package com.altnautica.gcs.core.agent.sensors

import com.altnautica.gcs.core.agent.capture.AgentCaptureApi
import org.junit.Assert.assertEquals
import org.junit.Test

class SensorsApiTest {

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
