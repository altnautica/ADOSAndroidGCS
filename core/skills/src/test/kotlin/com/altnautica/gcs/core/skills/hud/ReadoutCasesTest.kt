package com.altnautica.gcs.core.skills.hud

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shared readout formatters and pilot freshness read every case as the web modules do. */
class ReadoutCasesTest {
    private val cases = Json.parseToJsonElement(
        checkNotNull(javaClass.classLoader!!.getResource("fixtures/readout-cases.json")).readText(),
    ).jsonArray.map { it.jsonObject }

    @Test
    fun `every readout case formats as the shared module`() {
        assertEquals(17, cases.size)
        for (c in cases) {
            val name = c["name"].str()!!
            val input = c["input"]!!.jsonObject
            val v = (input["v"] as? JsonPrimitive)?.doubleOrNull
            val got = when (val fn = input["fn"].str()) {
                "fmtSigned1" -> ReadoutFormat.signed1(v)
                "fmtDistance" -> ReadoutFormat.distance(v)
                "countdownS" -> ReadoutFormat.countdownS(v!!.toLong()).toString()
                else -> error("$name: unknown fn $fn")
            }
            val expected = (c["expected"] as JsonPrimitive).content
            assertEquals(name, expected, got)
        }
    }

    @Test
    fun `a pilot frame is fresh below the spec's stale age only`() {
        assertTrue(pilotFresh(frameAtMs = 1_000L, nowMs = 2_999L))
        assertFalse(pilotFresh(frameAtMs = 1_000L, nowMs = 3_000L))
        assertFalse(pilotFresh(frameAtMs = null, nowMs = 3_000L))
    }
}
