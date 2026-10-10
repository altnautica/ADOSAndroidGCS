package com.altnautica.gcs.feature.cockpit

import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.agent.AgentEndpoints
import com.altnautica.gcs.core.agent.video.StreamCatalog
import com.altnautica.gcs.core.extensions.skills.ExtensionSkillContributor
import com.altnautica.gcs.core.link.manual.ManualControlInput
import com.altnautica.gcs.core.link.manual.ManualControlStreamer
import com.altnautica.gcs.core.link.services.VehicleServices
import com.altnautica.gcs.core.link.services.VehicleServicesProvider
import com.altnautica.gcs.core.model.AlertLevel
import com.altnautica.gcs.core.model.BatteryState
import com.altnautica.gcs.core.model.ConfirmGesture
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.FcVariant
import com.altnautica.gcs.core.model.FlightMode
import com.altnautica.gcs.core.model.HeartbeatState
import com.altnautica.gcs.core.model.HomePosition
import com.altnautica.gcs.core.model.Liveness
import com.altnautica.gcs.core.model.NodeProfile
import com.altnautica.gcs.core.model.NodeSource
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.model.PositionFix
import com.altnautica.gcs.core.model.Reach
import com.altnautica.gcs.core.model.VehicleCommand
import com.altnautica.gcs.core.model.VehicleProvider
import com.altnautica.gcs.core.skills.MonotonicClock
import com.altnautica.gcs.core.skills.SkillActivator
import com.altnautica.gcs.core.skills.SkillReason
import com.altnautica.gcs.core.skills.SkillRegistry
import com.altnautica.gcs.core.skills.SkillSessionFactory
import com.altnautica.gcs.core.skills.binding.BindingStore
import com.altnautica.gcs.core.skills.input.AdosInputRouter
import com.altnautica.gcs.core.skills.input.GamepadAxes
import com.altnautica.gcs.core.skills.input.GamepadButton
import com.altnautica.gcs.core.skills.input.InputEvent
import com.altnautica.gcs.core.video.VideoSessionManager
import com.altnautica.gcs.core.video.VideoSourceSelector
import com.altnautica.gcs.feature.cockpit.alerts.AlertAnnouncer
import com.altnautica.gcs.feature.cockpit.alerts.AlertId
import com.altnautica.gcs.feature.cockpit.settings.CockpitSettingsRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CockpitViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val events = MutableSharedFlow<InputEvent>(extraBufferCapacity = 16)
    private val buttons = MutableStateFlow(0)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun node(id: String, profile: NodeProfile = NodeProfile.Drone, carried: DeviceId? = null) =
        NodeSummary(DeviceId(id), "Node $id", profile, listOf(Reach.Lan), Liveness.Live, null, FcVariant.MavlinkArdupilot, carried, null)

    private val router = mockk<AdosInputRouter>(relaxed = true)
    private val gamepad = MutableStateFlow(false)

    private fun TestScope.vm(
        nodes: List<NodeSummary>,
        vehicle: FakeVehicle?,
        open: String = "drone-1",
        services: VehicleServices? = null,
        vehicleDelayMs: Long = 0,
    ): CockpitViewModel {
        val clock = MonotonicClock { testScheduler.currentTime }
        val dataStore = MemoryPreferences()
        every { router.axes } returns MutableStateFlow(GamepadAxes.Neutral)
        every { router.buttons } returns buttons
        every { router.gamepadConnected } returns gamepad
        every { router.events } returns events
        val activator = SkillActivator(clock)
        return CockpitViewModel(
            nodeIdValue = open,
            nodeSources = setOf(object : NodeSource { override val nodes = flowOf(nodes) }),
            vehicles = object : VehicleProvider {
                override fun vehicle(id: DeviceId) = vehicle?.takeIf { it.id == id }.let { v ->
                    if (vehicleDelayMs == 0L) flowOf(v) else flow { delay(vehicleDelayMs); emit(v) }
                }
            },
            services = object : VehicleServicesProvider {
                override fun services(id: DeviceId) = flowOf(services)
            },
            sessionFactory = SkillSessionFactory(SkillRegistry(emptySet()), activator, clock),
            bindings = BindingStore(dataStore),
            router = router,
            settingsRepo = CockpitSettingsRepository(dataStore),
            videoSelector = VideoSourceSelector(),
            videoSessions = mockk<VideoSessionManager>(relaxed = true) { every { canPlay(any()) } returns false },
            endpoints = mockk<AgentEndpoints> { coEvery { endpointFor(any()) } returns null },
            streamCatalog = mockk<StreamCatalog>(relaxed = true),
            announcer = mockk<AlertAnnouncer>(relaxed = true),
            clock = clock,
            activator = activator,
            extensionSkills = mockk<ExtensionSkillContributor> { every { targetActions } returns flowOf(emptyList()) },
        )
    }

    private fun FakeVehicle.beat(armed: Boolean, now: Long) {
        telemetry.heartbeat.value = HeartbeatState(armed, FlightMode(0, "STABILIZE"), vehicleType = 2, autopilot = 3, tMs = now)
    }

    @Test
    fun `a known drone with a vehicle is ready and an unknown battery stays a dash`() = runTest(dispatcher) {
        val vehicle = FakeVehicle()
        advanceTimeBy(5_000)
        vehicle.beat(armed = false, now = testScheduler.currentTime)
        vehicle.telemetry.battery.value = BatteryState(0, 12.1f, null, remainingPct = -1, timeToReserveS = null, tMs = testScheduler.currentTime)
        val vm = vm(listOf(node("drone-1")), vehicle)
        advanceTimeBy(600)
        runCurrent()
        assertEquals(CockpitStatus.Ready, vm.status.value)
        assertEquals("Node drone-1", vm.band.value.nodeName)
        assertEquals(ReachBadge.Lan, vm.band.value.reach)
        assertNull(vm.band.value.batteryPct)
        assertEquals(BatteryBand.Unknown, vm.band.value.batteryLevel)
        assertTrue(vm.alerts.value.none { it.id == AlertId.BATT_LOW || it.id == AlertId.BATT_CRIT })
        vm.viewModelScope.cancel()
    }

    @Test
    fun `the strip and minimap sample on the band's tick, not on a clock of their own`() = runTest(dispatcher) {
        val vehicle = FakeVehicle()
        advanceTimeBy(5_000)
        val t0 = testScheduler.currentTime
        vehicle.beat(armed = false, now = t0)
        vehicle.telemetry.home.value = HomePosition(47.0, 8.0, 400f, t0)
        vehicle.telemetry.position.value = PositionFix(47.0, 8.0, 400f, 0f, 0f, 0f, 0f, t0)
        // Telemetry arrives 250 ms after the cockpit's 2 Hz clock started, half a period out of phase.
        val vm = vm(listOf(node("drone-1")), vehicle, vehicleDelayMs = 250)
        advanceTimeBy(600)
        runCurrent()
        assertEquals(0f, vm.strip.value.homeDistanceM!!, 0.5f)
        assertFalse(vm.recordingActive.value)

        // About 100 m north of home.
        val moved = testScheduler.currentTime
        vehicle.telemetry.position.value = PositionFix(47.0009, 8.0, 400f, 0f, 0f, 0f, 0f, moved)
        advanceTimeBy(250)
        runCurrent()
        // 500 ms after the telemetry arrived, but the shared tick has not come round yet.
        assertEquals(0f, vm.strip.value.homeDistanceM!!, 0.5f)
        assertEquals(47.0, vm.minimap.value.vehicle!!.position.latDeg, 1e-9)
        val ageBefore = vm.band.value.linkAgeDeciS

        advanceTimeBy(200)
        runCurrent()
        // One tick moves the strip, the minimap and the band's link age together.
        assertEquals(100f, vm.strip.value.homeDistanceM!!, 1f)
        assertEquals(47.0009, vm.minimap.value.vehicle!!.position.latDeg, 1e-9)
        assertTrue(vm.band.value.linkAgeDeciS != ageBefore)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `a ground station without a carried drone has nothing to fly`() = runTest(dispatcher) {
        val vm = vm(listOf(node("gs-1", NodeProfile.GroundStation)), vehicle = null, open = "gs-1")
        runCurrent()
        assertEquals(CockpitStatus.NoCarriedDrone, vm.status.value)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `a ground station cockpit flies its carried drone`() = runTest(dispatcher) {
        val vehicle = FakeVehicle("drone-9")
        vehicle.beat(armed = false, now = testScheduler.currentTime)
        val vm = vm(listOf(node("gs-1", NodeProfile.GroundStation, DeviceId("drone-9")), node("drone-9")), vehicle, open = "gs-1")
        runCurrent()
        assertEquals(CockpitStatus.Ready, vm.status.value)
        assertEquals(DeviceId("drone-9"), vm.extensionNodeId.value)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `an unknown node with no vehicle reports it`() = runTest(dispatcher) {
        val vm = vm(emptyList(), vehicle = null, open = "ghost")
        runCurrent()
        assertEquals(CockpitStatus.UnknownNode, vm.status.value)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `a stale heartbeat disables the bar and raises the link alert`() = runTest(dispatcher) {
        val vehicle = FakeVehicle()
        advanceTimeBy(5_000)
        vehicle.beat(armed = true, now = testScheduler.currentTime)
        val vm = vm(listOf(node("drone-1")), vehicle)
        runCurrent()
        assertTrue(vm.slots.value.first { it.skill?.id == "land" }.state!!.enabled)
        advanceTimeBy(3_600)
        runCurrent()
        val land = vm.slots.value.first { it.skill?.id == "land" }.state!!
        assertFalse(land.enabled)
        assertEquals(SkillReason.NO_FC_LINK, land.reasonKey)
        assertEquals(AlertLevel.Warning, vm.alerts.value.first { it.id == AlertId.LINK_STALE }.level)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `holding the gamepad land button through the sheet lands the vehicle`() = runTest(dispatcher) {
        val vehicle = FakeVehicle()
        advanceTimeBy(5_000)
        vehicle.beat(armed = true, now = testScheduler.currentTime)
        val vm = vm(listOf(node("drone-1")), vehicle)
        vm.attachInput()
        runCurrent()
        buttons.value = 1 shl GamepadButton.X
        events.emit(InputEvent.Button(GamepadButton.X, pressed = true, heldMs = 0))
        runCurrent()
        assertEquals(ConfirmGesture.Hold, vm.confirm.state.value!!.gesture)
        advanceTimeBy(820)
        runCurrent()
        assertEquals(listOf(VehicleCommand.Land), vehicle.control.sent)
        vm.viewModelScope.cancel()
    }

    private class FakeStreamer : ManualControlStreamer {
        override val enabled = MutableStateFlow(false)
        override fun start(input: StateFlow<ManualControlInput>) {
            enabled.value = true
        }
        override fun stop() {
            enabled.value = false
        }
    }

    @Test
    fun `stopping the cockpit turns stick control off and centres the sticks`() = runTest(dispatcher) {
        val vehicle = FakeVehicle()
        val streamer = FakeStreamer()
        val services = mockk<VehicleServices>(relaxed = true) { every { manualControl } returns streamer }
        gamepad.value = true
        advanceTimeBy(5_000)
        vehicle.beat(armed = true, now = testScheduler.currentTime)
        val vm = vm(listOf(node("drone-1")), vehicle, services = services)
        vm.attachInput()
        runCurrent()
        vm.toggleStickControl()
        runCurrent()
        assertTrue(streamer.enabled.value)
        assertEquals(StickChip.Available(on = true), vm.band.value.stick)

        vm.detachInput()
        runCurrent()
        assertFalse("MANUAL_CONTROL must stop while the cockpit is stopped", streamer.enabled.value)
        assertEquals(StickChip.Available(on = false), vm.band.value.stick)
        verify { router.neutralize() }

        // Coming back does not resume streaming on its own.
        vm.attachInput()
        runCurrent()
        assertFalse(streamer.enabled.value)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `losing window focus centres the sticks`() = runTest(dispatcher) {
        val vm = vm(listOf(node("drone-1")), FakeVehicle())
        vm.onWindowFocusLost()
        verify { router.neutralize() }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `a heartbeat newer than the last tick reads as age zero, never negative`() = runTest(dispatcher) {
        val vehicle = FakeVehicle()
        advanceTimeBy(5_000)
        vehicle.beat(armed = false, now = testScheduler.currentTime)
        val vm = vm(listOf(node("drone-1")), vehicle)
        runCurrent()
        advanceTimeBy(300)
        runCurrent()
        // Stamped 300 ms after the last 500 ms tick.
        vehicle.beat(armed = false, now = testScheduler.currentTime)
        runCurrent()
        assertEquals(0, vm.band.value.linkAgeDeciS)
        advanceTimeBy(200)
        runCurrent()
        assertEquals(2, vm.band.value.linkAgeDeciS)
        vm.viewModelScope.cancel()
    }

    private fun FakeVehicle.planeBeat(mode: String, now: Long) {
        telemetry.heartbeat.value = HeartbeatState(false, FlightMode(0, mode), vehicleType = 1, autopilot = 3, tMs = now)
    }

    @Test
    fun `the mode picker lists the plane's modes and a tap-tier pick switches at once`() = runTest(dispatcher) {
        val vehicle = FakeVehicle()
        vehicle.control.availableModes.value =
            listOf(FlightMode(0, "MANUAL"), FlightMode(5, "FBWA"), FlightMode(12, "LOITER"), FlightMode(19, "QLOITER"))
        advanceTimeBy(5_000)
        vehicle.planeBeat("MANUAL", testScheduler.currentTime)
        val vm = vm(listOf(node("drone-1")), vehicle)
        runCurrent()
        assertNull(vm.modePicker.value)

        vm.openModePicker()
        runCurrent()
        val picker = vm.modePicker.value!!
        assertTrue(picker.enabled)
        assertEquals(listOf("MANUAL", "FBWA", "LOITER", "QLOITER"), picker.choices.map { it.name })
        assertTrue(picker.choices.first { it.name == "MANUAL" }.current)
        assertEquals(ConfirmGesture.Tap, picker.choices.first { it.name == "QLOITER" }.gesture)
        assertEquals(ConfirmGesture.Hold, picker.choices.first { it.name == "FBWA" }.gesture)

        vm.selectMode("QLOITER")
        runCurrent()
        assertNull(vm.modePicker.value)
        assertEquals(listOf(VehicleCommand.SetMode(FlightMode(19, "QLOITER"))), vehicle.control.sent)

        // A non-recovery mode asks for the hold tier and names the target.
        vm.openModePicker()
        vm.selectMode("FBWA")
        runCurrent()
        assertEquals(ConfirmGesture.Hold, vm.confirm.state.value!!.gesture)
        vm.confirm.cancel()
        vm.viewModelScope.cancel()
    }

    @Test
    fun `the drawer's set-mode entry opens the picker and plane presets drop Brake`() = runTest(dispatcher) {
        val vehicle = FakeVehicle()
        vehicle.control.availableModes.value = listOf(FlightMode(0, "MANUAL"), FlightMode(12, "LOITER"))
        advanceTimeBy(5_000)
        vehicle.planeBeat("MANUAL", testScheduler.currentTime)
        val vm = vm(listOf(node("drone-1")), vehicle)
        runCurrent()
        val drawer = vm.drawerSkills.value.map { it.skill?.id }
        assertEquals("set-mode", drawer.first())
        assertTrue("mode.loiter" in drawer)
        assertFalse("mode.brake" in drawer)
        vm.activate("set-mode")
        runCurrent()
        assertTrue(vm.modePicker.value != null)
        assertTrue(vehicle.control.sent.isEmpty())
        vm.viewModelScope.cancel()
    }
}
