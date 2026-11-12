package com.altnautica.gcs.core.demo.node

import com.altnautica.gcs.core.demo.DemoFc
import com.altnautica.gcs.core.demo.DemoScenario
import com.altnautica.gcs.core.demo.DemoScenarioStore
import com.altnautica.gcs.core.model.MissionState
import org.junit.Assert.assertEquals
import org.junit.Test

/** Away from the cockpit, the demo drone's mission reads from its pilot, never as unknown. */
class DemoMissionMirrorTest {
    private class GroundFc : DemoFc {
        override var armed = false
        override var altitudeM = 0.0
        override var mode = 0
        override var gnssFix = true
        override fun command(command: Int, p1: Float, p2: Float, p7: Float) = Unit
        override fun reposition(latDeg: Double, lonDeg: Double, altM: Float) = Unit
    }

    private val scenarios = DemoScenarioStore()
    private val pilot = DemoPilot(GroundFc(), scenarios = scenarios) { 1_000L }

    @Test
    fun `an idle demo drone mirrors no mission`() {
        assertEquals(MissionState.None, demoMissionMirror(pilot.view()).status?.state)
    }

    @Test
    fun `a pinned mission mirrors its state and waypoint`() {
        scenarios.pin(DemoScenario.MissionPaused)
        val status = demoMissionMirror(pilot.view()).status
        assertEquals(MissionState.Paused, status?.state)
        assertEquals(4, status?.seq)
        assertEquals(6, status?.total)
    }
}
