package com.altnautica.gcs.feature.cockpit

import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.agent.AgentEndpoints
import com.altnautica.gcs.core.agent.http.AgentError
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.agent.nav.AutonomyFeedApi
import com.altnautica.gcs.core.agent.node.LocalNodeApis
import com.altnautica.gcs.core.data.cockpit.CockpitSettingsRepository
import com.altnautica.gcs.core.data.controller.ControllerSettingsRepository
import com.altnautica.gcs.core.extensions.data.ExtensionRepository
import com.altnautica.gcs.core.extensions.data.PerceptionFeed
import com.altnautica.gcs.core.extensions.skills.ExtensionSkillContributor
import com.altnautica.gcs.core.link.services.VehicleServices
import com.altnautica.gcs.core.link.services.VehicleServicesProvider
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.FcVariant
import com.altnautica.gcs.core.model.FlightMode
import com.altnautica.gcs.core.model.HeartbeatState
import com.altnautica.gcs.core.model.Liveness
import com.altnautica.gcs.core.model.NodeProfile
import com.altnautica.gcs.core.model.NodeSource
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.model.Reach
import com.altnautica.gcs.core.model.VehicleProvider
import com.altnautica.gcs.core.skills.MonotonicClock
import com.altnautica.gcs.core.skills.SkillActivator
import com.altnautica.gcs.core.skills.SkillRegistry
import com.altnautica.gcs.core.skills.SkillSessionFactory
import com.altnautica.gcs.core.skills.binding.BindingStore
import com.altnautica.gcs.core.skills.cockpit.CockpitSummaryRegistry
import com.altnautica.gcs.core.skills.controls.ControlsRepository
import com.altnautica.gcs.core.skills.input.AdosInputRouter
import com.altnautica.gcs.core.skills.input.GamepadAxes
import com.altnautica.gcs.core.skills.input.InputEvent
import com.altnautica.gcs.core.skills.input.PadSnapshot
import com.altnautica.gcs.core.skills.input.controller.ControllerInfo
import com.altnautica.gcs.core.skills.input.controller.ControllerManager
import com.altnautica.gcs.core.video.VideoSessionManager
import com.altnautica.gcs.core.video.VideoSourceSelector
import com.altnautica.gcs.feature.cockpit.alerts.AlertAnnouncer
import com.altnautica.gcs.feature.cockpit.video.CockpitVideoServices
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.IOException

/** A drone node with id [id], live on the LAN. */
internal fun testDroneNode(id: String = "drone-1") =
    NodeSummary(DeviceId(id), "Node $id", NodeProfile.Drone, listOf(Reach.Lan), Liveness.Live, null, FcVariant.MavlinkArdupilot, null, null)

/** One heartbeat from the flight controller at [now]. */
internal fun FakeVehicle.heartbeatAt(now: Long, armed: Boolean, mode: String = "STABILIZE") {
    telemetry.heartbeat.value = HeartbeatState(armed, FlightMode(0, mode), vehicleType = 2, autopilot = 3, tMs = now)
}

/**
 * A cockpit for [vehicle] on the test scheduler with in-memory collaborators, its
 * screen-only flows kept hot like a visible cockpit. The caller sets the main dispatcher.
 * Without [videoSessions] no source can play.
 */
internal fun TestScope.testCockpitVm(
    vehicle: com.altnautica.gcs.core.model.Vehicle,
    localApis: Set<LocalNodeApis> = emptySet(),
    nodes: List<NodeSummary> = listOf(testDroneNode(vehicle.id.value)),
    videoSelector: VideoSourceSelector = VideoSourceSelector(emptySet()),
    videoSessions: VideoSessionManager = mockk<VideoSessionManager>(relaxed = true) { every { canPlay(any()) } returns false },
): CockpitViewModel {
    val clock = MonotonicClock { testScheduler.currentTime }
    val dataStore = MemoryPreferences()
    val router = mockk<AdosInputRouter>(relaxed = true) {
        every { axes } returns MutableStateFlow(GamepadAxes.Neutral)
        every { buttons } returns MutableStateFlow(0)
        every { gamepadConnected } returns MutableStateFlow(false)
        every { events } returns MutableSharedFlow<InputEvent>(extraBufferCapacity = 16)
        every { snapshot } returns MutableStateFlow(PadSnapshot.Neutral)
    }
    val activator = SkillActivator(clock)
    val extensionRepo = mockk<ExtensionRepository> { coEvery { api(any()) } returns null }
    val vm = CockpitViewModel(
        nodeIdValue = vehicle.id.value,
        nodeSources = setOf(object : NodeSource { override val nodes = flowOf(nodes) }),
        vehicles = object : VehicleProvider {
            override fun vehicle(id: DeviceId) = flowOf(vehicle.takeIf { it.id == id })
        },
        services = object : VehicleServicesProvider {
            override fun services(id: DeviceId) = flowOf<VehicleServices?>(null)
        },
        sessionFactory = SkillSessionFactory(SkillRegistry(emptySet(), testSpec), activator, clock),
        bindings = BindingStore(dataStore, testSpec),
        router = router,
        settingsRepo = CockpitSettingsRepository(dataStore),
        controllerSettings = ControllerSettingsRepository(dataStore),
        controllerManager = mockk<ControllerManager>(relaxed = true) {
            every { active } returns MutableStateFlow<ControllerInfo?>(null)
        },
        summaries = CockpitSummaryRegistry(),
        videoServices = CockpitVideoServices(
            selector = videoSelector,
            sessions = videoSessions,
            heroHold = mockk(relaxed = true),
            endpoints = mockk<AgentEndpoints> { coEvery { endpointFor(any()) } returns null },
            streamCatalog = mockk(relaxed = true),
        ),
        announcer = mockk<AlertAnnouncer>(relaxed = true),
        clock = clock,
        activator = activator,
        extensionSkills = mockk<ExtensionSkillContributor> { every { targetActions } returns flowOf(emptyList()) },
        extensions = extensionRepo,
        // A real feed: a mocked one's `() -> Unit` release could not be stubbed reliably once
        // other tests had loaded the class (mockk failed instantiating Function0).
        perception = PerceptionFeed(extensionRepo, backgroundScope),
        localApis = localApis,
        spec = testSpec,
        controls = ControlsRepository(dataStore, testSpec, flowOf(emptyList())),
        missionMirror = com.altnautica.gcs.core.link.mission.MissionMirrorSource { MutableStateFlow(null) },
    )
    listOf(
        vm.status, vm.chip, vm.band, vm.flightValues, vm.minimap, vm.statusItems, vm.preflight, vm.alerts,
    ).forEach { flow -> backgroundScope.launch { flow.collect {} } }
    // A failed assertion skips the test's own cancel; the VM's tickers must not outlive the test.
    backgroundScope.coroutineContext[Job]!!.invokeOnCompletion { vm.viewModelScope.cancel() }
    return vm
}

/**
 * An agent serving the autonomy feeds in process. Its pilot channel sends one pilot
 * frame with [pilotData] and stays open. Over [radio] neither socket can open, and the
 * status route answers each poll with [statusBody] while [statusAnswers] is true, and
 * with nothing once it is false.
 */
internal class FakeAutonomyAgent(
    private val scope: TestScope,
    private val pilotData: String,
    private val radio: Boolean = false,
    private val statusBody: () -> String = {
        """{"pilot":{"ts_ms":${scope.testScheduler.currentTime},"age_ms":100,"data":$pilotData}}"""
    },
) : AutonomyFeedApi {
    var statusAnswers = true

    /**
     * False models a stalled pilot: a channel that (re)connects gets no frame. The channel
     * drops a silent link at the spec's stale age and reconnects, and a live pilot would
     * answer that reconnect with a fresh frame.
     */
    var pilotAnswers = true

    /** The `view` query of each status request answered or not. */
    val polls = mutableListOf<String?>()

    override fun detections() = emptyFlow<String>()

    override fun navStream(outbound: Flow<String>) = flow<String> {
        if (radio) throw IOException("the socket cannot open over this link")
        awaitCancellation()
    }

    override fun pilotStream(outgoing: Flow<String>) = flow {
        if (radio) throw IOException("the socket cannot open over this link")
        if (pilotAnswers) emit("""{"kind":"pilot","ts_ms":${scope.testScheduler.currentTime},"data":$pilotData}""")
        awaitCancellation()
    }

    override suspend fun navStatus(view: String?): AgentResult<JsonObject>? {
        if (!radio) return null
        polls += view
        if (!statusAnswers) return null
        return AgentResult.Ok(Json.parseToJsonElement(statusBody()) as JsonObject)
    }

    private val absent = AgentResult.Err(AgentError.FeatureAbsent)
    override suspend fun visionDemand(body: JsonObject) = absent
    override suspend fun visionStatus() = absent
    override suspend fun setDetector(modelId: String) = absent
    override suspend fun mapLive(since: Long) = absent

    /** This agent as the in-process source of every node's autonomy feeds. */
    val apis: Set<LocalNodeApis> = setOf(
        object : LocalNodeApis {
            override fun handles(id: DeviceId) = false
            override fun capture(id: DeviceId) = null
            override fun sensors(id: DeviceId) = null
            override fun capturePreview(id: DeviceId, camera: String?) = null
            override fun autonomy(id: DeviceId) = this@FakeAutonomyAgent
        },
    )
}
