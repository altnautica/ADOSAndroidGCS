package com.altnautica.gcs.feature.cockpit.flow

import com.altnautica.gcs.core.agent.pilot.PilotWire
import com.altnautica.gcs.core.model.PilotState
import com.altnautica.gcs.core.skills.hud.ConfinedText
import com.altnautica.gcs.core.skills.hud.SpecText
import com.altnautica.gcs.feature.cockpit.testSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** The pilot frame's route check and banners as the cockpit words them, read off the wire. */
class RouteCheckWireTextTest {
    private val text = ConfinedText(SpecText(testSpec.setupStrings))
    private val golden = Json.parseToJsonElement(File("../../core/agent/src/test/resources/fixtures/pilot-frame-confined.json").readText()) as JsonObject

    /** The confined golden frame with its `route_check` (or `banner`) replaced by [json]. */
    private fun frame(key: String, json: String): PilotState =
        PilotWire.parseState(JsonObject(golden + (key to Json.parseToJsonElement(json))), 0L)!!

    private fun routeLine(json: String): String = text.report(frame("route_check", json).routeCheck)!!.line

    @Test
    fun `an unverified check says a stale feed, then names the waypoints it could not place, then a mission too long to check`() {
        assertEquals(
            "Route not fully checked: the mission is not downloaded yet · slow near walls",
            routeLine("""{"state":"unverified","required_m":0.577,"unverified_legs":[],"unchecked":[3],"stale":true}"""),
        )
        assertEquals(
            "Route not fully checked: no position for waypoint 3, 5 · slow near walls",
            routeLine("""{"state":"unverified","required_m":0.577,"unverified_legs":[],"unchecked":[3,5],"truncated":true}"""),
        )
        assertEquals(
            "Route not fully checked: the mission is too long · slow near walls",
            routeLine("""{"state":"unverified","required_m":0.577,"unverified_legs":[],"truncated":true}"""),
        )
        assertEquals(
            "Route not seen yet · slow near walls",
            routeLine("""{"state":"unverified","required_m":0.577,"unverified_legs":[5]}"""),
        )
    }

    @Test
    fun `a pinch outranks what was not checked`() {
        assertEquals(
            "Route too tight: leg — has — m, needs 0.58 m",
            routeLine("""{"state":"infeasible","required_m":0.577,"unverified_legs":[],"unchecked":[3]}"""),
        )
    }

    @Test
    fun `clearances print as the agent and the web print them, 0_525 as 0_53`() {
        assertEquals(
            "Route too tight: leg 3→4 has 0.53 m, needs 0.58 m",
            routeLine(
                """{"state":"infeasible","required_m":0.577,"unverified_legs":[],
                |"pinch":{"from_seq":3,"to_seq":4,"ned":[1,2,-3],"clearance_m":0.525,"required_m":0.577}}""".trimMargin(),
            ),
        )
    }

    @Test
    fun `the GNSS-free low-battery countdown banner reads its landing line from the spec`() {
        val pilot = frame("banner", """{"text_key":"pilot.banner.low_battery_land_in","args":{"pct":18,"need_pct":20,"in_s":10},"severity":"critical","since_ms":0}""")
        assertEquals("Battery reserve 18% (need 20%) · Landing in 10 s", bannerLine(testSpec.setupStrings, pilot.banner!!))
    }
}
