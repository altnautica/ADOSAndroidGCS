package com.altnautica.gcs.core.skills.spec

import com.altnautica.gcs.core.model.PilotState
import com.altnautica.gcs.core.skills.testSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The pure model module cannot read the spec assets, so its pilot stale age is a constant;
 * this ties it to `flight-state.json` `stale_after_ms`, read raw and through the typed spec.
 */
class StaleAgeDriftTest {
    private val file = File("../../feature/cockpit/src/main/assets/cockpit-spec/flight-state.json")

    @Test
    fun `the pilot stale age is the spec's stale_after_ms`() {
        val raw = Json.parseToJsonElement(file.readText()).jsonObject.getValue("stale_after_ms").jsonPrimitive.long
        assertEquals(raw, PilotState.STALE_MS)
        assertEquals(raw, testSpec.flightState.staleAfterMs)
    }

    @Test
    fun `a pilot frame is stale at exactly the stale age`() {
        val stale = PilotState.STALE_MS
        assertEquals(true, com.altnautica.gcs.core.skills.hud.pilotFresh(0L, stale - 1))
        assertEquals(false, com.altnautica.gcs.core.skills.hud.pilotFresh(0L, stale))
    }
}
