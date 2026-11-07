package com.altnautica.gcs.feature.cockpit.manual

import com.altnautica.gcs.core.data.controller.ControllerSettings
import com.altnautica.gcs.core.link.manual.ManualControlInput
import com.altnautica.gcs.core.link.manual.ManualControlStreamer
import com.altnautica.gcs.core.link.services.VehicleServices
import com.altnautica.gcs.core.model.AlertLevel
import com.altnautica.gcs.core.model.FlightMode
import com.altnautica.gcs.core.model.HeartbeatState
import com.altnautica.gcs.core.model.Vehicle
import com.altnautica.gcs.core.skills.binding.Ds4Bindings
import com.altnautica.gcs.feature.cockpit.pilot.StickRoute
import com.altnautica.gcs.feature.cockpit.testSpec
import com.altnautica.gcs.core.skills.builtin.VehicleClass
import com.altnautica.gcs.core.skills.input.AdosInputRouter
import com.altnautica.gcs.core.skills.input.GamepadAxes
import com.altnautica.gcs.core.skills.input.GamepadButton
import com.altnautica.gcs.feature.cockpit.FakeVehicle
import com.altnautica.gcs.feature.cockpit.StickChip
import com.altnautica.gcs.feature.cockpit.alerts.AlertId
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CockpitManualControlTest {
    private class RecordingStreamer : ManualControlStreamer {
        val calls = mutableListOf<String>()
        var input: StateFlow<ManualControlInput>? = null
        override val enabled = MutableStateFlow(false)
        override fun start(input: StateFlow<ManualControlInput>) {
            calls += "start"
            this.input = input
            enabled.value = true
        }
        override fun stop() {
            calls += "stop"
            enabled.value = false
        }
    }

    private class Rig(
        val control: CockpitManualControl,
        val vehicle: FakeVehicle,
        val gamepad: MutableStateFlow<Boolean>,
        val axes: MutableStateFlow<GamepadAxes>,
        val buttons: MutableStateFlow<Int>,
        val settings: MutableStateFlow<ControllerSettings>,
        val streamer: RecordingStreamer,
        val off: MutableList<StickOff>,
        val route: MutableStateFlow<StickRoute>,
    )

    private fun FakeVehicle.beat(mode: String, armed: Boolean = true, type: Int = 2) {
        telemetry.heartbeat.value = HeartbeatState(armed, FlightMode(0, mode), vehicleType = type, autopilot = 3, tMs = 0)
    }

    private fun CoroutineScope.rig(mode: String = "LOITER", settings: ControllerSettings = ControllerSettings()): Rig {
        val gamepad = MutableStateFlow(true)
        val axes = MutableStateFlow(GamepadAxes.Neutral)
        val buttons = MutableStateFlow(0)
        val router = mockk<AdosInputRouter>(relaxed = true) {
            every { gamepadConnected } returns gamepad
            every { this@mockk.axes } returns axes
            every { this@mockk.buttons } returns buttons
        }
        val vehicle = FakeVehicle().apply { beat(mode) }
        val streamer = RecordingStreamer()
        val services = mockk<VehicleServices>(relaxed = true) { every { manualControl } returns streamer }
        val settingsFlow = MutableStateFlow(settings)
        val route = MutableStateFlow(StickRoute.FcManual)
        val control = CockpitManualControl(
            scope = this,
            vehicle = MutableStateFlow<Vehicle?>(vehicle),
            vehicleServices = MutableStateFlow<VehicleServices?>(services),
            router = router,
            controllerSettings = settingsFlow,
            boundButtons = MutableStateFlow(Ds4Bindings.boundButtonMask(testSpec.ds4, testSpec.ds4.bindings)),
            route = route,
            pilotStreaming = MutableStateFlow(false),
        )
        val off = mutableListOf<StickOff>()
        launch { control.off.collect { off += it } }
        return Rig(control, vehicle, gamepad, axes, buttons, settingsFlow, streamer, off, route)
    }

    @Test
    fun `in ADOS Pilot control the chip names the pilot and manual control never starts`() = runTest(UnconfinedTestDispatcher()) {
        val r = backgroundScope.rig()
        r.route.value = StickRoute.Pilot
        runCurrent()
        assertEquals(StickChip.Pilot(false), r.control.stick.value)
        r.control.toggle()
        runCurrent()
        assertTrue(r.streamer.calls.none { it == "start" })
    }

    @Test
    fun `stick control stays off after the controller reconnects`() = runTest(UnconfinedTestDispatcher()) {
        val r = backgroundScope.rig()
        runCurrent()
        r.control.toggle()
        runCurrent()
        assertEquals(StickChip.Available(on = true), r.control.stick.value)
        assertEquals("start", r.streamer.calls.last())

        r.gamepad.value = false
        runCurrent()
        r.gamepad.value = true
        runCurrent()

        assertEquals(StickChip.Available(on = false), r.control.stick.value)
        assertEquals("stop", r.streamer.calls.last())
        assertEquals(listOf<StickOff>(StickOff.ControllerLost(armed = true)), r.off)
    }

    @Test
    fun `losing the controller while disarmed is not critical`() = runTest(UnconfinedTestDispatcher()) {
        val r = backgroundScope.rig()
        r.vehicle.beat("LOITER", armed = false)
        runCurrent()
        r.control.toggle()
        runCurrent()
        r.gamepad.value = false
        runCurrent()
        val alert = r.off.single().toAlert(nowMs = 5)
        assertEquals(AlertId.CONTROLLER_LOST, alert.id)
        assertEquals(AlertLevel.Warning, alert.level)
        assertEquals(AlertLevel.Critical, StickOff.ControllerLost(armed = true).toAlert(0).level)
    }

    @Test
    fun `an assisted mode makes stick control available`() = runTest(UnconfinedTestDispatcher()) {
        val r = backgroundScope.rig(mode = "ALT_HOLD")
        runCurrent()
        assertEquals(StickChip.Available(on = false), r.control.stick.value)
    }

    @Test
    fun `a manual mode blocks stick control and toggling does nothing`() = runTest(UnconfinedTestDispatcher()) {
        val r = backgroundScope.rig(mode = "STABILIZE")
        runCurrent()
        assertEquals(StickChip.ModeBlocked("STABILIZE"), r.control.stick.value)
        r.control.toggle()
        runCurrent()
        assertFalse(r.streamer.enabled.value)
    }

    @Test
    fun `leaving an assisted mode turns stick control off with an alert and returning does not resume it`() =
        runTest(UnconfinedTestDispatcher()) {
            val r = backgroundScope.rig(mode = "LOITER")
            runCurrent()
            r.control.toggle()
            runCurrent()
            assertTrue(r.streamer.enabled.value)

            r.vehicle.beat("STABILIZE")
            runCurrent()
            assertFalse(r.streamer.enabled.value)
            assertEquals(StickChip.ModeBlocked("STABILIZE"), r.control.stick.value)
            assertEquals(listOf<StickOff>(StickOff.ModeLeft("STABILIZE")), r.off)
            val alert = r.off.single().toAlert(0)
            assertEquals(AlertId.STICK_MODE, alert.id)
            assertEquals(AlertLevel.Warning, alert.level)
            assertEquals("STABILIZE", alert.detail)

            r.vehicle.beat("LOITER")
            runCurrent()
            assertEquals(StickChip.Available(on = false), r.control.stick.value)
            assertFalse(r.streamer.enabled.value)
        }

    @Test
    fun `a vehicle opted in to all modes flies the sticks in stabilize`() = runTest(UnconfinedTestDispatcher()) {
        val r = backgroundScope.rig(mode = "STABILIZE", settings = ControllerSettings(allModesVehicles = setOf("drone-1")))
        runCurrent()
        assertEquals(StickChip.Available(on = false), r.control.stick.value)
        r.control.toggle()
        runCurrent()
        assertTrue(r.streamer.enabled.value)
        assertTrue(r.control.allModes())

        // Withdrawing the opt-in in a manual mode turns stick control off.
        r.settings.value = ControllerSettings()
        runCurrent()
        assertFalse(r.streamer.enabled.value)
        assertEquals(listOf<StickOff>(StickOff.ModeLeft("STABILIZE")), r.off)
    }

    @Test
    fun `skill buttons never reach the flight controller`() = runTest(UnconfinedTestDispatcher()) {
        val r = backgroundScope.rig()
        runCurrent()
        r.control.toggle()
        runCurrent()
        r.buttons.value = (1 shl GamepadButton.A) or (1 shl GamepadButton.L1) or (1 shl GamepadButton.R2)
        runCurrent()
        assertEquals(0, r.streamer.input!!.value.buttons)
    }

    @Test
    fun `expo softens pitch, roll and yaw but not throttle`() = runTest(UnconfinedTestDispatcher()) {
        val r = backgroundScope.rig(settings = ControllerSettings(expoPct = 60))
        r.axes.value = GamepadAxes(leftX = 0.5f, leftY = 0.5f, rightX = 0.5f, rightY = -1f)
        runCurrent()
        r.control.toggle()
        runCurrent()
        val input = r.streamer.input!!.value
        // 0.4 * 0.5 + 0.6 * 0.125 = 0.275; full deflection stays full.
        assertEquals(0.275f, input.roll, 1e-5f)
        assertEquals(0.275f, input.yaw, 1e-5f)
        assertEquals(-1f, input.pitch, 1e-5f)
        assertEquals(0.75f, input.throttle, 1e-5f)
    }

    @Test
    fun `assisted modes follow the airframe class`() {
        assertTrue(StickPolicy.allowed("loiter", VehicleClass.Multirotor, allModes = false))
        assertFalse(StickPolicy.allowed("STABILIZE", VehicleClass.Multirotor, allModes = false))
        assertTrue(StickPolicy.allowed("STABILIZE", VehicleClass.Multirotor, allModes = true))
        assertTrue(StickPolicy.allowed("FBWB", VehicleClass.FixedWing, allModes = false))
        assertFalse(StickPolicy.allowed("FBWA", VehicleClass.FixedWing, allModes = false))
        assertTrue(StickPolicy.allowed("QHOVER", VehicleClass.Vtol, allModes = false))
        assertTrue(StickPolicy.allowed("STEERING", VehicleClass.Rover, allModes = false))
        assertTrue(StickPolicy.allowed("SURFTRAK", VehicleClass.Sub, allModes = false))
        assertFalse(StickPolicy.allowed(null, VehicleClass.Unknown, allModes = false))
    }

    @Test
    fun `a low controller battery warns only while the sticks fly`() {
        assertTrue(controllerBatteryLow(StickChip.Available(on = true), 10))
        assertFalse(controllerBatteryLow(StickChip.Available(on = true), 15))
        assertFalse(controllerBatteryLow(StickChip.Available(on = false), 10))
        assertFalse(controllerBatteryLow(StickChip.Available(on = true), null))
        assertFalse(controllerBatteryLow(StickChip.NoGamepad, 5))
    }
}
