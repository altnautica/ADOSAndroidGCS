package com.altnautica.gcs.feature.cockpit

import com.altnautica.gcs.core.model.PilotAction
import com.altnautica.gcs.core.model.PilotOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tips' armed, airborne and fly-here facts trust the pilot only while its frames are fresh. */
class FlightTipFactsTest {
    private val disarmed = pilotState(PilotOwner.Idle, armed = false).copy(surfaceOps = setOf(PilotAction.GOTO))

    @Test
    fun `a stale disarmed pilot loses to an armed heartbeat`() {
        val facts = flightTipFacts(disarmed, pilotFresh = false, bandArmed = true)
        assertEquals(true, facts.armed)
        assertEquals(true, facts.airborne)
    }

    @Test
    fun `an unknown arm state is unknown, not an arm`() {
        val facts = flightTipFacts(null, pilotFresh = false, bandArmed = null)
        assertNull(facts.armed)
        assertNull(facts.airborne)
    }

    @Test
    fun `a fresh pilot without a flight-controller link leaves the arm state unknown`() {
        val facts = flightTipFacts(disarmed.copy(armed = null, landed = null), pilotFresh = true, bandArmed = false)
        assertNull(facts.armed)
        assertNull(facts.airborne)
    }

    @Test
    fun `a stale pilot offers no fly-here`() {
        assertFalse(flightTipFacts(disarmed, pilotFresh = false, bandArmed = false).gotoOffered)
        assertTrue(flightTipFacts(disarmed, pilotFresh = true, bandArmed = false).gotoOffered)
    }

    @Test
    fun `a fresh pilot is trusted over the heartbeat`() {
        val facts = flightTipFacts(disarmed, pilotFresh = true, bandArmed = true)
        assertEquals(false, facts.armed)
        assertEquals(false, facts.airborne)
    }
}
