package com.altnautica.gcs.feature.cockpit

import com.altnautica.gcs.core.model.PilotOwner
import com.altnautica.gcs.feature.cockpit.pilot.ControllerContext
import com.altnautica.gcs.feature.cockpit.pilot.LightbarState
import com.altnautica.gcs.feature.cockpit.pilot.lightbarState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** An unknown arm state counts as possibly airborne: the left column offers Land, never Take off. */
class UnknownArmStateTest {
    @Test
    fun `a pilot without a flight-controller link offers Land`() {
        val unknown = pilotState(PilotOwner.Idle).copy(armed = null, landed = null)
        assertTrue(leftColumnLands(unknown, bandArmed = false))
    }

    @Test
    fun `no pilot and no heartbeat offers Land`() {
        assertTrue(leftColumnLands(null, bandArmed = null))
    }

    @Test
    fun `a known disarmed vehicle offers Take off`() {
        assertFalse(leftColumnLands(pilotState(PilotOwner.Idle, armed = false), bandArmed = true))
        assertFalse(leftColumnLands(null, bandArmed = false))
    }

    @Test
    fun `an armed vehicle in the air offers Land, one the pilot reports landed Take off`() {
        assertTrue(leftColumnLands(pilotState(PilotOwner.Pilot, armed = true, landed = false), bandArmed = null))
        assertFalse(leftColumnLands(pilotState(PilotOwner.Pilot, armed = true, landed = true), bandArmed = true))
        assertTrue(leftColumnLands(null, bandArmed = true))
    }

    @Test
    fun `the controller bindings treat an unknown arm state as possibly airborne`() {
        fun ctx(pilot: com.altnautica.gcs.core.model.PilotState?, armed: Boolean?) =
            ControllerContext.of(pilot, armed, modeName = null, pickerOpen = false, lowBattery = false, failsafe = false)
        val unknown = ctx(null, armed = null)
        assertTrue(unknown.holds("airborne"))
        assertFalse(unknown.holds("on_ground"))
        assertTrue("not read as disarmed", lightbarState(unknown) != LightbarState.Disarmed)

        val noFcLink = ctx(pilotState(PilotOwner.Idle).copy(armed = null, landed = null), armed = null)
        assertTrue(noFcLink.holds("airborne"))

        // A pilot without its FC link falls back to the heartbeat when that knows.
        val heartbeatDisarmed = ctx(pilotState(PilotOwner.Idle).copy(armed = null, landed = null), armed = false)
        assertTrue(heartbeatDisarmed.holds("on_ground"))
        assertEquals(LightbarState.Disarmed, lightbarState(heartbeatDisarmed))
    }
}
