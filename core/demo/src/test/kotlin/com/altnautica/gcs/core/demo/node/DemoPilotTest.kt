package com.altnautica.gcs.core.demo.node

import com.altnautica.gcs.core.demo.DemoFc
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoPilotTest {

    /** A flight controller that follows commands at once and climbs straight to the take-off altitude. */
    private class FakeFc : DemoFc {
        override var armed = false
        override var altitudeM = 0.0
        override var mode = STABILIZE
        val sent = mutableListOf<Int>()

        override fun command(command: Int, p1: Float, p2: Float, p7: Float) {
            sent += command
            when (command) {
                ARM_DISARM -> if (p1 == 1f && mode in setOf(STABILIZE, GUIDED, LOITER)) armed = true else if (p1 == 0f) armed = false
                SET_MODE -> mode = p2.toInt()
                TAKEOFF -> if (armed && mode == GUIDED) altitudeM = p7.toDouble()
                LAND -> mode = 9
            }
        }
    }

    private var t = 1_000L
    private val fc = FakeFc()
    private val pilot = DemoPilot(fc) { t }

    private fun state(): JsonObject = Json.parseToJsonElement(pilot.frame()).jsonObject["data"]!!.jsonObject

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun action(name: String, args: String = ""): JsonObject {
        val a = if (args.isEmpty()) "" else ""","args":$args"""
        return Json.parseToJsonElement(pilot.handle("""{"op":"action","name":"$name","src":"android"$a}""")!!).jsonObject
    }

    private fun input(src: String, vararg axes: Float) {
        val list = axes.joinToString(",")
        pilot.handle("""{"op":"input","src":"$src","session":7,"seq":1,"t_ms":$t,"axes":[$list],"buttons":0}""")
    }

    /** Advances time in 100 ms frames, streaming centred sticks from Android. */
    private fun fly(ms: Long, vararg axes: Float = floatArrayOf(0f, 0f, 0f, 0f)) {
        var left = ms
        while (left > 0) {
            t += 100
            left -= 100
            input("android", *axes)
            state()
        }
    }

    @Test
    fun `arm then take off leaves the pilot holding with the android sticks`() {
        assertTrue(action("arm")["ok"]!!.jsonPrimitive.boolean)
        assertTrue(fc.armed)
        assertEquals("idle", state().str("owner"))

        assertTrue(action("takeoff", """{"alt_m":1.5}""")["ok"]!!.jsonPrimitive.boolean)
        fly(1_000)

        val s = state()
        assertEquals("pilot", s.str("owner"))
        assertEquals("android", s.str("input_src"))
        assertEquals(false, s["landed"]!!.jsonPrimitive.boolean)
        assertEquals("GUIDED", s.str("fc_mode_name"))
        assertEquals("done", s["sequence"]!!.jsonObject.str("step"))
        assertEquals(listOf(ARM_DISARM, SET_MODE, TAKEOFF), fc.sent)
    }

    @Test
    fun `take off arms by itself and refuses in the air`() {
        action("takeoff")
        assertTrue(fc.armed)
        fly(500)
        assertEquals("pilot", state().str("owner"))
        assertEquals("airborne", action("takeoff")["err"]!!.jsonPrimitive.content)
        assertEquals("armed", action("arm")["err"]!!.jsonPrimitive.content)
    }

    @Test
    fun `sticks take an armed idle vehicle in guided only in ados mode, behind the centring gate`() {
        fc.armed = true
        fc.altitudeM = 5.0
        fc.mode = GUIDED
        action("control_mode:fc_manual")
        input("android", 0f, 0.8f, 0f, 0f)
        assertEquals("idle", state().str("owner"))

        action("control_mode:ados")
        t += 100
        input("android", 0f, 0.8f, 0f, 0f)
        val taken = state()
        assertEquals("pilot", taken.str("owner"))
        assertEquals(true, taken["gate"]!!.jsonObject["pending"]!!.jsonPrimitive.boolean)

        fly(700)
        assertTrue(state()["gate"] is kotlinx.serialization.json.JsonNull)
    }

    @Test
    fun `a second surface is ignored until it takes the session`() {
        action("takeoff")
        fly(500)
        input("mission-control", 0.5f, 0f, 0f, 0f)
        assertEquals("android", state().str("input_src"))

        pilot.handle("""{"op":"session.take","src":"mission-control","session":9}""")
        assertEquals("mission-control", state().str("input_src"))
    }

    @Test
    fun `a flight controller mode change hands the owner to the flight controller, and land disarms to idle`() {
        action("takeoff")
        fly(500)
        assertTrue(action("rth")["ok"]!!.jsonPrimitive.boolean)
        fc.mode = 6
        t += 2_000
        assertEquals("fc_mode", state().str("owner"))

        fc.armed = false
        fc.altitudeM = 0.0
        t += 100
        val s = state()
        assertEquals("idle", s.str("owner"))
        assertEquals(true, s["landed"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `pause during a launch on the ground disarms and marks the sequence aborted`() {
        fc.mode = 9
        action("launch", """{"after":"hold","alt_m":3}""")
        // Arming from LAND first switches to GUIDED, which arms.
        assertEquals(listOf(SET_MODE, ARM_DISARM), fc.sent)
        assertTrue(fc.armed)
        action("pause")
        assertFalse(fc.armed)
        val seq = state()["sequence"]!!.jsonObject
        assertEquals("aborted", seq.str("error"))
    }

    @Test
    fun `stale input while the pilot owns shows the input-stale hold`() {
        action("takeoff")
        fly(500)
        t += 400
        assertEquals("input-stale", state().str("hold_reason"))
        pilot.handle("""{"op":"release","src":"android","session":7}""")
        assertNull(state().str("input_src"))
    }

    private companion object {
        const val STABILIZE = 0
        const val GUIDED = 4
        const val LOITER = 5
        const val ARM_DISARM = 400
        const val SET_MODE = 176
        const val TAKEOFF = 22
        const val LAND = 21
    }
}
