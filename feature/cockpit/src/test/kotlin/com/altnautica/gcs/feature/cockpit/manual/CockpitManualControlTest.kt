package com.altnautica.gcs.feature.cockpit.manual

import com.altnautica.gcs.core.data.controller.ControllerSettings
import com.altnautica.gcs.core.link.manual.ManualControlInput
import com.altnautica.gcs.core.link.manual.ManualControlStreamer
import com.altnautica.gcs.core.link.services.VehicleServices
import com.altnautica.gcs.core.model.Vehicle
import com.altnautica.gcs.core.skills.input.AdosInputRouter
import com.altnautica.gcs.core.skills.input.GamepadAxes
import com.altnautica.gcs.feature.cockpit.FakeVehicle
import com.altnautica.gcs.feature.cockpit.StickChip
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CockpitManualControlTest {
    private class RecordingStreamer : ManualControlStreamer {
        val calls = mutableListOf<String>()
        override val enabled = MutableStateFlow(false)
        override fun start(input: StateFlow<ManualControlInput>) {
            calls += "start"
            enabled.value = true
        }
        override fun stop() {
            calls += "stop"
            enabled.value = false
        }
    }

    @Test
    fun `stick control stays off after the controller reconnects`() = runTest(UnconfinedTestDispatcher()) {
        val gamepad = MutableStateFlow(true)
        val router = mockk<AdosInputRouter>(relaxed = true) {
            every { gamepadConnected } returns gamepad
            every { axes } returns MutableStateFlow(GamepadAxes.Neutral)
            every { buttons } returns MutableStateFlow(0)
        }
        val streamer = RecordingStreamer()
        val services = mockk<VehicleServices>(relaxed = true) { every { manualControl } returns streamer }
        val control = CockpitManualControl(
            scope = backgroundScope,
            vehicle = MutableStateFlow<Vehicle?>(FakeVehicle()),
            vehicleServices = MutableStateFlow<VehicleServices?>(services),
            router = router,
            controllerSettings = MutableStateFlow(ControllerSettings()),
        )
        runCurrent()
        control.toggle()
        runCurrent()
        assertEquals(StickChip.Available(on = true), control.stick.value)
        assertEquals("start", streamer.calls.last())

        gamepad.value = false
        runCurrent()
        gamepad.value = true
        runCurrent()

        assertEquals(StickChip.Available(on = false), control.stick.value)
        assertEquals("stop", streamer.calls.last())
    }

    @Test
    fun `expo softens pitch, roll and yaw but not throttle`() = runTest(UnconfinedTestDispatcher()) {
        val axes = MutableStateFlow(GamepadAxes(leftX = 0.5f, leftY = 0.5f, rightX = 0.5f, rightY = -1f))
        val router = mockk<AdosInputRouter>(relaxed = true) {
            every { gamepadConnected } returns MutableStateFlow(true)
            every { this@mockk.axes } returns axes
            every { buttons } returns MutableStateFlow(0)
        }
        var streamed: StateFlow<ManualControlInput>? = null
        val streamer = object : ManualControlStreamer {
            override val enabled = MutableStateFlow(false)
            override fun start(input: StateFlow<ManualControlInput>) {
                streamed = input
            }
            override fun stop() = Unit
        }
        val services = mockk<VehicleServices>(relaxed = true) { every { manualControl } returns streamer }
        val control = CockpitManualControl(
            scope = backgroundScope,
            vehicle = MutableStateFlow<Vehicle?>(FakeVehicle()),
            vehicleServices = MutableStateFlow<VehicleServices?>(services),
            router = router,
            controllerSettings = MutableStateFlow(ControllerSettings(expoPct = 60)),
        )
        runCurrent()
        control.toggle()
        runCurrent()
        val input = streamed!!.value
        // 0.4 * 0.5 + 0.6 * 0.125 = 0.275; full deflection stays full.
        assertEquals(0.275f, input.roll, 1e-5f)
        assertEquals(0.275f, input.yaw, 1e-5f)
        assertEquals(-1f, input.pitch, 1e-5f)
        assertEquals(0.75f, input.throttle, 1e-5f)
    }
}
