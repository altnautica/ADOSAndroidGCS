package com.altnautica.gcs.feature.cockpit

import com.altnautica.gcs.core.model.PilotAction
import com.altnautica.gcs.core.model.PilotOwner
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The tips' armed, airborne and fly-here facts trust the pilot only while its frames are fresh. */
class FlightTipFactsTest {
    private val disarmed = pilotState(PilotOwner.Idle, armed = false).copy(surfaceOps = setOf(PilotAction.GOTO))

    @Test
    fun `a stale disarmed pilot loses to an armed heartbeat`() {
        val facts = flightTipFacts(disarmed, pilotFresh = false, bandArmed = true)
        assertTrue(facts.armed)
        assertTrue(facts.airborne)
    }

    @Test
    fun `an unknown arm state counts as possibly airborne, not as an arm`() {
        val facts = flightTipFacts(null, pilotFresh = false, bandArmed = null)
        assertFalse(facts.armed)
        assertTrue(facts.airborne)
    }

    @Test
    fun `a stale pilot offers no fly-here`() {
        assertFalse(flightTipFacts(disarmed, pilotFresh = false, bandArmed = false).gotoOffered)
        assertTrue(flightTipFacts(disarmed, pilotFresh = true, bandArmed = false).gotoOffered)
    }

    @Test
    fun `a fresh pilot is trusted over the heartbeat`() {
        val facts = flightTipFacts(disarmed, pilotFresh = true, bandArmed = true)
        assertFalse(facts.armed)
        assertFalse(facts.airborne)
    }
}
