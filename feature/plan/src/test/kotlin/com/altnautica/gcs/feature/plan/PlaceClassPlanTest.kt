package com.altnautica.gcs.feature.plan

import com.altnautica.gcs.core.link.mission.PilotPlanView
import com.altnautica.gcs.core.map.GeoPoint
import com.altnautica.gcs.core.model.PilotActionResult
import com.altnautica.gcs.core.model.PlaceClass
import com.altnautica.gcs.core.model.RouteCheck
import com.altnautica.gcs.core.model.RouteCheckState
import com.altnautica.gcs.core.model.VehicleCommand
import com.altnautica.gcs.feature.plan.io.PlanFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The plan's place class: saved with the plan, set on the vehicle's pilot at Start, gated by its route check. */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaceClassPlanTest {
    private val h = PlannerHarness()
    private val a = GeoPoint(12.0, 77.0)
    private val b = GeoPoint(12.001, 77.0)

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun check(rc: RouteCheckState) = RouteCheck(rc, 0.577, 0.477, 0.02, null, emptyList())

    @Test
    fun `a confined plan round-trips its place class through the saved plan file, an open one writes nothing`() {
        val confined = com.altnautica.gcs.feature.plan.model.MissionPlan(placeClass = PlaceClass.Confined)
        val text = PlanFiles.exportPlan(confined)
        assertEquals(text, "confined", (kotlinx.serialization.json.Json.parseToJsonElement(text) as kotlinx.serialization.json.JsonObject)["mission"].toString().let { Regex("\"placeClass\":\"(\\w+)\"").find(it)?.groupValues?.get(1) })
        assertEquals(PlaceClass.Confined, PlanFiles.parsePlan(text) { 1L }.plan.placeClass)
        val open = PlanFiles.exportPlan(com.altnautica.gcs.feature.plan.model.MissionPlan())
        assertFalse(open, open.contains("placeClass"))
        assertEquals(PlaceClass.Open, PlanFiles.parsePlan(open) { 1L }.plan.placeClass)
    }

    @Test
    fun `the picker's class is kept in the plan and Start sets it on the pilot before arming`() = runTest {
        h.withViewModel { vm ->
            h.pilot.value = PilotPlanView(placeClass = PlaceClass.Open, ageMs = 0)
            h.routeCheckAfterConfined = check(RouteCheckState.Feasible)
            h.connect(vm, armed = false)
            vm.onMapTap(a)
            vm.onMapTap(b)
            vm.setPlaceClass(PlaceClass.Confined)
            assertEquals(PlaceClass.Confined, vm.state.value.plan.placeClass)
            assertTrue(vm.state.value.dirty)
            vm.upload()
            vm.openStartMission()
            val sheet = vm.state.value.startSheet!!
            assertTrue(sheet.setsPlaceClass)
            assertTrue(sheet.checks.single { it.kind == StartCheckKind.PlaceClass }.ok)
            assertTrue(sheet.canSlide)

            vm.startMission()

            assertEquals(listOf(PlaceClass.Confined), h.placeClassesSent)
            assertEquals(listOf<VehicleCommand>(VehicleCommand.Arm()), h.control.commands)
            assertNull(vm.state.value.startSheet)
        }
    }

    @Test
    fun `a refused place class stops the start with nothing armed`() = runTest {
        h.withViewModel { vm ->
            h.pilot.value = PilotPlanView(placeClass = PlaceClass.Open, ageMs = 0)
            h.placeClassAnswer = PilotActionResult.Refused("armed")
            h.connect(vm, armed = false)
            vm.onMapTap(a)
            vm.setPlaceClass(PlaceClass.Confined)
            vm.upload()
            vm.openStartMission()
            vm.startMission()

            assertEquals(StartError.PlaceClassRefused(PilotActionResult.Refused("armed")), vm.state.value.startSheet?.error)
            assertTrue(h.control.commands.isEmpty())
            assertEquals("armed", placeClassRefusalCode(PilotActionResult.Refused("armed")))
        }
    }

    @Test
    fun `an infeasible route check blocks a confined start, and a confined plan needs a pilot`() = runTest {
        h.withViewModel { vm ->
            h.pilot.value = PilotPlanView(placeClass = PlaceClass.Confined, routeCheck = check(RouteCheckState.Infeasible), ageMs = 0)
            h.connect(vm, armed = false)
            vm.onMapTap(a)
            vm.setPlaceClass(PlaceClass.Confined)
            vm.upload()
            vm.openStartMission()
            var sheet = vm.state.value.startSheet!!
            assertFalse(sheet.checks.single { it.kind == StartCheckKind.RouteCheck }.ok)
            assertFalse(sheet.canSlide)
            assertEquals(RouteCheckState.Infeasible, sheet.routeCheck?.state)

            h.pilot.value = PilotPlanView(placeClass = PlaceClass.Confined, routeCheck = check(RouteCheckState.Unverified), ageMs = 0)
            sheet = vm.state.value.startSheet!!
            assertTrue(sheet.checks.single { it.kind == StartCheckKind.RouteCheck }.ok)

            h.pilot.value = PilotPlanView.None
            sheet = vm.state.value.startSheet!!
            assertFalse("no pilot can fly the confined profile", sheet.checks.single { it.kind == StartCheckKind.PlaceClass }.ok)
        }
    }
}
