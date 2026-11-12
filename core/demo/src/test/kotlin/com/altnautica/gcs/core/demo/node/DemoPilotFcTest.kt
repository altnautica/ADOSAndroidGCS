package com.altnautica.gcs.core.demo.node

import com.altnautica.gcs.core.demo.DemoScenario
import com.altnautica.gcs.core.demo.DemoScenarioStore
import com.altnautica.gcs.core.demo.node.FakeDemoFc.Companion.ARM_DISARM
import com.altnautica.gcs.core.demo.node.FakeDemoFc.Companion.AUTO
import com.altnautica.gcs.core.demo.node.FakeDemoFc.Companion.GUIDED
import com.altnautica.gcs.core.demo.node.FakeDemoFc.Companion.LAND_MODE
import com.altnautica.gcs.core.demo.node.FakeDemoFc.Companion.LOITER
import com.altnautica.gcs.core.demo.node.FakeDemoFc.Companion.SET_MODE
import com.altnautica.gcs.core.demo.node.FakeDemoFc.Companion.TAKEOFF
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The demo pilot driving the demo flight controller when a scenario is pinned. */
class DemoPilotFcTest {
    private val fc = FakeDemoFc()
    private val scenarios = DemoScenarioStore()
    private val pilot = DemoPilot(fc, scenarios = scenarios) { 1_000L }

    private fun frame() = pilot.frame()

    @Test
    fun `a flying pin takes the flight controller into the air and into the pin's mode, and unpinning lands it`() {
        scenarios.pin(DemoScenario.MissionActive)
        frame()
        assertTrue(fc.armed)
        assertEquals(GUIDED, fc.mode)
        assertEquals(15.0, fc.altitudeM, 1e-9)
        assertEquals(listOf(SET_MODE, ARM_DISARM, TAKEOFF), fc.sent)
        // Up at the pin's height, the flight controller flies the pin's mode.
        frame()
        assertEquals(AUTO, fc.mode)

        // Another flying pin keeps it up and only changes its mode.
        scenarios.pin(DemoScenario.Follow)
        frame()
        assertEquals(GUIDED, fc.mode)
        assertEquals(listOf(SET_MODE, ARM_DISARM, TAKEOFF, SET_MODE, SET_MODE), fc.sent)

        scenarios.pin(null)
        frame()
        assertEquals(LAND_MODE, fc.mode)
    }

    @Test
    fun `the control-mode refusal pin leaves the flight controller in LOITER, off the guided route`() {
        scenarios.pin(DemoScenario.GotoRefusedControlMode)
        frame()
        frame()
        assertEquals(LOITER, fc.mode)
    }

    @Test
    fun `a flying pin after a ground pin stops the landing and holds the flight controller in the air`() {
        scenarios.pin(DemoScenario.GotoRefusedBadArgs)
        frame()
        // The ground pin lands the flight controller the flying pin lifted.
        scenarios.pin(DemoScenario.GotoRefusedLanded)
        frame()
        assertEquals(LAND_MODE, fc.mode)

        // Still in the air: the no-home and tuning pins take it out of LAND, so a surface offers Fly Here again.
        for (pin in listOf(DemoScenario.GotoRefusedNoHome, DemoScenario.GotoRefusedTuning)) {
            scenarios.pin(DemoScenario.GotoRefusedLanded)
            frame()
            scenarios.pin(pin)
            frame()
            assertEquals(pin.name, GUIDED, fc.mode)
            assertTrue(pin.name, fc.armed)
        }
    }

    @Test
    fun `the no-GNSS pin drops the flight controller's fix until it is lifted`() {
        scenarios.pin(DemoScenario.NoGnss)
        frame()
        assertFalse(fc.gnssFix)
        scenarios.pin(null)
        frame()
        assertTrue(fc.gnssFix)
    }

    @Test
    fun `a ground pin leaves the flight controller alone`() {
        scenarios.pin(DemoScenario.ReadinessBlocked)
        frame()
        scenarios.pin(null)
        frame()
        assertFalse(fc.armed)
        assertEquals(emptyList<Int>(), fc.sent)
    }
}
