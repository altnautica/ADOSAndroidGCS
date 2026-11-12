package com.altnautica.gcs.feature.cockpit

import com.altnautica.gcs.core.map.GeoPoint
import com.altnautica.gcs.core.model.ConfirmGesture
import com.altnautica.gcs.core.model.LocalPosition
import com.altnautica.gcs.core.model.PositionFix
import com.altnautica.gcs.core.model.VehicleCommand
import com.altnautica.gcs.core.skills.SkillNotice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
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

/** A map long-press in the air flies to the point at a height inside the agent's bounds, or says what it lacks. */
@OptIn(ExperimentalCoroutinesApi::class)
class FlyHereMapTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a point picked a metre above home flies at the 2 m floor`() = runTest(dispatcher) {
        val vehicle = FakeVehicle()
        vehicle.heartbeatAt(testScheduler.currentTime, armed = true, mode = "GUIDED")
        vehicle.telemetry.position.value = PositionFix(47.0, 8.0, 401f, 1f, 0f, 0f, 0f, testScheduler.currentTime)
        val vm = testCockpitVm(vehicle)
        advanceTimeBy(600)
        runCurrent()
        vm.pickMapPoint(GeoPoint(47.001, 8.002))
        runCurrent()
        vm.confirm.onTouchHold(true)
        advanceTimeBy(1_000)
        runCurrent()
        vm.confirm.onTouchHold(false)
        runCurrent()
        assertEquals(listOf<VehicleCommand>(VehicleCommand.FlyTo(47.001, 8.002, 2f)), vehicle.control.sent)
    }

    @Test
    fun `a point picked without a position says Fly Here needs one`() = runTest(dispatcher) {
        val vehicle = FakeVehicle()
        vehicle.heartbeatAt(testScheduler.currentTime, armed = true, mode = "GUIDED")
        val vm = testCockpitVm(vehicle)
        val notices = mutableListOf<SkillNotice>()
        backgroundScope.launch { vm.notices.collect { notices += it } }
        advanceTimeBy(600)
        runCurrent()
        vm.pickMapPoint(GeoPoint(47.001, 8.002))
        runCurrent()
        assertNull("no confirm opens", vm.confirm.state.value)
        assertTrue(notices.toString(), notices.any { it.message == testSpec.setupStrings.format("map.fly_here.needs_position") })
    }

    @Test
    fun `a long-press while the flight controller is not in GUIDED says why and opens no confirm`() = runTest(dispatcher) {
        val vehicle = FakeVehicle()
        vehicle.heartbeatAt(testScheduler.currentTime, armed = true, mode = "LOITER")
        vehicle.telemetry.position.value = PositionFix(47.0, 8.0, 412f, 12f, 0f, 0f, 0f, testScheduler.currentTime)
        val vm = testCockpitVm(vehicle)
        val notices = mutableListOf<SkillNotice>()
        backgroundScope.launch { vm.notices.collect { notices += it } }
        advanceTimeBy(600)
        runCurrent()
        vm.pickMapPoint(GeoPoint(47.001, 8.002))
        runCurrent()
        assertNull("no confirm opens", vm.confirm.state.value)
        assertTrue(notices.toString(), notices.any { it.message == testSpec.setupStrings.format("refusal.not_guided") })
        assertTrue(vehicle.control.sent.isEmpty())
    }

    @Test
    fun `a long-press on the local minimap with a fresh pilot and an anchored origin opens the Fly Here hold`() = runTest(dispatcher) {
        val vehicle = FakeVehicle()
        vehicle.heartbeatAt(testScheduler.currentTime, armed = true, mode = "GUIDED")
        vehicle.telemetry.position.value = PositionFix(47.0, 8.0, 412f, 12f, 0f, 0f, 0f, testScheduler.currentTime)
        vehicle.telemetry.localPosition.value = LocalPosition(3f, -2f, -12f, 0f, 0f, 0f, testScheduler.currentTime)
        val agent = FakeAutonomyAgent(this, """{"owner":"pilot","control_mode":"ados","armed":true,"landed":false,"surface_ops":["goto"]}""")
        val vm = testCockpitVm(vehicle, agent.apis)
        advanceTimeBy(600)
        runCurrent()
        vm.pickLocalPoint(northM = 13f, eastM = 8f)
        runCurrent()
        assertEquals(ConfirmGesture.Hold, vm.confirm.state.value?.gesture)
        // The point is 10 m north and 10 m east of the vehicle.
        val origin = localOrigin(vehicle.telemetry)!!
        val point = localToGlobal(origin, 13f, 8f)
        assertEquals(47.0 + 10 / 111_319.49, point.latDeg, 1e-7)
    }

    @Test
    fun `a long-press on the local minimap without an anchored origin says Fly Here needs a position`() = runTest(dispatcher) {
        val vehicle = FakeVehicle()
        vehicle.heartbeatAt(testScheduler.currentTime, armed = true, mode = "GUIDED")
        vehicle.telemetry.localPosition.value = LocalPosition(3f, -2f, -12f, 0f, 0f, 0f, testScheduler.currentTime)
        val vm = testCockpitVm(vehicle)
        val notices = mutableListOf<SkillNotice>()
        backgroundScope.launch { vm.notices.collect { notices += it } }
        advanceTimeBy(600)
        runCurrent()
        vm.pickLocalPoint(northM = 13f, eastM = 8f)
        runCurrent()
        assertNull(vm.confirm.state.value)
        assertTrue(notices.any { it.message == testSpec.setupStrings.format("map.fly_here.needs_position") })
    }
}
