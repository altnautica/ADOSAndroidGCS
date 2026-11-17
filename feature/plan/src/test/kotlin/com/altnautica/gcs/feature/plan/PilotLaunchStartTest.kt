package com.altnautica.gcs.feature.plan

import com.altnautica.gcs.core.agent.mission.MirrorItem
import com.altnautica.gcs.core.agent.mission.MissionMirror
import com.altnautica.gcs.core.agent.mission.MissionStatusWire
import com.altnautica.gcs.core.link.mission.PilotPlanView
import com.altnautica.gcs.core.model.PilotAction
import com.altnautica.gcs.core.model.PilotActionResult
import com.altnautica.gcs.core.model.PlaceClass
import com.altnautica.gcs.core.model.VehicleCommand
import com.altnautica.gcs.feature.plan.model.MavCmd
import com.altnautica.gcs.feature.plan.model.MavFrame
import com.altnautica.gcs.feature.plan.model.MissionPlan
import com.altnautica.gcs.feature.plan.model.PlanItem
import com.altnautica.gcs.core.map.GeoPoint
import com.altnautica.gcs.feature.plan.model.PlanHome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Start from the ground on a vehicle with an onboard pilot: the pilot launches into the mission. */
@OptIn(ExperimentalCoroutinesApi::class)
class PilotLaunchStartTest {
    private val h = PlannerHarness()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun wp(seq: Int, lat: Double, lon: Double) =
        MirrorItem(seq = seq, command = 16, frame = 6, x = (lat * 1e7).toInt(), y = (lon * 1e7).toInt(), z = 20f)

    private val mirror = MissionMirror(
        rev = 1,
        count = 3,
        homeSlot = true,
        uploadsSeen = 1,
        mission = MissionStatusWire(state = "loaded", seq = 0, total = 3, id = 7),
        items = listOf(wp(0, 12.0, 77.0), wp(1, 12.001, 77.001), wp(2, 12.002, 77.002)),
    )

    private fun TestScope.loadAndStart(vm: PlannerViewModel) {
        h.connect(vm)
        h.mirror.value = mirror
        runCurrent()
        vm.loadFromDrone()
        runCurrent()
        vm.openStartMission()
        vm.startMission()
        advanceTimeBy(10_000)
        runCurrent()
    }

    @Test
    fun `on the ground with a pilot, Start sends launch into the mission and never arms or starts on the FC`() = runTest {
        h.withViewModel { vm ->
            h.pilot.value = PilotPlanView(placeClass = PlaceClass.Open, tsMs = 1_000, ageMs = 0, present = true)
            loadAndStart(vm)
            assertEquals(listOf(PilotAction(PilotAction.LAUNCH, mapOf("after" to "mission", "alt_m" to 3f))), h.pilotActions)
            assertTrue(h.control.commands.isEmpty())
            assertEquals(0, h.mission.started)
            assertNull(vm.state.value.startSheet)
        }
    }

    @Test
    fun `a refused launch stays on the sheet with the pilot's reason`() = runTest {
        h.withViewModel { vm ->
            h.pilot.value = PilotPlanView(placeClass = PlaceClass.Open, tsMs = 1_000, ageMs = 0, present = true)
            h.actionAnswer = PilotActionResult.Refused("liftoff_no_trim")
            loadAndStart(vm)
            assertEquals(StartError.LaunchRefused(PilotActionResult.Refused("liftoff_no_trim")), vm.state.value.startSheet?.error)
            assertTrue(h.control.commands.isEmpty())
        }
    }

    @Test
    fun `without a pilot, Start arms and starts the mission on the FC`() = runTest {
        h.withViewModel { vm ->
            loadAndStart(vm)
            assertTrue(h.pilotActions.isEmpty())
            assertEquals(listOf<VehicleCommand>(VehicleCommand.Arm()), h.control.commands)
            assertEquals(1, h.mission.started)
        }
    }

    @Test
    fun `with a stale pilot frame, Start arms and starts the mission on the FC`() = runTest {
        h.withViewModel { vm ->
            h.pilot.value = PilotPlanView(placeClass = PlaceClass.Open, tsMs = 1_000, ageMs = 30_000, present = true)
            loadAndStart(vm)
            assertTrue(h.pilotActions.isEmpty())
            assertEquals(listOf<VehicleCommand>(VehicleCommand.Arm()), h.control.commands)
            assertEquals(1, h.mission.started)
        }
    }

    @Test
    fun `the launch climbs to the plan's take-off altitude, clamped, else the default`() {
        fun plan(z: Float?) = MissionPlan(items = listOfNotNull(z?.let { PlanItem(1, MavCmd.NAV_TAKEOFF, MavFrame.GLOBAL_RELATIVE_ALT, z = it) }))
        assertEquals(15f, launchAltitudeM(plan(15f)))
        assertEquals(120f, launchAltitudeM(plan(500f)))
        assertEquals(3f, launchAltitudeM(plan(0f)))
        assertEquals(3f, launchAltitudeM(plan(null)))
    }

    @Test
    fun `a sea-level take-off item climbs above home, else the default`() {
        val item = PlanItem(1, MavCmd.NAV_TAKEOFF, MavFrame.GLOBAL, z = 920f)
        assertEquals(3f, launchAltitudeM(MissionPlan(items = listOf(item))))
        val home = PlanHome(GeoPoint(0.0, 0.0), altMslM = 910f)
        assertEquals(10f, launchAltitudeM(MissionPlan(items = listOf(item), home = home)))
        assertEquals(7f, launchAltitudeM(MissionPlan(items = listOf(item.copy(frame = MavFrame.GLOBAL_TERRAIN_ALT, z = 7f)))))
    }
}
