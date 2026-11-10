package com.altnautica.gcs.core.skills

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

    private fun ctx(link: FakePilotLink) = SkillContext(
        vehicle, armed = true, mode = null, linkFresh = true, checklistReady = true, autonomousNav = true,
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
