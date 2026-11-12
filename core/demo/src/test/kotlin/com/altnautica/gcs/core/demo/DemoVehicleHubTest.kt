package com.altnautica.gcs.core.demo

import com.altnautica.gcs.core.link.demo.DemoOptions
import com.altnautica.gcs.core.link.transport.DemoTransport
import com.altnautica.gcs.core.mavlink.enums.MavCmd
import com.altnautica.gcs.core.mavlink.enums.MavModeFlag
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The demo pilot's reposition reaches the simulated flight controller, which flies there. */
class DemoVehicleHubTest {
    @Test
    fun `a reposition flies the simulated drone to the point`() = runTest {
        val options = DemoOptions()
        val transport = DemoTransport(backgroundScope.coroutineContext, options)
        val hub = DemoVehicleHub().apply { attach(transport) }
        hub.command(MavCmd.MAV_CMD_DO_SET_MODE, MavModeFlag.MAV_MODE_FLAG_CUSTOM_MODE_ENABLED.toFloat(), GUIDED.toFloat())
        hub.command(MavCmd.MAV_CMD_COMPONENT_ARM_DISARM, 1f)
        hub.command(MavCmd.MAV_CMD_NAV_TAKEOFF, p7 = 10f)
        advanceTimeBy(15_000)
        runCurrent()
        assertTrue("airborne before the reposition", hub.altitudeM > 9.0)
        assertEquals(0.0, transport.simulator.horizontalDistanceFromHomeM, 1.0)

        // 80 m north of home, 15 m up.
        hub.reposition(options.homeLatDeg + 80.0 / 111_319.49, options.homeLonDeg, 15f)
        advanceTimeBy(60_000)
        runCurrent()

        assertEquals(GUIDED, hub.mode)
        assertEquals(80.0, transport.simulator.horizontalDistanceFromHomeM, 3.0)
        assertEquals(15.0, hub.altitudeM, 1.0)
        transport.close()
    }

    private companion object {
        const val GUIDED = 4
    }
}
