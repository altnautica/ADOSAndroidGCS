package com.altnautica.gcs.core.skills

import com.altnautica.gcs.core.model.FlightMode
import com.altnautica.gcs.core.model.HeartbeatState
import com.altnautica.gcs.core.model.VehicleCommand
import com.altnautica.gcs.core.skills.builtin.ModePreset
import com.altnautica.gcs.core.skills.builtin.VehicleClass
import com.altnautica.gcs.core.skills.builtin.vehicleClass
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ModePresetTest {
    private val planeModes = listOf(
        0 to "MANUAL", 2 to "STABILIZE", 5 to "FBWA", 6 to "FBWB", 7 to "CRUISE", 10 to "AUTO", 11 to "RTL",
        12 to "LOITER", 15 to "GUIDED", 17 to "QSTABILIZE", 18 to "QHOVER", 19 to "QLOITER", 20 to "QLAND", 21 to "QRTL",
    ).map { FlightMode(it.first.toLong(), it.second) }

    @Test
    fun `presets resolve per vehicle class`() {
        assertEquals("LOITER", ModePreset.Loiter.resolve(planeModes, VehicleClass.FixedWing)?.name)
        assertEquals("QLOITER", ModePreset.Loiter.resolve(planeModes, VehicleClass.Vtol)?.name)
        assertEquals("FBWB", ModePreset.AltHold.resolve(planeModes, VehicleClass.FixedWing)?.name)
        assertEquals("QHOVER", ModePreset.AltHold.resolve(planeModes, VehicleClass.Vtol)?.name)
        assertEquals("QSTABILIZE", ModePreset.Stabilize.resolve(planeModes, VehicleClass.Vtol)?.name)
        assertNull(ModePreset.Brake.resolve(planeModes, VehicleClass.FixedWing))
        val copter = listOf(FlightMode(5, "LOITER"), FlightMode(17, "BRAKE"), FlightMode(2, "ALT_HOLD"))
        assertEquals("LOITER", ModePreset.Loiter.resolve(copter, VehicleClass.Multirotor)?.name)
        assertEquals("BRAKE", ModePreset.Brake.resolve(copter, VehicleClass.Multirotor)?.name)
    }

    @Test
    fun `a QuadPlane reporting fixed wing is a VTOL while it hovers`() {
        assertEquals(VehicleClass.FixedWing, vehicleClass(1, "FBWA"))
        assertEquals(VehicleClass.Vtol, vehicleClass(1, "QHOVER"))
        assertEquals(VehicleClass.Vtol, vehicleClass(20, "FBWA"))
        assertEquals(VehicleClass.Multirotor, vehicleClass(2, "STABILIZE"))
        assertEquals(VehicleClass.Unknown, vehicleClass(null, null))
    }

    private fun TestScope.planeSession(mode: String): Pair<FakeVehicle, SkillSession> {
        advanceTimeBy(10_000)
        val vehicle = FakeVehicle()
        vehicle.control.availableModes.value = planeModes
        vehicle.telemetry.heartbeat.value =
            HeartbeatState(false, FlightMode(0, mode), vehicleType = 1, autopilot = 3, tMs = testScheduler.currentTime)
        val clock = MonotonicClock { testScheduler.currentTime }
        val session = SkillSession(MutableStateFlow(vehicle), MutableStateFlow(true), SkillRegistry(emptySet()), SkillActivator(clock), clock, backgroundScope)
        runCurrent()
        return vehicle to session
    }

    @Test
    fun `on a plane the bar hides Brake and Loiter flies LOITER at a tap`() = runTest {
        val (vehicle, s) = planeSession("MANUAL")
        val bar = s.barSkills.value.map { it.id }
        assertFalse("mode.brake" in bar)
        assertTrue("mode.loiter" in bar)
        assertTrue(s.states.value.getValue("mode.loiter").enabled)
        s.activate("mode.loiter", emptyMap()) { error("Loiter is a tap-tier mode and needs no sheet") }
        runCurrent()
        assertEquals(listOf(VehicleCommand.SetMode(FlightMode(12, "LOITER"))), vehicle.control.sent)
    }

    @Test
    fun `a hovering QuadPlane holds in QLOITER`() = runTest {
        val (vehicle, s) = planeSession("QHOVER")
        s.activate("mode.loiter", emptyMap()) { error("tap tier") }
        runCurrent()
        assertEquals(listOf(VehicleCommand.SetMode(FlightMode(19, "QLOITER"))), vehicle.control.sent)
    }

    @Test
    fun `set-mode sends the picked mode the vehicle advertises`() = runTest {
        val (vehicle, s) = planeSession("MANUAL")
        s.activate("set-mode", mapOf(com.altnautica.gcs.core.skills.builtin.SkillArgs.TARGET_MODE to "QLOITER")) { error("tap tier") }
        runCurrent()
        assertEquals(listOf(VehicleCommand.SetMode(FlightMode(19, "QLOITER"))), vehicle.control.sent)
    }

    @Test
    fun `presets stay listed until the mode list is known`() = runTest {
        val (vehicle, s) = planeSession("MANUAL")
        vehicle.control.availableModes.value = emptyList()
        runCurrent()
        assertTrue("mode.brake" in s.barSkills.value.map { it.id })
        assertEquals(SkillReason.MODE_UNAVAILABLE, s.states.value.getValue("mode.brake").reasonKey)
    }

    @Test
    fun `heartbeat age is never negative`() {
        assertEquals(0L, heartbeatAgeMs(tMs = 10_300, nowMs = 10_000))
        assertEquals(250L, heartbeatAgeMs(tMs = 10_000, nowMs = 10_250))
        assertTrue(isLinkFresh(HeartbeatState(false, FlightMode(0, "MANUAL"), 1, 3, tMs = 10_300), nowMs = 10_000))
    }
}
