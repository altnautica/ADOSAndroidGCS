package com.altnautica.gcs.feature.configure.calibration

import com.altnautica.gcs.core.link.calibration.AccelPose
import com.altnautica.gcs.core.link.calibration.CalibrationKind
import com.altnautica.gcs.core.link.calibration.CalibrationState
import com.altnautica.gcs.core.link.calibration.CompassProgress
import com.altnautica.gcs.core.link.calibration.CompassReport
import com.altnautica.gcs.core.link.calibration.CompassStatus
import com.altnautica.gcs.core.link.calibration.RcChannelRange
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.feature.configure.FakeCalibrationService
import com.altnautica.gcs.feature.configure.FakeParamService
import com.altnautica.gcs.feature.configure.FakeVehicle
import com.altnautica.gcs.feature.configure.common.ConfigureTarget
import com.altnautica.gcs.feature.configure.common.ConfigureTargetSource
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CalibrationViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()
    private val clock = 50_000L
    private val vehicle = FakeVehicle(tMs = clock)
    private val cal = FakeCalibrationService()
    private val target = MutableStateFlow<ConfigureTarget?>(vehicle.target(FakeParamService(), cal))

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm() = CalibrationViewModel(DeviceId("d"), ConfigureTargetSource { target }, { clock }, flowOf(Unit))

    @Test
    fun accelWalksThroughSixPosePrompts() = runTest(dispatcher) {
        val vm = vm()
        assertTrue(vm.state.value.canStart)
        vm.start(CalibrationKind.Accel)
        assertEquals(listOf("accel"), cal.calls)

        AccelPose.entries.forEachIndexed { i, pose ->
            cal.state.value = CalibrationState.AccelPosition(pose, "Place vehicle")
            val step = vm.state.value.accelStep!!
            assertEquals(pose, step.pose)
            assertEquals(i + 1, step.index)
            assertEquals(6, step.total)
            assertFalse(vm.state.value.canStart)
            vm.confirmAccelPosition()
        }
        assertEquals(6, cal.calls.count { it == "confirmAccel" })

        cal.state.value = CalibrationState.Succeeded(CalibrationKind.Accel, "Calibration successful")
        assertNull(vm.state.value.accelStep)
        assertTrue(vm.state.value.canStart)
        vm.confirmAccelPosition() // no prompt: ignored
        assertEquals(6, cal.calls.count { it == "confirmAccel" })
    }

    @Test
    fun compassProgressAndAcceptOnlyWhenEveryCompassSucceeded() = runTest(dispatcher) {
        val vm = vm()
        vm.start(CalibrationKind.Compass)
        cal.state.value = CalibrationState.Compass(
            progress = listOf(CompassProgress(0, 40, CompassStatus.Running), CompassProgress(1, 60, CompassStatus.Running)),
            reports = emptyList(),
            readyToAccept = false,
        )
        assertEquals(50, vm.state.value.compassPct)
        assertFalse(vm.state.value.canAcceptCompass)
        vm.acceptCompass()
        assertFalse("acceptCompass" in cal.calls)

        cal.state.value = CalibrationState.Compass(
            progress = listOf(CompassProgress(0, 100, CompassStatus.Success), CompassProgress(1, 100, CompassStatus.Failed)),
            reports = listOf(CompassReport(0, CompassStatus.Success, 8f, false), CompassReport(1, CompassStatus.Failed, 90f, false)),
            readyToAccept = true,
        )
        assertFalse(vm.state.value.canAcceptCompass)

        cal.state.value = CalibrationState.Compass(
            progress = listOf(CompassProgress(0, 100, CompassStatus.Success), CompassProgress(1, 100, CompassStatus.Success)),
            reports = listOf(CompassReport(0, CompassStatus.Success, 8f, false), CompassReport(1, CompassStatus.Success, 12f, false)),
            readyToAccept = true,
        )
        assertTrue(vm.state.value.canAcceptCompass)
        vm.acceptCompass()
        assertTrue("acceptCompass" in cal.calls)
    }

    @Test
    fun cancellingCompassCancelsOnVehicle() = runTest(dispatcher) {
        val vm = vm()
        cal.state.value = CalibrationState.Compass(emptyList(), emptyList(), false)
        vm.cancel()
        assertEquals(listOf("cancelCompass", "cancel"), cal.calls)
        assertEquals(CalibrationState.Idle, vm.state.value.calibration)
    }

    @Test
    fun armedStaleOrMissingVehicleBlocksStart() = runTest(dispatcher) {
        val vm = vm()
        vehicle.setArmed(true)
        assertEquals(CalibrationBlock.Armed, vm.state.value.block)
        vm.start(CalibrationKind.Gyro)
        assertTrue(cal.calls.isEmpty())

        vehicle.setArmed(false, tMs = clock - 4_000)
        assertEquals(CalibrationBlock.LinkStale, vm.state.value.block)

        target.value = null
        assertEquals(CalibrationBlock.NoVehicle, vm.state.value.block)
    }

    @Test
    fun radioSavesOnlyDuringCapture() = runTest(dispatcher) {
        val vm = vm()
        vm.saveRadio()
        assertTrue(cal.calls.isEmpty())
        vm.start(CalibrationKind.Radio)
        cal.state.value = CalibrationState.Radio(listOf(RcChannelRange(1, 1500, 1000, 2000)))
        vm.saveRadio()
        assertEquals(listOf("radio", "saveRadio"), cal.calls)
    }
}
