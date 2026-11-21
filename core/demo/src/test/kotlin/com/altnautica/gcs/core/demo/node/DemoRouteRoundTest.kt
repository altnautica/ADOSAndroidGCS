package com.altnautica.gcs.core.demo.node

import com.altnautica.gcs.core.agent.pilot.PilotWire
import com.altnautica.gcs.core.demo.DemoScenario
import com.altnautica.gcs.core.demo.DemoScenarioStore
import com.altnautica.gcs.core.model.AvoidMode
import com.altnautica.gcs.core.model.PilotOwner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The demo pins for a detour round a blocked mission leg and a return routed home parse as the pilot sends them. */
class DemoRouteRoundTest {
    private val scenarios = DemoScenarioStore()
    private val pilot = DemoPilot(FakeDemoFc(), scenarios = scenarios) { 1_000L }

    private fun parsed() =
        PilotWire.parseState(Json.parseToJsonElement(pilot.frame()).jsonObject["data"]!!.jsonObject, receivedAtMs = 0L)!!

    @Test
    fun `the detour pin flies a goto round the leg to waypoint 4`() {
        scenarios.pin(DemoScenario.MissionDetour)
        val p = parsed()
        assertEquals(PilotOwner.Goto, p.owner)
        assertEquals(AvoidMode.Bypass, p.avoid)
        val detour = p.missionDetour!!
        assertEquals(4, detour.seq)
        assertEquals(listOf(18.25f, 0f, -15f), detour.targetNed)
        assertEquals("pilot.banner.mission_detour", p.banner?.textKey)
        assertNull(p.returnRoute)
    }

    @Test
    fun `the routed return pin flies home the planned way`() {
        scenarios.pin(DemoScenario.ReturnRouted)
        val p = parsed()
        assertEquals(PilotOwner.Goto, p.owner)
        val route = p.returnRoute!!
        assertEquals(37.6f, route.remainingM, 1e-4f)
        assertEquals(24.2f, route.straightM, 1e-4f)
        assertEquals("pilot.banner.return_routed", p.banner?.textKey)
        assertNull(p.missionDetour)
    }
}
