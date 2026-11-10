package com.altnautica.gcs.core.skills

import com.altnautica.gcs.core.model.CommandResult
import com.altnautica.gcs.core.model.PilotAction
import com.altnautica.gcs.core.model.SkillContext
import com.altnautica.gcs.core.model.VehicleCommand
import com.altnautica.gcs.core.skills.builtin.FlyHereSkill
import com.altnautica.gcs.core.skills.builtin.SkillArgs
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlyHereRouteTest {
    private val vehicle = FakeVehicle()
    private val args = mapOf(SkillArgs.LAT to 47.1, SkillArgs.LON to 8.5, SkillArgs.ALTITUDE_M to 10f)

    private fun ctx(link: FakePilotLink, mode: String? = "GUIDED") = SkillContext(
        vehicle, armed = true, mode = mode?.let { com.altnautica.gcs.core.model.FlightMode(4, it) }, linkFresh = true, checklistReady = true, autonomousNav = true,
        pilot = adosPilot().copy(surfaceOps = setOf(PilotAction.GOTO)), pilotLink = link,
    )

    @Test
    fun `a fresh pilot offering goto flies the point`() = runTest {
        val link = FakePilotLink(connected = true)
        FlyHereSkill.activate(ctx(link), args)
        assertEquals(listOf(PilotAction.GOTO), link.actions.map { it.name })
        assertTrue(vehicle.control.sent.isEmpty())
    }

    @Test
    fun `a stale pilot hands the point to the flight controller instead of waiting for no answer`() = runTest {
        val link = FakePilotLink(connected = true).apply { fresh = false }
        FlyHereSkill.activate(ctx(link), args)
        assertTrue(link.actions.isEmpty())
        assertEquals(listOf<VehicleCommand>(VehicleCommand.FlyTo(47.1, 8.5, 10f)), vehicle.control.sent)
    }

    @Test
    fun `a stale pilot outside GUIDED sends nothing, matching the offer`() = runTest {
        val link = FakePilotLink(connected = true).apply { fresh = false }
        val result = FlyHereSkill.activate(ctx(link, mode = "LOITER"), args)
        assertEquals(CommandResult.Denied(SkillReason.FLY_HERE_NOT_GUIDED), result)
        assertTrue(link.actions.isEmpty())
        assertTrue(vehicle.control.sent.isEmpty())
    }
}

class FlyHereFcRouteTest {
    private val vehicle = FakeVehicle()

    private fun ctx(mode: String) = SkillContext(
        vehicle, armed = true, mode = com.altnautica.gcs.core.model.FlightMode(0, mode), linkFresh = true,
        checklistReady = true, autonomousNav = true, fcLanded = false,
    )

    @Test
    fun `without the pilot route, Fly Here is offered only while the FC flies GUIDED`() {
        assertEquals(SkillReason.FLY_HERE_NOT_GUIDED, FlyHereSkill.state(ctx("LOITER")).reasonKey)
        assertTrue(FlyHereSkill.state(ctx("GUIDED")).enabled)
    }

    @Test
    fun `an unknown landed state does not hide Fly Here on the FC route`() {
        val c = SkillContext(
            vehicle, armed = true, mode = com.altnautica.gcs.core.model.FlightMode(4, "GUIDED"), linkFresh = true,
            checklistReady = true, autonomousNav = true, fcLanded = null,
        )
        assertTrue(FlyHereSkill.state(c).enabled)
    }
}

class LaunchRangeTest {
    @Test
    fun `take-off altitude range and default are the spec's launch values`() {
        val launch = testSpec.layout.launch!!
        assertEquals(launch.altMinM, com.altnautica.gcs.core.skills.builtin.TakeoffAltitude.MIN_M)
        assertEquals(launch.altMaxM, com.altnautica.gcs.core.skills.builtin.TakeoffAltitude.MAX_M)
        assertEquals(launch.altDefaultM, com.altnautica.gcs.core.skills.builtin.TakeoffAltitude.DEFAULT_M)
    }
}
