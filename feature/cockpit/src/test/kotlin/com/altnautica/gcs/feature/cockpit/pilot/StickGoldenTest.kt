package com.altnautica.gcs.feature.cockpit.pilot

import com.altnautica.gcs.core.skills.controls.StickMode
import com.altnautica.gcs.core.skills.input.GamepadAxes
import com.altnautica.gcs.core.skills.input.PadSnapshot
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared stick golden vectors (`cockpit-spec/stick-golden.json`): a raw stick (x + right,
 * y + down) through the radial dead zone and the stick mode gives the same
 * `[roll, pitch, thr, yaw]` from a controller and from the on-screen sticks, in both modes and
 * at the dead-zone edge, as on every other surface.
 */
class StickGoldenTest {
    private val golden = Json.parseToJsonElement(File("src/main/assets/cockpit-spec/stick-golden.json").readText()).jsonObject
    private val tolerance = golden.getValue("tolerance").jsonPrimitive.float
    private val cases = golden.getValue("cases").jsonArray.map { it.jsonObject }

    private class Case(val name: String, val mode: StickMode, val deadzone: Float, val raw: GamepadAxes, val out: List<Float>)

    private fun case(o: JsonObject): Case {
        val raw = o.getValue("raw").jsonObject
        fun r(k: String) = raw.getValue(k).jsonPrimitive.float
        return Case(
            name = o.getValue("name").jsonPrimitive.content,
            mode = checkNotNull(StickMode.fromWire(o.getValue("mode").jsonPrimitive.content)),
            deadzone = (o["deadzone"] ?: golden.getValue("deadzone")).jsonPrimitive.float,
            // The router reports y up positive; the golden raw is y down positive.
            raw = GamepadAxes(r("lx"), -r("ly"), r("rx"), -r("ry")),
            out = o.getValue("out").jsonArray.map { it.jsonPrimitive.float },
        )
    }

    private fun assertAxes(label: String, want: List<Float>, got: List<Float>) {
        for (i in want.indices) assertEquals("$label axis $i", want[i], got[i], tolerance)
    }

    @Test
    fun `the golden file covers both stick modes and the dead-zone edge`() {
        val all = cases.map(::case)
        assertTrue(all.any { it.mode == StickMode.Mode1 } && all.any { it.mode == StickMode.Mode2 })
        assertTrue(all.any { it.name.contains("edge") })
    }

    @Test
    fun `a controller stick conditions to the golden axes`() {
        for (c in cases.map(::case)) {
            val i = StickConditioning(mode = c.mode, deadzone = c.deadzone)
                .condition(PadSnapshot(3, 0x054c, 0x05c4, c.raw, c.raw, 0, 1_000L, emptyList()))
            assertAxes(c.name, c.out, listOf(i.roll, i.pitch, i.throttle, i.yaw))
        }
    }

    @Test
    fun `the on-screen sticks condition to the golden axes`() {
        for (c in cases.map(::case)) {
            val sticks = OnScreenSticks()
            sticks.touch(true)
            sticks.setLeft(c.raw.leftX, c.raw.leftY)
            sticks.setRight(c.raw.rightX, c.raw.rightY)
            val i = StickConditioning(mode = c.mode, deadzone = c.deadzone).condition(sticks.snapshot(1_000L))
            assertAxes(c.name, c.out, listOf(i.roll, i.pitch, i.throttle, i.yaw))
        }
    }
}
