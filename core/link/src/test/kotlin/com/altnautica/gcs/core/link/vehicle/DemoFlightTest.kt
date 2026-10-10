package com.altnautica.gcs.core.link.vehicle

import com.altnautica.gcs.core.link.command.CommandService
import com.altnautica.gcs.core.link.mavlink.ElapsedClock
import com.altnautica.gcs.core.link.testing.demoHarness
import com.altnautica.gcs.core.model.CommandResult
import com.altnautica.gcs.core.model.GpsFix
import com.altnautica.gcs.core.model.VehicleCommand
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoFlightTest {

    @Test
    fun `telemetry decodes the simulated copter and takeoff climbs`() = runTest {
        val h = demoHarness()
        val clock = ElapsedClock { testScheduler.currentTime }
        val telemetry = MavlinkVehicleTelemetry(backgroundScope, h.link, clock)
        val commands = CommandService(backgroundScope, h.link, telemetry.heartbeat, telemetry.home)

        val hb = telemetry.heartbeat.filterNotNull().first()
        assertFalse(hb.armed)
        assertEquals("STABILIZE", hb.mode.name)
        assertEquals("ALT_HOLD", commands.availableModes.first { it.isNotEmpty() }[2].name)
        assertEquals(GpsFix.Fix3D, telemetry.gps.filterNotNull().first().fix)
        val battery = telemetry.battery.filterNotNull().first()
        assertEquals(100, battery.remainingPct)
        assertTrue(battery.voltageV!! > 16f)
        assertNull(battery.timeToReserveS)
        assertTrue(telemetry.systemHealth.filterNotNull().first().sensorsHealthy != 0L)

        assertEquals(CommandResult.Accepted, commands.run(VehicleCommand.Arm()))
        assertEquals(CommandResult.Accepted, commands.run(VehicleCommand.Takeoff(10f)))
        testScheduler.advanceTimeBy(10_000)
        assertTrue(telemetry.heartbeat.value!!.armed)
        assertEquals("GUIDED", telemetry.heartbeat.value!!.mode.name)
        assertEquals(10f, telemetry.position.value!!.altRelM, 0.5f)

        assertEquals(CommandResult.Accepted, commands.run(VehicleCommand.Land))
        testScheduler.advanceTimeBy(15_000)
        assertEquals(0f, telemetry.position.value!!.altRelM, 0.05f)
        assertFalse(telemetry.heartbeat.value!!.armed)
    }

    @Test
    fun `arming is refused in a mode that cannot arm`() = runTest {
        val h = demoHarness()
        val telemetry = MavlinkVehicleTelemetry(backgroundScope, h.link, ElapsedClock { testScheduler.currentTime })
        val commands = CommandService(backgroundScope, h.link, telemetry.heartbeat, telemetry.home)
        val auto = commands.availableModes.first { it.isNotEmpty() }.first { it.name == "RTL" }
        assertEquals(CommandResult.Accepted, commands.run(VehicleCommand.SetMode(auto)))
        assertTrue(commands.run(VehicleCommand.Arm()) is CommandResult.Rejected)
    }

    @Test
    fun `auto without a mission flies a circle round home`() = runTest {
        val h = demoHarness()
        val telemetry = MavlinkVehicleTelemetry(backgroundScope, h.link, ElapsedClock { testScheduler.currentTime })
        val commands = CommandService(backgroundScope, h.link, telemetry.heartbeat, telemetry.home)
        commands.run(VehicleCommand.Arm())
        commands.run(VehicleCommand.Takeoff(20f))
        testScheduler.advanceTimeBy(12_000)
        val auto = commands.availableModes.value.first { it.name == "AUTO" }
        assertEquals(CommandResult.Accepted, commands.run(VehicleCommand.SetMode(auto)))
        testScheduler.advanceTimeBy(90_000)
        assertEquals(100.0, h.transport.simulator.horizontalDistanceFromHomeM, 15.0)
    }
}
