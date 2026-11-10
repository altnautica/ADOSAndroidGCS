package com.altnautica.gcs.feature.cockpit.chip

import com.altnautica.gcs.core.agent.pilot.PilotWire
import com.altnautica.gcs.core.model.LocalPosition
import com.altnautica.gcs.core.skills.spec.SpecColor
import com.altnautica.gcs.feature.cockpit.goalDistanceM
import com.altnautica.gcs.feature.cockpit.testSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The shared chip golden cases (`chip-cases.json`), every field of every case. */
class ChipCasesTest {

    private val chips = FlightStateChipResolver(testSpec.flightState, testSpec.tokens)

    private val cases: List<JsonObject> =
        Json.parseToJsonElement(File("../../core/skills/src/test/resources/fixtures/chip-cases.json").readText()).jsonArray.map { it.jsonObject }

    private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull

    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

    private fun buttonId(b: ChipButton?): String? = when (b) {
        ChipButton.SetUpAircraft -> "setup"
        ChipButton.Resume -> "resume"
        ChipButton.TakeControl -> "take_control"
        null -> null
    }

    @Test
    fun `the case file covers every chip rule`() {
        assertTrue(cases.size >= 30)
        val covered = cases.map { it["expected"]!!.jsonObject.text("rule") }.toSet()
        assertEquals(emptyList<String>(), testSpec.flightState.rules.map { it.id }.filterNot { it in covered })
    }

    @Test
    fun `every chip case resolves to its expected rule, text, colour, dim, button and secondary line`() {
        val now = 1_000_000L
        for (case in cases) {
            val name = case.text("name")!!
            val input = case["input"]!!.jsonObject
            val expected = case["expected"]!!.jsonObject
            val pilot = PilotWire.parseState(input["pilot"]!!.jsonObject, receivedAtMs = now - input["pilot_age_ms"]!!.jsonPrimitive.long)
            assertNotNull(name, pilot)
            val local = (input["local_pos"] as? JsonObject)?.let { lp ->
                LocalPosition(lp.num("x")!!.toFloat(), lp.num("y")!!.toFloat(), 0f, 0f, 0f, 0f, 0L)
            }
            val ctx = ChipContext(
                nowMs = now,
                missionCompleteSinceMs = input.num("complete_age_ms")?.let { now - it.toLong() },
                homeDistM = input.num("home_dist_m")?.toFloat(),
                goalDistM = pilot!!.goalNed?.let { goalDistanceM(it, local) },
                missionEtaS = input.num("ar_eta_s")?.toFloat(),
                modeDetail = input.text("mode_detail"),
            )
            val ui = chips.resolve(pilot, ctx)

            val color = expected.text("color")!!
            assertEquals(name, expected.text("rule"), ui.ruleId)
            assertEquals(name, expected.text("text"), ui.primary.text)
            assertEquals(name, color, ui.primary.colorKey)
            assertEquals(name, SpecColor.argb(testSpec.tokens.state.getValue(color)), ui.primary.color)
            assertEquals(name, expected["dim"]!!.jsonPrimitive.boolean, ui.dim)
            assertEquals(name, expected.text("button"), buttonId(ui.button))
            assertEquals(name, (expected["secondary"] as JsonArray).map { (it as JsonPrimitive).content }, ui.secondary.map { it.text })
        }
    }

    @Test
    fun `an unknown hold reason reads as its wire string with spaces`() {
        assertEquals("new reason", chips.holdReasonLabel("new_reason"))
        assertEquals("Controller lost", chips.holdReasonLabel("controller-lost"))
    }
}
