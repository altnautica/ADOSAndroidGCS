package com.altnautica.gcs.feature.cockpit

import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.agent.AgentEndpoints
import com.altnautica.gcs.core.agent.video.StreamCatalog
import com.altnautica.gcs.core.extensions.data.ExtensionRepository
import com.altnautica.gcs.core.extensions.data.PerceptionFeed
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
import com.altnautica.gcs.core.map.GeoPoint
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
import com.altnautica.gcs.core.skills.cockpit.CockpitSummaryRegistry
import com.altnautica.gcs.core.skills.input.AdosInputRouter
import com.altnautica.gcs.core.skills.input.GamepadAxes
import com.altnautica.gcs.core.skills.controls.ControlsRepository
import com.altnautica.gcs.core.skills.input.PadSnapshot
import com.altnautica.gcs.core.skills.input.InputEvent
import com.altnautica.gcs.core.skills.input.controller.ControllerInfo
import com.altnautica.gcs.core.skills.input.controller.ControllerManager
import com.altnautica.gcs.core.video.VideoSessionManager
import com.altnautica.gcs.core.video.VideoSourceSelector
import com.altnautica.gcs.feature.cockpit.alerts.AlertAnnouncer
import com.altnautica.gcs.feature.cockpit.alerts.AlertId
import com.altnautica.gcs.feature.cockpit.pilot.StickRoute
import com.altnautica.gcs.core.data.cockpit.CockpitSettingsRepository
import com.altnautica.gcs.core.data.controller.ControllerSettingsRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import com.altnautica.gcs.core.agent.http.AgentEndpoint
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.agent.video.StreamSwitchOutcome
import com.altnautica.gcs.core.model.StreamDescriptor
import com.altnautica.gcs.core.model.StreamSwitchKind
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrl
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CockpitViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val events = MutableSharedFlow<InputEvent>(extraBufferCapacity = 16)
    private val buttons = MutableStateFlow(0)
    private val pad = MutableStateFlow(PadSnapshot.Neutral)

    /** A DualShock 4 holding [controls] (spec control ids), as the router's snapshot reports it. */
    private fun TestScope.hold(vararg controls: String) {
        val bits = testSpec.ds4.stream.buttonsBitfield
        val mask = controls.fold(0) { m, c -> m or (1 shl bits.getValue(c)) }
        pad.value = PadSnapshot(1, 0x054c, 0x09cc, GamepadAxes.Neutral, GamepadAxes.Neutral, mask, testScheduler.currentTime, emptyList())
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun node(id: String, profile: NodeProfile = NodeProfile.Drone, carried: DeviceId? = null) =
        NodeSummary(DeviceId(id), "Node $id", profile, listOf(Reach.Lan), Liveness.Live, null, FcVariant.MavlinkArdupilot, carried, null)

    private val router = mockk<AdosInputRouter>(relaxed = true)
    private val gamepad = MutableStateFlow(false)
    private val mirror = MutableStateFlow<com.altnautica.gcs.core.agent.mission.MissionMirror?>(null)
    private val missionMirror = com.altnautica.gcs.core.link.mission.MissionMirrorSource { mirror }
    private val summaries = CockpitSummaryRegistry()
    private val controllers = mockk<ControllerManager>(relaxed = true) {
        every { active } returns MutableStateFlow<ControllerInfo?>(null)
    }

    private fun TestScope.vm(
        nodes: List<NodeSummary>,
        vehicle: FakeVehicle?,
        open: String = "drone-1",
        services: VehicleServices? = null,
        vehicleDelayMs: Long = 0,
        streamCatalog: StreamCatalog = mockk(relaxed = true),
        agentBaseUrl: String? = null,
        localApis: Set<com.altnautica.gcs.core.agent.node.LocalNodeApis> = emptySet(),
    ): CockpitViewModel {
        val clock = MonotonicClock { testScheduler.currentTime }
        val dataStore = MemoryPreferences()
        every { router.axes } returns MutableStateFlow(GamepadAxes.Neutral)
        every { router.buttons } returns buttons
        every { router.gamepadConnected } returns gamepad
        every { router.events } returns events
        every { router.snapshot } returns pad
        val activator = SkillActivator(clock)
        val endpoint = agentBaseUrl?.let { AgentEndpoint(null, it.toHttpUrl(), null) }
        val vm = CockpitViewModel(
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
            sessionFactory = SkillSessionFactory(SkillRegistry(emptySet(), testSpec), activator, clock),
            bindings = BindingStore(dataStore, testSpec),
            router = router,
            settingsRepo = CockpitSettingsRepository(dataStore),
            controllerSettings = ControllerSettingsRepository(dataStore),
            controllerManager = controllers,
            summaries = summaries,
            videoSelector = VideoSourceSelector(emptySet()),
            videoSessions = mockk<VideoSessionManager>(relaxed = true) { every { canPlay(any()) } returns (endpoint != null) },
            endpoints = mockk<AgentEndpoints> { coEvery { endpointFor(any()) } returns endpoint },
            streamCatalog = streamCatalog,
            announcer = mockk<AlertAnnouncer>(relaxed = true),
            clock = clock,
            activator = activator,
            extensionSkills = mockk<ExtensionSkillContributor> { every { targetActions } returns flowOf(emptyList()) },
            extensions = mockk<ExtensionRepository> { coEvery { api(any()) } returns null },
            perception = mockk<PerceptionFeed> {
                every { acquire(any()) } returns {}
                every { batches(any()) } returns null
            },
            localApis = localApis,
            spec = testSpec,
            controls = ControlsRepository(dataStore, testSpec, flowOf(emptyList())),
            missionMirror = missionMirror,
        )
        // The screen-only flows run while collected; keep them hot like a visible cockpit.
        listOf(
            vm.status, vm.maxBrightness, vm.canRecord, vm.recordingActive, vm.skillStates, vm.pickerChip,
            vm.modeSheet, vm.chip, vm.flow, vm.targetActions, vm.band, vm.flightValues, vm.minimap,
        ).forEach { flow -> backgroundScope.launch { flow.collect {} } }
        // A failed assertion skips the test's own cancel; without this the VM's endless
        // tickers would keep the scheduler's final drain spinning instead of failing.
        backgroundScope.coroutineContext[kotlinx.coroutines.Job]!!.invokeOnCompletion { vm.viewModelScope.cancel() }
        return vm
    }

    /**
     * An in-process agent whose pilot channel reports one pilot frame ([data]), then stays
     * open. With [radio] its navigation socket cannot open and the status route answers
     * with [data] as a fresh `pilot` kind; each status request's `view` lands in [views].
     */
    private fun TestScope.pilotAgent(
        data: String = """{"owner":"fc_mode","armed":true}""",
        radio: Boolean = false,
        views: MutableList<String?> = mutableListOf(),
        sent: MutableList<String> = mutableListOf(),
    ): Set<com.altnautica.gcs.core.agent.node.LocalNodeApis> {
        val api = object : com.altnautica.gcs.core.agent.nav.AutonomyFeedApi {
            override fun detections() = kotlinx.coroutines.flow.emptyFlow<String>()
            override fun navStream(outbound: kotlinx.coroutines.flow.Flow<String>) = flow<String> {
                if (radio) throw java.io.IOException("the socket cannot open over this link")
                kotlinx.coroutines.awaitCancellation()
            }
            override fun pilotStream(outgoing: kotlinx.coroutines.flow.Flow<String>) = flow {
                backgroundScope.launch { outgoing.collect { sent += it } }
                if (radio) throw java.io.IOException("the socket cannot open over this link")
                emit("""{"kind":"pilot","ts_ms":${testScheduler.currentTime},"data":$data}""")
                kotlinx.coroutines.awaitCancellation()
            }
            override suspend fun navStatus(view: String?): AgentResult<kotlinx.serialization.json.JsonObject>? {
                if (!radio) return null
                views += view
                val body = """{"pilot":{"ts_ms":${testScheduler.currentTime},"age_ms":100,"data":$data},"pose":null}"""
                return AgentResult.Ok(kotlinx.serialization.json.Json.parseToJsonElement(body) as kotlinx.serialization.json.JsonObject)
            }
            private val absent = AgentResult.Err(com.altnautica.gcs.core.agent.http.AgentError.FeatureAbsent)
            override suspend fun visionDemand(body: kotlinx.serialization.json.JsonObject) = absent
            override suspend fun visionStatus() = absent
            override suspend fun setDetector(modelId: String) = absent
            override suspend fun mapLive(since: Long) = absent
        }
        return setOf(
            object : com.altnautica.gcs.core.agent.node.LocalNodeApis {
                override fun handles(id: DeviceId) = false
                override fun capture(id: DeviceId) = null
                override fun sensors(id: DeviceId) = null
                override fun capturePreview(id: DeviceId, camera: String?) = null
                override fun autonomy(id: DeviceId) = api
            },
        )
    }

    @Test
    fun `sending the launch closes the Launch sheet and the take-off after landing opens it again`() = runTest(dispatcher) {
        val vehicle = FakeVehicle()
        advanceTimeBy(5_000)
        // Armed once this session: the first-arm checklist no longer gates the launch.
        vehicle.beat(armed = true, now = testScheduler.currentTime)
        val vm = vm(listOf(node("drone-1")), vehicle)
        backgroundScope.launch { vm.launch.collect {} }
        advanceTimeBy(600)
        runCurrent()

        vm.openLaunch(true)
        runCurrent()
        assertNotNull(vm.launch.value)
        vm.launchNow()
        runCurrent()
        assertNull("the sheet closes once the launch is sent", vm.launch.value)

        // Flight, landing, disarm: the next take-off tap opens a fresh sheet.
        vehicle.beat(armed = false, now = testScheduler.currentTime)
        advanceTimeBy(600)
        runCurrent()
        vm.openLaunch(true)
        runCurrent()
        assertNotNull(vm.launch.value)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `the pilot's goto op, AGL and relayed status texts reach the map, the bottom row and the pill`() = runTest(dispatcher) {
        val vehicle = FakeVehicle()
        vehicle.beat(armed = true, now = testScheduler.currentTime)
        vehicle.telemetry.position.value = PositionFix(47.0, 8.0, 412f, 12f, 0f, 0f, 0f, testScheduler.currentTime)
        val sent = mutableListOf<String>()
        val data = """{"owner":"pilot","armed":true,"landed":false,"surface_ops":["goto"],"agl_m":4.2,"agl_source":"rangefinder",""" +
            """"statustext":[{"severity":4,"text":"PreArm: Compass not calibrated","age_ms":200}]}"""
        val vm = vm(listOf(node("drone-1")), vehicle, localApis = pilotAgent(data, sent = sent))
        backgroundScope.launch { vm.flightValues.collect {} }
        backgroundScope.launch { vm.statusItems.collect {} }
        advanceTimeBy(600)
        runCurrent()

        assertEquals(4.2f, vm.flightValues.value.aglM!!, 1e-6f)
        assertTrue(vm.statusItems.value.toString(), vm.statusItems.value.any { "Compass not calibrated" in it.toString() })

        vm.pickMapPoint(GeoPoint(47.001, 8.002))
        runCurrent()
        val goto = sent.map { kotlinx.serialization.json.Json.parseToJsonElement(it) as kotlinx.serialization.json.JsonObject }
            .firstOrNull { it["name"]?.toString() == "\"goto\"" }
        assertNotNull("a map point in the air goes to the pilot's goto action: $sent", goto)
        val args = goto!!["args"] as kotlinx.serialization.json.JsonObject
        assertEquals("47.001", args["lat"].toString())
        assertEquals("8.002", args["lon"].toString())
        assertEquals("12.0", args["alt_m"].toString())
        vm.viewModelScope.cancel()
    }

    @Test
    fun `with no navigation socket the layers poll the status route and say so`() = runTest(dispatcher) {
        val vehicle = FakeVehicle()
        vehicle.beat(armed = true, now = testScheduler.currentTime)
        val views = mutableListOf<String?>()
        val vm = vm(listOf(node("drone-1")), vehicle, localApis = pilotAgent("""{"owner":"goto","armed":true}""", radio = true, views = views))
        assertFalse(vm.autonomy.feed.reducedRate.value)
        advanceTimeBy(1_100)
        runCurrent()
        assertTrue("the status route is polled", views.isNotEmpty())
        assertTrue("the polled frames are shown as reduced rate", vm.autonomy.feed.reducedRate.value)
        assertEquals("the polled pilot kind drives the cockpit", "goto", vm.chip.value.ruleId)
        vm.viewModelScope.cancel()
    }

    private fun FakeVehicle.beat(armed: Boolean, now: Long, mode: String = "STABILIZE") {
        telemetry.heartbeat.value = HeartbeatState(armed, FlightMode(0, mode), vehicleType = 2, autopilot = 3, tMs = now)
    }

    @Test
    fun `a later stream pick wins over a slower earlier switch`() = runTest(dispatcher) {
        val drone = DeviceId("drone-1")
        val catalog = mockk<StreamCatalog>()
        every { catalog.streams(any()) } returns flowOf(
            listOf("cam-a", "cam-b", "cam-c").map { StreamDescriptor(it, "main", "h264", live = true, switchKind = StreamSwitchKind.Switchable) },
        )
        coEvery { catalog.switch(drone, "cam-b") } coAnswers {
            delay(2_000)
            AgentResult.Ok(StreamSwitchOutcome.Switched)
        }
        coEvery { catalog.switch(drone, "cam-c") } coAnswers {
            delay(100)
            AgentResult.Ok(StreamSwitchOutcome.Switched)
        }
        val vm = vm(listOf(node("drone-1")), vehicle = null, streamCatalog = catalog, agentBaseUrl = "http://10.0.0.2:8080/")
        runCurrent()
        assertEquals("cam-a", vm.video.value.selectedStreamId)

        vm.selectStream("cam-b")
        runCurrent()
        vm.selectStream("cam-c")
        advanceTimeBy(3_000)
        runCurrent()

        assertEquals("cam-c", vm.video.value.selectedStreamId)
        assertNull(vm.video.value.switchingStreamId)
        vm.viewModelScope.cancel()
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
        assertEquals(0f, vm.flightValues.value.distanceM!!, 0.5f)
        assertFalse(vm.recordingActive.value)

        // About 100 m north of home.
        val moved = testScheduler.currentTime
        vehicle.telemetry.position.value = PositionFix(47.0009, 8.0, 400f, 0f, 0f, 0f, 0f, moved)
        advanceTimeBy(250)
        runCurrent()
        // 500 ms after the telemetry arrived, but the shared tick has not come round yet.
        assertEquals(0f, vm.flightValues.value.distanceM!!, 0.5f)
        assertEquals(47.0, vm.minimap.value.vehicle!!.position.latDeg, 1e-9)
        val ageBefore = vm.band.value.linkAgeDeciS

        advanceTimeBy(200)
        runCurrent()
        // One tick moves the strip, the minimap and the band's link age together.
        assertEquals(100f, vm.flightValues.value.distanceM!!, 1f)
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
        assertTrue(vm.skillStates.value.getValue("land").enabled)
        advanceTimeBy(3_600)
        runCurrent()
        val land = vm.skillStates.value.getValue("land")
        assertFalse(land.enabled)
        assertEquals(SkillReason.NO_FC_LINK, land.reasonKey)
        assertEquals(AlertLevel.Warning, vm.alerts.value.first { it.id == AlertId.LINK_STALE }.level)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `holding triangle in the air for 1_5 s lands without a second sheet`() = runTest(dispatcher) {
        val vehicle = FakeVehicle()
        advanceTimeBy(5_000)
        vehicle.beat(armed = true, now = testScheduler.currentTime)
        val vm = vm(listOf(node("drone-1")), vehicle)
        vm.attachInput()
        runCurrent()
        hold("triangle")
        advanceTimeBy(1_400)
        runCurrent()
        assertTrue(vehicle.control.sent.isEmpty())
        advanceTimeBy(200)
        runCurrent()
        assertNull(vm.confirm.state.value)
        assertEquals(listOf(VehicleCommand.Land), vehicle.control.sent)
        hold()
        vm.viewModelScope.cancel()
    }

    @Test
    fun `controller skill bindings do nothing while a navigable overlay is open`() = runTest(dispatcher) {
        val vehicle = FakeVehicle()
        advanceTimeBy(5_000)
        vehicle.beat(armed = true, now = testScheduler.currentTime)
        val vm = vm(listOf(node("drone-1")), vehicle)
        vm.attachInput()
        runCurrent()
        vm.setQuickSettings(true)
        hold("triangle")
        advanceTimeBy(1_600)
        runCurrent()
        assertTrue(vehicle.control.sent.isEmpty())
        assertTrue(vm.quickSettingsOpen.value)
        hold()
        vm.viewModelScope.cancel()
    }

    @Test
    fun `triangle tap cycles the view, options opens quick settings and back closes then leaves`() = runTest(dispatcher) {
        val vm = vm(listOf(node("drone-1")), FakeVehicle())
        vm.attachInput()
        runCurrent()
        val emitted = mutableListOf<CockpitEvent>()
        backgroundScope.launch { vm.events.collect { emitted += it } }
        runCurrent()
        assertEquals(CockpitView.Pilot, vm.view.value)

        hold("triangle")
        advanceTimeBy(100)
        hold()
        advanceTimeBy(100)
        runCurrent()
        assertEquals(CockpitView.Map, vm.view.value)

        hold("options")
        advanceTimeBy(100)
        hold()
        advanceTimeBy(100)
        runCurrent()
        assertTrue(vm.quickSettingsOpen.value)

        vm.back()
        assertFalse(vm.quickSettingsOpen.value)
        assertFalse(CockpitEvent.ExitRequested in emitted)
        vm.back()
        runCurrent()
        assertTrue(CockpitEvent.ExitRequested in emitted)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `the PS button brakes on press even with a sheet open`() = runTest(dispatcher) {
        val vehicle = FakeVehicle()
        advanceTimeBy(5_000)
        vehicle.beat(armed = true, now = testScheduler.currentTime, mode = "GUIDED")
        val vm = vm(listOf(node("drone-1")), vehicle)
        vm.attachInput()
        runCurrent()
        vm.setQuickSettings(true)
        hold("ps")
        advanceTimeBy(60)
        runCurrent()
        assertEquals(listOf<VehicleCommand>(VehicleCommand.SetMode(FlightMode(5, "LOITER"))), vehicle.control.sent)
        hold()
        vm.viewModelScope.cancel()
    }

    @Test
    fun `the started cockpit publishes its summary and clears it on stop`() = runTest(dispatcher) {
        val vehicle = FakeVehicle()
        advanceTimeBy(5_000)
        vehicle.beat(armed = false, now = testScheduler.currentTime)
        val vm = vm(listOf(node("drone-1")), vehicle)
        vm.attachInput()
        advanceTimeBy(600)
        runCurrent()
        val summary = summaries.summary.value!!
        assertEquals(DeviceId("drone-1"), summary.nodeId)
        assertEquals(vm.preflight.value.total, summary.preflightTotal)
        assertFalse(summary.stickOn)

        vm.detachInput()
        runCurrent()
        assertNull(summaries.summary.value)
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
        // A flight controller attached to the phone: MANUAL_CONTROL is its stick path.
        val vehicle = FakeVehicle().apply { reach.value = Reach.DirectFc }
        val streamer = FakeStreamer()
        val services = mockk<VehicleServices>(relaxed = true) { every { manualControl } returns streamer }
        gamepad.value = true
        advanceTimeBy(5_000)
        vehicle.beat(armed = true, now = testScheduler.currentTime, mode = "LOITER")
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
    fun `a drone reached through its agent gets no MANUAL_CONTROL before its pilot reports a control mode`() = runTest(dispatcher) {
        val vehicle = FakeVehicle()
        val streamer = FakeStreamer()
        val services = mockk<VehicleServices>(relaxed = true) { every { manualControl } returns streamer }
        gamepad.value = true
        advanceTimeBy(5_000)
        vehicle.beat(armed = true, now = testScheduler.currentTime, mode = "LOITER")
        val vm = vm(listOf(node("drone-1")), vehicle, services = services)
        vm.attachInput()
        runCurrent()
        assertEquals(StickRoute.None, vm.pilot.route.value)
        vm.toggleStickControl()
        runCurrent()
        assertFalse("sticks must not reach the FC by MANUAL_CONTROL", streamer.enabled.value)
        assertEquals(StickChip.LinkBlocked, vm.band.value.stick)
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
    fun `the Mode sheet lists the plane's modes and a tap-tier pick switches at once`() = runTest(dispatcher) {
        val vehicle = FakeVehicle()
        vehicle.control.availableModes.value =
            listOf(FlightMode(0, "MANUAL"), FlightMode(5, "FBWA"), FlightMode(12, "LOITER"), FlightMode(19, "QLOITER"))
        advanceTimeBy(5_000)
        vehicle.planeBeat("MANUAL", testScheduler.currentTime)
        val vm = vm(listOf(node("drone-1")), vehicle)
        runCurrent()
        assertNull(vm.modeSheet.value)

        vm.openModeSheet()
        runCurrent()
        val picker = vm.modeSheet.value!!.fcModes
        assertTrue(picker.enabled)
        assertEquals(listOf("MANUAL", "FBWA", "LOITER", "QLOITER"), picker.choices.map { it.name })
        assertTrue(picker.choices.first { it.name == "MANUAL" }.current)
        assertEquals(ConfirmGesture.Tap, picker.choices.first { it.name == "QLOITER" }.gesture)
        assertEquals(ConfirmGesture.Hold, picker.choices.first { it.name == "FBWA" }.gesture)

        vm.modeSheetActions.onFcMode("QLOITER")
        runCurrent()
        assertNull(vm.modeSheet.value)
        assertEquals(listOf(VehicleCommand.SetMode(FlightMode(19, "QLOITER"))), vehicle.control.sent)

        // A non-recovery mode asks for the hold tier and names the target.
        vm.openModeSheet()
        vm.modeSheetActions.onFcMode("FBWA")
        runCurrent()
        assertEquals(ConfirmGesture.Hold, vm.confirm.state.value!!.gesture)
        vm.confirm.cancel()
        vm.viewModelScope.cancel()
    }

    @Test
    fun `set-mode without a target opens the Mode sheet and sends nothing`() = runTest(dispatcher) {
        val vehicle = FakeVehicle()
        vehicle.control.availableModes.value = listOf(FlightMode(0, "MANUAL"), FlightMode(12, "LOITER"))
        advanceTimeBy(5_000)
        vehicle.planeBeat("MANUAL", testScheduler.currentTime)
        val vm = vm(listOf(node("drone-1")), vehicle)
        runCurrent()
        vm.activate("set-mode")
        runCurrent()
        assertTrue(vm.modeSheet.value != null)
        assertTrue(vehicle.control.sent.isEmpty())
        vm.viewModelScope.cancel()
    }

    @Test
    fun `a VTOL transition reported by the flight controller shows on the chip`() = runTest(dispatcher) {
        val vehicle = FakeVehicle()
        vehicle.beat(armed = true, now = testScheduler.currentTime)
        val vm = vm(listOf(node("drone-1")), vehicle, localApis = pilotAgent())
        advanceTimeBy(600)
        runCurrent()
        assertEquals("fc_other", vm.chip.value.ruleId)
        vehicle.telemetry.vtolTransitioning.value = true
        advanceTimeBy(600)
        runCurrent()
        assertEquals("vtol_transition", vm.chip.value.ruleId)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `the goto chip reads the distance from the vehicle to the goal`() = runTest(dispatcher) {
        val vehicle = FakeVehicle()
        vehicle.beat(armed = true, now = testScheduler.currentTime)
        vehicle.telemetry.localPosition.value = com.altnautica.gcs.core.model.LocalPosition(0f, 0f, -10f, 0f, 0f, 0f, 0L)
        // On a clear path the planner puts the setpoint on the goal; the goal is 50 m from the vehicle.
        val goto = """{"owner":"goto","armed":true,"goal_ned":[30,40,-10],"setpoint_ned":[30,40,-10]}"""
        val vm = vm(listOf(node("drone-1")), vehicle, localApis = pilotAgent(goto))
        advanceTimeBy(600)
        runCurrent()
        assertEquals("goto", vm.chip.value.ruleId)
        assertEquals("GOTO · 50 m", vm.chip.value.primary.text)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `the first pad connected in the cockpit opens the controller tour once`() = runTest(dispatcher) {
        val vm = vm(listOf(node("drone-1")), FakeVehicle())
        runCurrent()
        assertNull(vm.tourStep.value)
        gamepad.value = true
        runCurrent()
        assertEquals(0, vm.tourStep.value)
        repeat(CockpitViewModel.TOUR_STEPS) { vm.nextTourStep() }
        runCurrent()
        assertNull("past the last card the tour closes", vm.tourStep.value)
        gamepad.value = false
        runCurrent()
        gamepad.value = true
        runCurrent()
        assertNull("a seen tour does not come back", vm.tourStep.value)
        vm.viewModelScope.cancel()
    }

    @Test
    fun `the cockpit map draws the route the vehicle holds`() = runTest(dispatcher) {
        val vm = vm(listOf(node("drone-1")), FakeVehicle())
        backgroundScope.launch { vm.missionRoute.collect {} }
        runCurrent()
        assertTrue(vm.missionRoute.value.waypoints.isEmpty())
        mirror.value = com.altnautica.gcs.core.agent.mission.MissionMirror(
            count = 3,
            homeSlot = true,
            items = listOf(
                com.altnautica.gcs.core.agent.mission.MirrorItem(0, 16, x = 470000000, y = 80000000),
                com.altnautica.gcs.core.agent.mission.MirrorItem(1, 22, x = 470001000, y = 80000000),
                com.altnautica.gcs.core.agent.mission.MirrorItem(2, 16, x = 470009000, y = 80000000),
            ),
        )
        runCurrent()
        val route = vm.missionRoute.value
        assertEquals(listOf("1", "2"), route.waypoints.map { it.label })
        assertEquals(47.0009, route.waypoints.last().position.latDeg, 1e-9)
        vm.viewModelScope.cancel()
    }
}
