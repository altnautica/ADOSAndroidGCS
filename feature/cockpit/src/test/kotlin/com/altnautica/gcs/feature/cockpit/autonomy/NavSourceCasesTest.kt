package com.altnautica.gcs.feature.cockpit.autonomy

import com.altnautica.gcs.core.agent.pilot.PilotWire
import com.altnautica.gcs.feature.cockpit.testSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The shared nav-source chip golden cases (`nav-source-cases.json`): label, word and tone of every case. */
class NavSourceCasesTest {

    private val text = TargetText(testSpec.setupStrings)

    private val cases: List<JsonObject> =
        Json.parseToJsonElement(File("../../core/skills/src/test/resources/fixtures/nav-source-cases.json").readText()).jsonArray.map { it.jsonObject }

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

    private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull

    @Test
    fun `every nav-source case resolves to its expected label, word and tone`() {
        assertTrue(cases.isNotEmpty())
        for (case in cases) {
            val name = case.text("name")!!
            val input = case["input"]!!.jsonObject
            val expected = case["expected"]!!.jsonObject
            val nav = (input["nav"] as? JsonObject)?.let(AutonomyParse::nav)
            val gps = input["gps"] as? JsonObject
            // The pilot's position_unverified goes through the pilot wire parser, as it arrives.
            val unverified = (input["position_unverified"] as? JsonObject)?.let { pu ->
                PilotWire.parseState(buildJsonObject { put("owner", "idle"); put("position_unverified", pu) }, receivedAtMs = 0L)!!.positionUnverified
            }
            val chip = NavSource.resolve(
                NavInputs(
                    ekfFlags = input["ekf_flags"]!!.jsonPrimitive.int,
                    ekfAgeMs = input.num("ekf_age_ms")?.toLong() ?: 100L,
                    nav = nav,
                    navFresh = nav != null,
                    commandedSet = null,
                    params = (input["params"] as? JsonObject).orEmpty().mapValues { it.value.jsonPrimitive.int },
                    gpsFixType = gps?.num("fix_type")?.toInt(),
                    sats = gps?.num("satellites")?.toInt(),
                    hdop = gps?.num("hdop")?.toFloat(),
                    positionUnverified = unverified,
                ),
            )
            assertEquals(name, expected.text("label"), text.navLabel(chip))
            assertEquals(name, expected.text("word"), chip.quality?.let(text::quality))
            assertEquals(name, expected.text("tone"), chip.tone.name.lowercase())
        }
    }
}
