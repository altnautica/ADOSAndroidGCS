package com.altnautica.gcs.feature.cockpit.chip

import com.altnautica.gcs.core.model.PilotOwner
import com.altnautica.gcs.core.model.PilotProfile
import com.altnautica.gcs.core.model.ProfileLimits
import com.altnautica.gcs.core.model.StickCommand
import com.altnautica.gcs.feature.cockpit.pilotState
import com.altnautica.gcs.feature.cockpit.testSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The flight-state chip's stick parts from `flight-state.json`: limit, speeds, input age, clearance. */
class StickChipTest {
    private val chips = FlightStateChipResolver(testSpec.flightState, testSpec.tokens, testSpec.setupStrings.strings)
    private val sticks = pilotState(PilotOwner.Pilot, armed = true, landed = false, receivedAtMs = 1_000L)
        .copy(profile = PilotProfile.Normal, profileLimits = ProfileLimits(PilotProfile.Normal, 7f, 1.5f), controlMode = null)
    private val ctx = ChipContext(nowMs = 1_100L, hSpeedMs = 3.24f, vSpeedMs = -0.44f)

    private fun texts(ui: FlightStateChipUi) = ui.secondary.map { it.text }

    private fun part(ui: FlightStateChipUi, prefix: String) = ui.secondary.firstOrNull { it.text.startsWith(prefix) }

    @Test
    fun `the chip shows the profile with its limit and the horizontal and vertical speed`() {
        val ui = chips.resolve(sticks, ctx)
        assertEquals("Pilot · Normal · 7 m/s max", ui.primary.text)
        assertEquals(listOf("H 3.2 m/s", "V -0.4 m/s"), texts(ui))
        assertEquals("fc", ui.secondary.first().colorKey)
        assertEquals("Pilot · Sport · 2.5 m/s max", chips.resolve(sticks.copy(profile = PilotProfile.Sport, profileLimits = ProfileLimits(PilotProfile.Sport, 2.5f)), ctx).primary.text)
    }

    @Test
    fun `without limits the chip reads the profile alone, and a near-zero climb reads 0_0`() {
        val ui = chips.resolve(sticks.copy(profileLimits = null), ctx.copy(vSpeedMs = -0.04f))
        assertEquals("Pilot · Normal", ui.primary.text)
        assertEquals("V 0.0 m/s", part(ui, "V ")?.text)
    }

    @Test
    fun `the stick input age shows only over 100 ms`() {
        assertNull(part(chips.resolve(sticks.copy(stick = StickCommand(100L)), ctx), "Input"))
        val late = part(chips.resolve(sticks.copy(stick = StickCommand(240L)), ctx), "Input")
        assertEquals("Input 240 ms" to "avoiding", late?.text to late?.colorKey)
    }

    @Test
    fun `the clearance warning is amber within two margins and red inside the margin, for any owner`() {
        assertNull(part(chips.resolve(sticks.copy(minClearanceM = 3.0f), ctx), "Obstacle"))
        val near = part(chips.resolve(sticks.copy(minClearanceM = 2.4f), ctx), "Obstacle")
        assertEquals("Obstacle 2.4 m" to "avoiding", near?.text to near?.colorKey)
        val inside = part(chips.resolve(sticks.copy(minClearanceM = 1.5f), ctx), "Obstacle")
        assertEquals("Obstacle 1.5 m" to "blocked", inside?.text to inside?.colorKey)
        val goto = sticks.copy(owner = PilotOwner.Goto, minClearanceM = 1.0f)
        assertEquals("blocked", part(chips.resolve(goto, ctx), "Obstacle")?.colorKey)
        assertNull("no margin known", part(chips.resolve(sticks.copy(profileLimits = null, minClearanceM = 0.5f), ctx), "Obstacle"))
    }

    @Test
    fun `the speeds show only while the sticks own`() {
        assertTrue(texts(chips.resolve(sticks.copy(owner = PilotOwner.Goto), ctx)).none { it.endsWith("m/s") })
    }
}
