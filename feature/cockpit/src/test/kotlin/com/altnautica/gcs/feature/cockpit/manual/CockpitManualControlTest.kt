package com.altnautica.gcs.feature.cockpit.manual

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
}
