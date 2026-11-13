package com.altnautica.gcs.feature.cockpit.camera

import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.model.CommandResult
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.FlightMode
import com.altnautica.gcs.core.model.Reach
import com.altnautica.gcs.core.model.Vehicle
import com.altnautica.gcs.core.model.VehicleCommand
import com.altnautica.gcs.core.model.VehicleControl
import com.altnautica.gcs.feature.cockpit.FakeTelemetry
import com.altnautica.gcs.feature.cockpit.FakeVehicle
import com.altnautica.gcs.feature.cockpit.testCockpitVm
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A command service whose every answer waits until the test gives it. */
internal class GatedControl : VehicleControl {
    override val availableModes = MutableStateFlow(listOf(FlightMode(0, "STABILIZE")))
    val sent = mutableListOf<VehicleCommand>()
    var clock: () -> Long = { 0L }
    val sentAt = mutableListOf<Long>()
    val answers = mutableListOf<CompletableDeferred<CommandResult>>()

    override suspend fun run(command: VehicleCommand): CommandResult {
        sent += command
        sentAt += clock()
        val answer = CompletableDeferred<CommandResult>()
        answers += answer
        return answer.await()
    }
}

internal class GatedVehicle(id: String = "drone-1") : Vehicle {
    override val id = DeviceId(id)
    override val reach = MutableStateFlow<Reach>(Reach.Lan)
    override val telemetry = FakeTelemetry()
    override val control = GatedControl()
}

/** Shutter and zoom presses act at the press, not after the camera's slow answer. */
@OptIn(ExperimentalCoroutinesApi::class)
class CameraPressTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a shutter press hands out the frame grab at once while the camera has not answered`() = runTest(dispatcher) {
        val vehicle = GatedVehicle()
        val vm = testCockpitVm(vehicle)
        backgroundScope.launch { vm.vehicle.collect {} }
        val grabs = mutableListOf<Any>()
        backgroundScope.launch { vm.frameGrabs.collect { grabs += it } }
        runCurrent()

        vm.capturePhoto()
        runCurrent()

        assertEquals(listOf<VehicleCommand>(VehicleCommand.CapturePhoto), vehicle.control.sent)
        assertEquals("the frame is taken at the press", 1, grabs.size)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `zoom presses while a camera zoom is pending are ignored`() = runTest(dispatcher) {
        val vehicle = GatedVehicle()
        val vm = testCockpitVm(vehicle)
        backgroundScope.launch { vm.vehicle.collect {} }
        runCurrent()

        vm.zoomIn()
        runCurrent()
        vm.zoomIn()
        vm.zoomIn()
        runCurrent()
        assertEquals("one exchange at a time", 1, vehicle.control.sent.size)

        vehicle.control.answers.single().complete(CommandResult.Unsupported)
        runCurrent()
        assertEquals("the refused zoom steps the video once", 2, vm.digitalZoom.value)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `a zoom press the camera takes zooms at a rate and then stops it`() = runTest(dispatcher) {
        val vehicle = GatedVehicle()
        val vm = testCockpitVm(vehicle)
        backgroundScope.launch { vm.vehicle.collect {} }
        runCurrent()

        vm.zoomOut()
        runCurrent()
        vehicle.control.answers.single().complete(CommandResult.Accepted)
        runCurrent()
        assertEquals("zooming until the burst ends", 1, vehicle.control.sent.size)
        advanceTimeBy(500)
        runCurrent()

        assertEquals(listOf(VehicleCommand.CameraZoom(-1), VehicleCommand.CameraZoom(0)), vehicle.control.sent)
        assertEquals("the camera zoomed itself", 1, vm.digitalZoom.value)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `a zoom start with no final ack still sends the stop`() = runTest(dispatcher) {
        for (lost in listOf(CommandResult.Timeout, CommandResult.Denied("no link"))) {
            val vehicle = GatedVehicle()
            val vm = testCockpitVm(vehicle)
            backgroundScope.launch { vm.vehicle.collect {} }
            runCurrent()

            vm.zoomIn()
            runCurrent()
            vehicle.control.answers.single().complete(lost)
            runCurrent()

            assertEquals("$lost", listOf(VehicleCommand.CameraZoom(1), VehicleCommand.CameraZoom(0)), vehicle.control.sent)
            vm.viewModelScope.cancel()
        }
    }

    @Test
    fun `leaving the screen mid-press stops the zoom`() = runTest(dispatcher) {
        val vehicle = GatedVehicle()
        val vm = testCockpitVm(vehicle)
        backgroundScope.launch { vm.vehicle.collect {} }
        runCurrent()

        vm.zoomIn()
        runCurrent()
        vm.viewModelScope.cancel()
        runCurrent()

        assertEquals(listOf(VehicleCommand.CameraZoom(1), VehicleCommand.CameraZoom(0)), vehicle.control.sent)
    }

    @Test
    fun `without a camera that zooms or shoots, zoom goes digital and a photo keeps the shown frame`() = runTest(dispatcher) {
        val vehicle = FakeVehicle()
        val vm = testCockpitVm(vehicle)
        backgroundScope.launch { vm.vehicle.collect {} }
        val grabs = mutableListOf<FrameGrab>()
        backgroundScope.launch { vm.frameGrabs.collect { grabs += it } }
        runCurrent()
        vehicle.control.result = CommandResult.Unsupported
        vm.zoomIn()
        runCurrent()
        assertEquals(2, vm.digitalZoom.value)
        vm.zoomIn()
        runCurrent()
        assertEquals(4, vm.digitalZoom.value)
        vm.capturePhoto()
        runCurrent()
        assertTrue("no camera took it: the frame is saved", grabs.single().keep.await())
        vehicle.control.result = CommandResult.Accepted
        vm.capturePhoto()
        runCurrent()
        assertFalse("the camera took it", grabs.last().keep.await())
        vm.viewModelScope.cancel()
    }

    @Test
    fun `quick shutter presses each copy at once and keep only what the camera did not take`() = runTest(dispatcher) {
        val control = GatedControl()
        val camera = CockpitCamera(backgroundScope) { control }
        val grabs = mutableListOf<FrameGrab>()
        backgroundScope.launch { camera.frameGrabs.collect { grabs += it } }
        runCurrent()

        camera.capturePhoto()
        camera.capturePhoto()
        runCurrent()
        assertEquals("both presses hand out their grab before any answer", 2, grabs.size)
        assertFalse(grabs[0].keep.isCompleted)

        control.answers[0].complete(CommandResult.Accepted)
        control.answers[1].complete(CommandResult.Unsupported)
        runCurrent()
        assertFalse(grabs[0].keep.await())
        assertTrue(grabs[1].keep.await())
    }

    @Test
    fun `without a vehicle a press keeps the frame and zoom goes digital at once`() = runTest(dispatcher) {
        val camera = CockpitCamera(backgroundScope) { null }
        val grabs = mutableListOf<FrameGrab>()
        backgroundScope.launch { camera.frameGrabs.collect { grabs += it } }
        runCurrent()
        camera.capturePhoto()
        camera.zoom(1)
        runCurrent()
        assertTrue(grabs.single().keep.await())
        assertEquals(2, camera.digitalZoom.value)
    }

    private fun TestScope.camera(): Pair<GatedControl, CockpitCamera> {
        val control = GatedControl().apply { clock = { testScheduler.currentTime } }
        return control to CockpitCamera(backgroundScope) { control }
    }

    @Test
    fun `a zoom start whose ack is lost is stopped at the burst, then stopped again`() = runTest(dispatcher) {
        val (control, camera) = camera()
        camera.zoom(1)
        advanceTimeBy(401)
        assertEquals("stop at the burst, not after the start's retries", listOf(VehicleCommand.CameraZoom(1), VehicleCommand.CameraZoom(0)), control.sent)
        assertEquals(listOf(0L, 400L), control.sentAt)
        control.answers[1].complete(CommandResult.Timeout)
        runCurrent()
        control.answers[0].complete(CommandResult.Accepted)
        runCurrent()
        assertEquals(listOf(VehicleCommand.CameraZoom(1), VehicleCommand.CameraZoom(0), VehicleCommand.CameraZoom(0)), control.sent)
        assertEquals(1, camera.digitalZoom.value)
    }

    @Test
    fun `a zoom start in progress is stopped at the burst and a late accept gets a second stop`() = runTest(dispatcher) {
        val (control, camera) = camera()
        camera.zoom(-1)
        advanceTimeBy(5_000)
        assertEquals(listOf(0L, 400L), control.sentAt)
        // The camera settles the start late; the first stop's exchange ends on that answer.
        control.answers[1].complete(CommandResult.Accepted)
        runCurrent()
        assertEquals(listOf(VehicleCommand.CameraZoom(-1), VehicleCommand.CameraZoom(0), VehicleCommand.CameraZoom(0)), control.sent)
        control.answers[2].complete(CommandResult.Accepted)
        advanceTimeBy(20_000)
        assertEquals("the stop is the last zoom frame", VehicleCommand.CameraZoom(0), control.sent.last())
        assertEquals(3, control.sent.size)
    }

    @Test
    fun `a zoom start refused before the burst steps digitally and stops once`() = runTest(dispatcher) {
        val (control, camera) = camera()
        camera.zoom(1)
        advanceTimeBy(100)
        control.answers[0].complete(CommandResult.Denied("denied"))
        runCurrent()
        advanceTimeBy(1_000)
        assertEquals(listOf(VehicleCommand.CameraZoom(1), VehicleCommand.CameraZoom(0)), control.sent)
        assertEquals(listOf(0L, 100L), control.sentAt)
        assertEquals(2, camera.digitalZoom.value)
    }

    @Test
    fun `leaving the screen before the start is answered stops at once`() = runTest(dispatcher) {
        val control = GatedControl().apply { clock = { testScheduler.currentTime } }
        val scope = CoroutineScope(coroutineContext + Job(coroutineContext[Job]))
        val camera = CockpitCamera(scope) { control }
        camera.zoom(1)
        advanceTimeBy(100)
        scope.cancel()
        runCurrent()
        assertEquals(listOf(VehicleCommand.CameraZoom(1), VehicleCommand.CameraZoom(0)), control.sent)
        assertEquals(listOf(0L, 100L), control.sentAt)
        control.answers[1].complete(CommandResult.Accepted)
        runCurrent()
        assertEquals(VehicleCommand.CameraZoom(0), control.sent.last())
        control.answers.forEach { it.complete(CommandResult.Accepted) }
        runCurrent()
    }
}
