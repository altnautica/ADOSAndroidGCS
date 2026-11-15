package com.altnautica.gcs.feature.plan

import com.altnautica.gcs.core.agent.http.AgentError
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.agent.mission.MirrorItem
import com.altnautica.gcs.core.agent.mission.MissionMirror
import com.altnautica.gcs.core.agent.mission.MissionStatusWire
import com.altnautica.gcs.core.link.mission.MissionItem
import com.altnautica.gcs.core.link.mission.MissionType
import com.altnautica.gcs.core.agent.model.CommandResponse
import com.altnautica.gcs.core.link.mission.AgentVehiclePilotSource
import com.altnautica.gcs.core.link.mission.PilotPlanView
import com.altnautica.gcs.core.map.GeoPoint
import com.altnautica.gcs.core.model.PilotActionResult
import com.altnautica.gcs.core.model.PlaceClass
import com.altnautica.gcs.core.model.RouteCheck
import com.altnautica.gcs.core.model.RouteCheckState
import com.altnautica.gcs.core.model.VehicleCommand
import com.altnautica.gcs.feature.plan.model.MavCmd
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import com.altnautica.gcs.core.link.mission.MissionPlan as WirePlan

/** Start and the vehicle's place class: a plan from the vehicle keeps the pilot's class, and the class set on Start is read back. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaceClassStartTest {
    private val h = PlannerHarness()
    private val a = GeoPoint(12.0, 77.0)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun check(rc: RouteCheckState) = RouteCheck(rc, 0.577, 0.477, 0.02, null, emptyList())

    /** The pilot as a fresh navigation status reports it (a frame 0 ms old). */
    private fun view(c: PlaceClass, rc: RouteCheck? = null) = PilotPlanView(placeClass = c, routeCheck = rc, tsMs = 1_000, ageMs = 0)

    private fun storeVehicleMission() {
        h.mission.home = MissionItem.global(MavCmd.NAV_WAYPOINT, 12.0, 77.0, 910f, frame = 5)
        h.mission.stored[MissionType.Mission] = WirePlan(
            h.mission.home,
            listOf(MissionItem.global(MavCmd.NAV_WAYPOINT, 12.001, 77.0, 25f), MissionItem.global(MavCmd.NAV_WAYPOINT, 12.002, 77.001, 25f)),
        )
    }

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

    /** Opens the Start sheet and slides it, letting the read-back run its course. */
    private fun TestScope.start(vm: PlannerViewModel) {
        vm.openStartMission()
        vm.startMission()
        advanceTimeBy(10_000)
        runCurrent()
    }

    @Test
    fun `a plan downloaded from a confined pilot stays confined and Start sends no class`() = runTest {
        h.withViewModel { vm ->
            h.pilot.value = view(PlaceClass.Confined, check(RouteCheckState.Feasible))
            h.connect(vm)
            storeVehicleMission()
            vm.download()
            assertEquals(PlaceClass.Confined, vm.state.value.plan.placeClass)
            vm.openStartMission()
            assertFalse(vm.state.value.startSheet!!.setsPlaceClass)
            start(vm)
            assertTrue("no class change the operator did not choose", h.placeClassesSent.isEmpty())
            assertEquals(listOf<VehicleCommand>(VehicleCommand.Arm()), h.control.commands)
        }
    }

    @Test
    fun `Load from drone takes the pilot's class`() = runTest {
        h.withViewModel { vm ->
            h.pilot.value = view(PlaceClass.Confined, check(RouteCheckState.Feasible))
            h.connect(vm)
            h.mirror.value = mirror
            runCurrent()
            vm.loadFromDrone()
            vm.openStartMission()
            assertFalse(vm.state.value.startSheet!!.setsPlaceClass)
            assertEquals(PlaceClass.Confined, vm.state.value.plan.placeClass)
        }
    }

    @Test
    fun `a plan loaded while the pilot's class is unknown leaves it unset, so Start never sends Open`() = runTest {
        h.withViewModel { vm ->
            h.connect(vm)
            h.mirror.value = mirror
            runCurrent()
            vm.loadFromDrone()
            runCurrent()
            assertNull(vm.state.value.plan.placeClass)

            h.pilot.value = view(PlaceClass.Confined, check(RouteCheckState.Feasible))
            runCurrent()
            vm.openStartMission()
            assertFalse(vm.state.value.startSheet!!.setsPlaceClass)
            start(vm)
            assertTrue(h.placeClassesSent.isEmpty())
            assertEquals(listOf<VehicleCommand>(VehicleCommand.Arm()), h.control.commands)
        }
    }

    @Test
    fun `Start reads the class back until a newer frame reports it`() = runTest {
        h.withViewModel { vm ->
            h.pilot.value = view(PlaceClass.Open)
            h.placeClassReadLag = 2
            h.routeCheckAfterConfined = check(RouteCheckState.Feasible)
            h.connect(vm)
            vm.onMapTap(a)
            vm.setPlaceClass(PlaceClass.Confined)
            vm.upload()
            start(vm)
            assertEquals(listOf(PlaceClass.Confined), h.placeClassesSent)
            assertEquals(listOf<VehicleCommand>(VehicleCommand.Arm()), h.control.commands)
            assertNull(vm.state.value.startSheet)
        }
    }

    @Test
    fun `a confined Start the pilot has not route-checked stops with nothing armed`() = runTest {
        h.withViewModel { vm ->
            h.pilot.value = view(PlaceClass.Open)
            h.routeCheckAfterConfined = null
            h.connect(vm)
            vm.onMapTap(a)
            vm.setPlaceClass(PlaceClass.Confined)
            vm.upload()
            start(vm)
            assertEquals(listOf(PlaceClass.Confined), h.placeClassesSent)
            assertNotNull(vm.state.value.startSheet?.error)
            assertTrue(h.control.commands.isEmpty())
        }
    }

    @Test
    fun `a pilot already confined must have route-checked the mission before Start`() = runTest {
        h.withViewModel { vm ->
            h.pilot.value = view(PlaceClass.Confined)
            h.connect(vm)
            vm.onMapTap(a)
            vm.setPlaceClass(PlaceClass.Confined)
            vm.upload()
            vm.openStartMission()
            val sheet = vm.state.value.startSheet!!
            assertFalse(sheet.checks.single { it.kind == StartCheckKind.RouteCheck }.ok)
            assertFalse(sheet.canSlide)
        }
    }

    @Test
    fun `a stale pilot stops Start with its own reason before anything is sent`() = runTest {
        h.withViewModel { vm ->
            h.pilot.value = view(PlaceClass.Open)
            h.connect(vm)
            vm.onMapTap(a)
            vm.setPlaceClass(PlaceClass.Confined)
            vm.upload()
            vm.openStartMission()
            // The pilot's frames stopped: the agent's status serves the last one, ever older.
            h.pilot.value = h.pilot.value.copy(ageMs = 6_000)
            start(vm)
            val error = vm.state.value.startSheet?.error as StartError.PlaceClassRefused
            assertEquals("pilot_stale", placeClassRefusalCode(error.result))
            assertTrue(h.placeClassesSent.isEmpty())
            assertTrue(h.control.commands.isEmpty())
        }
    }

    @Test
    fun `an agent answer that never reached the pilot reads as what happened`() {
        val err = { e: AgentError -> AgentVehiclePilotSource.commandAnswer(AgentResult.Err(e)) }
        assertEquals(PilotActionResult.Accepted, AgentVehiclePilotSource.commandAnswer(AgentResult.Ok(CommandResponse(status = "ok"))))
        assertEquals(
            PilotActionResult.Refused("armed"),
            AgentVehiclePilotSource.commandAnswer(AgentResult.Ok(CommandResponse(status = "error", message = "pilot refused place_class:confined: armed"))),
        )
        assertEquals(PilotActionResult.NoAnswer, err(AgentError.Network(java.io.IOException("reset"))))
        assertEquals(PilotActionResult.Unavailable, err(AgentError.Server(503, "ADOS pilot not reachable")))
        // An agent that does not route the verb to the pilot answers 400 or 404.
        assertEquals(PilotActionResult.Refused("unknown_action"), err(AgentError.Server(400, "unknown command")))
        assertEquals(PilotActionResult.Refused("unknown_action"), err(AgentError.FeatureAbsent))
        assertEquals(PilotActionResult.Refused("not_authorized"), err(AgentError.Unauthorized))
    }

    @Test
    fun `the planner's view carries the pilot frame's time and age from the navigation status`() {
        val status = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"pilot":{"ts_ms":5000,"age_ms":120,"data":{"owner":"idle","place_class":"confined"}}}""",
        ) as kotlinx.serialization.json.JsonObject
        val v = AgentVehiclePilotSource.viewOf(status)
        assertEquals(PlaceClass.Confined, v.placeClass)
        assertEquals(5_000L, v.tsMs)
        assertEquals(120L, v.ageMs)
    }
}
