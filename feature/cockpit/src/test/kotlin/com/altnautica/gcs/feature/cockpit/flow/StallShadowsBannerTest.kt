package com.altnautica.gcs.feature.cockpit.flow

import com.altnautica.gcs.core.agent.pilot.PilotWire
import com.altnautica.gcs.core.model.MissionStall
import com.altnautica.gcs.core.model.MissionStallKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** While the stall overlay shows, the stall's own banners hide; every other banner stays. */
class StallShadowsBannerTest {
    private val stall = MissionStall(MissionStallKind.Mission, 3, "3", 0L, 30f, 15f)

    @Test
    fun `the stall overlay hides exactly the stalled, stalled-hold and return-blocked banners`() {
        for (key in listOf("pilot.banner.mission_stalled", "pilot.banner.mission_stalled_hold", "pilot.banner.return_blocked")) {
            assertTrue(key, stallShadowsBanner(key, stall))
            assertFalse("no stall, the banner shows: $key", stallShadowsBanner(key, null))
        }
        assertFalse(stallShadowsBanner("pilot.banner.route_infeasible", stall))
        assertFalse(stallShadowsBanner(null, stall))
    }

    @Test
    fun `the confined golden frame keeps its too-tight-route banner beside the stall overlay`() {
        val text = File("../../core/agent/src/test/resources/fixtures/pilot-frame-confined.json").readText()
        val pilot = PilotWire.parseState(Json.parseToJsonElement(text) as JsonObject, 0L)!!
        val flow = FlowUi.of(pilot, "android", streaming = false)
        assertEquals("pilot.banner.route_infeasible", flow.banner?.textKey)
        assertEquals("3", flow.stall?.wp)
    }
}
