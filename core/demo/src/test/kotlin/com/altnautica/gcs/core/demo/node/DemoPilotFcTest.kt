package com.altnautica.gcs.core.demo.node

import com.altnautica.gcs.core.demo.DemoScenario
import com.altnautica.gcs.core.demo.DemoScenarioStore
import com.altnautica.gcs.core.demo.node.FakeDemoFc.Companion.ARM_DISARM
import com.altnautica.gcs.core.demo.node.FakeDemoFc.Companion.GUIDED
import com.altnautica.gcs.core.demo.node.FakeDemoFc.Companion.LAND_MODE
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
    fun `a flying pin takes the flight controller into the air and unpinning lands it`() {
        scenarios.pin(DemoScenario.MissionActive)
        frame()
        assertTrue(fc.armed)
        assertEquals(GUIDED, fc.mode)
        assertEquals(15.0, fc.altitudeM, 1e-9)
        assertEquals(listOf(SET_MODE, ARM_DISARM, TAKEOFF), fc.sent)

        // Another flying pin keeps it up without new commands.
        scenarios.pin(DemoScenario.Follow)
        frame()
        assertEquals(3, fc.sent.size)

        scenarios.pin(null)
        frame()
        assertEquals(LAND_MODE, fc.mode)
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
