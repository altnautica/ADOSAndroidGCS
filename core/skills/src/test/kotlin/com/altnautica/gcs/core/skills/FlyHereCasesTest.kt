package com.altnautica.gcs.core.skills

import com.altnautica.gcs.core.model.ControlMode
import com.altnautica.gcs.core.model.FlightMode
import com.altnautica.gcs.core.model.SkillContext
import com.altnautica.gcs.core.skills.builtin.FlyHereSkill
import com.altnautica.gcs.core.skills.hud.bool
import com.altnautica.gcs.core.skills.hud.goldenCases
import com.altnautica.gcs.core.skills.hud.long
import com.altnautica.gcs.core.skills.hud.str
import com.altnautica.gcs.core.skills.hud.strings
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** Fly Here's state follows the shared offer for every case: offered by the expected route, or refused. */
class FlyHereCasesTest {
    private val vehicle = FakeVehicle("fly-here-cases")

    private fun ctx(input: JsonObject): SkillContext {
        val p = input["pilot"] as? JsonObject
        val fc = input["fc"] as? JsonObject
        val pilot = p?.let {
            adosPilot(armed = it["armed"].bool() == true).copy(
                controlMode = ControlMode.of(it["control_mode"].str()) ?: ControlMode.Ados,
                surfaceOps = it["surface_ops"].strings().toSet(),
                landed = it["landed"].bool() == true,
                fcFailsafe = it["fc_failsafe"].bool() == true,
            )
        }
        val link = pilot?.let { FakePilotLink(connected = true).apply { fresh = (input["pilot_age_ms"].long() ?: 0L) < 2_000L } }
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
    fun `every fly-here case offers the shared route or none`() {
        val cases = goldenCases("fly-here-cases.json")
        for (c in cases) {
            val route = c.expected["route"].str()
            val state = FlyHereSkill.state(ctx(c.input))
            assertEquals(c.name, route != null, state.enabled)
        }
    }
}
