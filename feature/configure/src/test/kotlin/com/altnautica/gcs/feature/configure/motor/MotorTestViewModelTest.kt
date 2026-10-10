package com.altnautica.gcs.feature.configure.motor

import com.altnautica.gcs.core.model.CommandResult
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.FlightMode
import com.altnautica.gcs.core.model.HeartbeatState
import com.altnautica.gcs.core.model.Vehicle
import com.altnautica.gcs.core.model.VehicleCommand
import com.altnautica.gcs.core.model.VehicleControl
import com.altnautica.gcs.core.model.VehicleProvider
import com.altnautica.gcs.core.model.VehicleTelemetry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MotorTestViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private var clock = 10_000L
    private val heartbeat = MutableStateFlow<HeartbeatState?>(null)
    private val control = mockk<VehicleControl>()
    private val telemetry = mockk<VehicleTelemetry> { every { heartbeat } returns this@MotorTestViewModelTest.heartbeat }
    private val vehicle = mockk<Vehicle> {
        every { telemetry } returns this@MotorTestViewModelTest.telemetry
        every { control } returns this@MotorTestViewModelTest.control
    }
    private val provider = object : VehicleProvider {
        override fun vehicle(id: DeviceId) = flowOf(vehicle)
    }

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun hb(armed: Boolean, tMs: Long = clock) =
        HeartbeatState(armed = armed, mode = FlightMode(0, "Stabilize"), vehicleType = 2, autopilot = 3, tMs = tMs)

    private fun vm() = MotorTestViewModel(DeviceId("d"), provider, { clock }, flowOf(Unit))

    @Test
    fun testsRunOnlyAfterPropsOffAcknowledgementWhileDisarmedAndFresh() = runTest(dispatcher) {
        val vm = vm()
        assertEquals(MotorTestBlock.NoVehicle, vm.state.value.block)

        heartbeat.value = hb(armed = false)
        assertEquals(MotorTestBlock.NotAcknowledged, vm.state.value.block)
        vm.test(1)
        coVerify(exactly = 0) { control.run(any()) }

        vm.setAcknowledged(true)
        assertNull(vm.state.value.block)

        heartbeat.value = hb(armed = true)
        assertEquals(MotorTestBlock.Armed, vm.state.value.block)
        vm.test(1)
        coVerify(exactly = 0) { control.run(any()) }

        heartbeat.value = hb(armed = false, tMs = clock - 5_000)
        assertEquals(MotorTestBlock.LinkStale, vm.state.value.block)

        heartbeat.value = hb(armed = false)
        val reply = CompletableDeferred<CommandResult>()
        coEvery { control.run(any()) } coAnswers { reply.await() }
        vm.setThrottle(50f)
        vm.test(3)
        assertEquals(MotorTestBlock.Running, vm.state.value.block)
        assertEquals(3, vm.state.value.runningMotor)
        vm.test(2) // ignored while running
        reply.complete(CommandResult.Accepted)
        assertEquals(CommandResult.Accepted, vm.state.value.lastResult)
        assertNull(vm.state.value.block)
        coVerify(exactly = 1) { control.run(VehicleCommand.MotorTest(3, 20f, 2f)) }
    }

    @Test
    fun motorOutsideCountIsIgnored() = runTest(dispatcher) {
        heartbeat.value = hb(armed = false)
        val vm = vm()
        vm.setAcknowledged(true)
        vm.test(5)
        coVerify(exactly = 0) { control.run(any()) }
    }
}
