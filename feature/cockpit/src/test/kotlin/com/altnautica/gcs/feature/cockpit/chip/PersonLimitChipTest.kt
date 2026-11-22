package com.altnautica.gcs.feature.cockpit.chip

import com.altnautica.gcs.core.agent.pilot.PilotWire
import com.altnautica.gcs.feature.cockpit.testSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** The person separation limit shows with the other command limits, worded the same on every surface. */
class PersonLimitChipTest {
    private val chips = FlightStateChipResolver(testSpec.flightState, testSpec.tokens, testSpec.setupStrings.strings)

    private fun pilot(json: String) = checkNotNull(PilotWire.parseState(Json.parseToJsonElement(json) as JsonObject, 0L))

    @Test
    fun `a goto held off a person shows the person limit`() {
        val p = pilot("""{"owner":"goto","command":{"limits":["speed","person"]}}""")
        assertEquals("person", chips.commandLimitCode(p))
        assertEquals("Keeping distance from people", testSpec.flightState.commandLimitLabels["person"])
        assertEquals("Keeping distance from people", testSpec.setupStrings.strings["pilot.command_limit.person"])
    }

    @Test
    fun `the sticks held off a person show it too, and a blocked avoidance does not hide it`() {
        val p = pilot("""{"owner":"pilot","avoid_state":"blocked","command":{"limits":["person"]}}""")
        assertEquals("person", chips.commandLimitCode(p))
    }
}
