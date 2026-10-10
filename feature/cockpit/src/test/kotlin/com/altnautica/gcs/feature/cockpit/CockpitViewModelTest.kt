package com.altnautica.gcs.feature.cockpit

import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.agent.AgentEndpoints
import com.altnautica.gcs.core.agent.video.StreamCatalog
import com.altnautica.gcs.core.extensions.skills.ExtensionSkillContributor
import com.altnautica.gcs.core.link.services.VehicleServicesProvider
import com.altnautica.gcs.core.model.AlertLevel
import com.altnautica.gcs.core.model.BatteryState
import com.altnautica.gcs.core.model.ConfirmGesture
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.FcVariant
import com.altnautica.gcs.core.model.FlightMode
import com.altnautica.gcs.core.model.HeartbeatState
import com.altnautica.gcs.core.model.Liveness
import com.altnautica.gcs.core.model.NodeProfile
import com.altnautica.gcs.core.model.NodeSource
import com.altnautica.gcs.core.model.NodeSummary
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
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

    private fun TestScope.vm(
        nodes: List<NodeSummary>,
        vehicle: FakeVehicle?,
        open: String = "drone-1",
        extraSources: Set<NodeSource> = emptySet(),
    ): CockpitViewModel {
        val clock = MonotonicClock { testScheduler.currentTime }
        val dataStore = MemoryPreferences()
        val router = mockk<AdosInputRouter>(relaxed = true) {
            every { axes } returns MutableStateFlow(GamepadAxes.Neutral)
            every { this@mockk.buttons } returns this@CockpitViewModelTest.buttons
            every { gamepadConnected } returns MutableStateFlow(false)
            every { this@mockk.events } returns this@CockpitViewModelTest.events
        }
        val activator = SkillActivator(clock)
        return CockpitViewModel(
            nodeIdValue = open,
            nodeSources = setOf<NodeSource>(object : NodeSource { override val nodes = flowOf(nodes) }) + extraSources,
            vehicles = object : VehicleProvider {
                override fun vehicle(id: DeviceId) = flowOf(vehicle?.takeIf { it.id == id })
            },
            services = object : VehicleServicesProvider {
                override fun services(id: DeviceId) = flowOf(null)
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
    fun `a node source that has not reported yet does not hide the flown node`() = runTest(dispatcher) {
        val vehicle = FakeVehicle()
        vehicle.beat(armed = false, now = testScheduler.currentTime)
        val silent = object : NodeSource { override val nodes = flow<List<NodeSummary>> { awaitCancellation() } }
        val vm = vm(listOf(node("drone-1")), vehicle, extraSources = setOf(silent))
        runCurrent()
        assertEquals("Node drone-1", vm.band.value.nodeName)
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
}
