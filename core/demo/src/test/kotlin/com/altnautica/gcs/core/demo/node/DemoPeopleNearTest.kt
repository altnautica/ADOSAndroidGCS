package com.altnautica.gcs.core.demo.node

import com.altnautica.gcs.core.agent.pilot.PilotWire
import com.altnautica.gcs.core.demo.DemoScenario
import com.altnautica.gcs.core.demo.DemoScenarioStore
import com.altnautica.gcs.core.model.MoverKind
import com.altnautica.gcs.core.model.MoverSource
import com.altnautica.gcs.core.model.PilotOwner
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The people-near demo pin tracks movers as the pilot sends them and holds the person separation limit. */
class DemoPeopleNearTest {
    private var t = 1_000L
    private val scenarios = DemoScenarioStore()
    private val pilot = DemoPilot(FakeDemoFc(), scenarios = scenarios) { t }

    private fun parsed() =
        PilotWire.parseState(Json.parseToJsonElement(pilot.frame()).jsonObject["data"]!!.jsonObject, receivedAtMs = 0L)!!

    @Test
    fun `the pin flies a goto held off people, with every mover kind on the map`() {
        scenarios.pin(DemoScenario.PeopleNear)
        val p = parsed()
        assertEquals(PilotOwner.Goto, p.owner)
        assertEquals(listOf("person"), p.commandLimits)
        assertEquals(listOf(MoverKind.Person, MoverKind.Person, MoverKind.Vehicle, MoverKind.Unknown), p.movers.map { it.kind })
        assertEquals(MoverSource.Scan, p.movers.last().source)
        assertEquals(listOf(false, true, false, false), p.movers.map { it.stale })
    }

    @Test
    fun `the walking person moves east at a metre a second`() {
        scenarios.pin(DemoScenario.PeopleNear)
        val before = parsed().movers.first()
        t += 2_000L
        val after = parsed().movers.first()
        assertEquals(1f, after.velEastMps, 1e-6f)
        assertTrue(after.eastM > before.eastM)
        assertEquals(2f, after.eastM - before.eastM, 0.11f)
    }

    @Test
    fun `without the pin the pilot tracks none`() {
        assertEquals(emptyList<Any>(), parsed().movers)
    }
}
