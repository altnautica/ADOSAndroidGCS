package com.altnautica.gcs.feature.cockpit.pilot

import com.altnautica.gcs.core.model.ControlMode
import com.altnautica.gcs.core.model.PilotOwner
import com.altnautica.gcs.feature.cockpit.pilotState
import org.junit.Assert.assertEquals
import org.junit.Test

class StickRouteTest {
    @Test
    fun `an unknown control mode sends sticks to MANUAL_CONTROL only for a flight controller on the phone`() {
        assertEquals(StickRoute.None, stickRoute(pilotLinked = false, pilot = null, directFc = false))
        assertEquals(StickRoute.None, stickRoute(pilotLinked = true, pilot = null, directFc = false))
        assertEquals(StickRoute.FcManual, stickRoute(pilotLinked = false, pilot = null, directFc = true))
    }

    @Test
    fun `a reported control mode decides the route on any reach`() {
        val fcManual = pilotState(PilotOwner.Idle, controlMode = ControlMode.FcManual)
        val ados = pilotState(PilotOwner.Idle, controlMode = ControlMode.Ados)
        val elrs = pilotState(PilotOwner.Idle, controlMode = ControlMode.Elrs)
        assertEquals(StickRoute.FcManual, stickRoute(true, fcManual, directFc = false))
        assertEquals(StickRoute.Pilot, stickRoute(true, ados, directFc = false))
        assertEquals(StickRoute.None, stickRoute(false, ados, directFc = false))
        assertEquals(StickRoute.None, stickRoute(true, elrs, directFc = true))
    }
}
