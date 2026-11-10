package com.altnautica.gcs.feature.cockpit.chip

import com.altnautica.gcs.core.agent.pilot.PilotWire
import com.altnautica.gcs.core.skills.hud.FlyHereFc
import com.altnautica.gcs.core.skills.hud.flyHereOffered
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** The shared Fly Here offer cases (`fly-here-cases.json`): the route for every pilot, age and FC state. */
class FlyHereCasesTest {
    private val cases: List<JsonObject> =
        Json.parseToJsonElement(File("../../core/skills/src/test/resources/fixtures/fly-here-cases.json").readText()).jsonArray.map { it.jsonObject }

    @Test
    fun `every fly here case offers the expected route`() {
        assertEquals(18, cases.size)
        for (c in cases) {
            val name = c["name"]!!.jsonPrimitive.content
            val input = c["input"]!!.jsonObject
            val pilot = (input["pilot"] as? JsonObject)?.let { PilotWire.parseState(it, receivedAtMs = 0L) }
            val fc = (input["fc"] as? JsonObject)?.let {
                FlyHereFc(it["guided"]!!.jsonPrimitive.boolean, it["armed"]!!.jsonPrimitive.boolean, it["landed"]!!.jsonPrimitive.boolean)
            }
            val route = flyHereOffered(pilot, (input["pilot_age_ms"] as? JsonPrimitive)?.longOrNull ?: Long.MAX_VALUE, fc)
            val expected = (c["expected"]!!.jsonObject["route"] as? JsonPrimitive)?.contentOrNull
            assertEquals(name, expected, route?.wire)
        }
    }
}
