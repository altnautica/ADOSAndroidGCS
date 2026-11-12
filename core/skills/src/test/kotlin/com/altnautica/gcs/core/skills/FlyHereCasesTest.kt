package com.altnautica.gcs.core.skills

import com.altnautica.gcs.core.model.ControlMode
import com.altnautica.gcs.core.model.FlightMode
import com.altnautica.gcs.core.model.PilotState
import com.altnautica.gcs.core.model.SkillContext
import com.altnautica.gcs.core.skills.builtin.FlyHereSkill
import com.altnautica.gcs.core.skills.hud.FlyHereFc
import com.altnautica.gcs.core.skills.hud.FlyHereReason
import com.altnautica.gcs.core.skills.hud.SpecText
import com.altnautica.gcs.core.skills.hud.bool
import com.altnautica.gcs.core.skills.hud.flyHereOffered
import com.altnautica.gcs.core.skills.hud.goldenCases
import com.altnautica.gcs.core.skills.hud.long
import com.altnautica.gcs.core.skills.hud.str
import com.altnautica.gcs.core.skills.hud.strings
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared Fly Here offer cases (`fly-here-cases.json`): the route and refusal reason of
 * every pilot, age and FC state, and Fly Here's skill state offered by it or refused.
 */
class FlyHereCasesTest {
    private val vehicle = FakeVehicle("fly-here-cases")
    private val cases = goldenCases("fly-here-cases.json")

    private fun pilot(input: JsonObject): PilotState? = (input["pilot"] as? JsonObject)?.let {
        adosPilot().copy(
            controlMode = ControlMode.of(it["control_mode"].str()),
            surfaceOps = it["surface_ops"].strings().toSet(),
            armed = it["armed"].bool(),
            landed = it["landed"].bool(),
            fcFailsafe = it["fc_failsafe"].bool() == true,
        )
    }

    private fun fc(input: JsonObject): FlyHereFc? = (input["fc"] as? JsonObject)?.let {
        FlyHereFc(it["guided"].bool() == true, it["armed"].bool() == true, it["landed"].bool() == true)
    }

    private fun ctx(input: JsonObject): SkillContext {
        val pilot = pilot(input)
        val fc = input["fc"] as? JsonObject
        val link = pilot?.let { FakePilotLink(connected = true).apply { fresh = (input["pilot_age_ms"].long() ?: 0L) < PilotState.STALE_MS } }
        return SkillContext(
            vehicle,
            armed = fc?.get("armed").bool() ?: pilot?.armed,
            mode = fc?.let { FlightMode(0, if (it["guided"].bool() == true) "GUIDED" else "LOITER") },
            linkFresh = true, checklistReady = true, autonomousNav = true,
            pilot = pilot, pilotLink = link, systemStatus = 4,
            fcLanded = fc?.get("landed").bool(),
        )
    }

    @Test
    fun `every fly-here case offers the shared route and reason`() {
        assertTrue(cases.any { it.name.startsWith("arm_unknown") })
        for (c in cases) {
            val offer = flyHereOffered(pilot(c.input), c.input["pilot_age_ms"].long() ?: Long.MAX_VALUE, fc(c.input))
            assertEquals(c.name, c.expected["route"].str(), offer.route?.wire)
            assertEquals(c.name, c.expected["reason"].str(), offer.reason?.wire)
        }
    }

    @Test
    fun `every fly-here case enables the skill exactly when a route is offered`() {
        for (c in cases) {
            assertEquals(c.name, c.expected["route"].str() != null, FlyHereSkill.state(ctx(c.input)).enabled)
        }
    }

    @Test
    fun `every reason is worded by a refusal spec string`() {
        val strings = testSpec.setupStrings.strings
        for (r in FlyHereReason.entries) assertTrue(r.refusalKey, r.refusalKey in strings)
        assertEquals("Fly here needs GUIDED on the flight controller", SpecText(strings).text(FlyHereReason.NotGuided.refusalKey))
        assertEquals("Pilot not answering", SpecText(strings).text(FlyHereReason.PilotStale.refusalKey))
    }
}
