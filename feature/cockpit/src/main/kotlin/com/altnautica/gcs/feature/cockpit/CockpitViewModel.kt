package com.altnautica.gcs.feature.cockpit

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.agent.AgentEndpoints
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.agent.video.StreamCatalog
import com.altnautica.gcs.core.agent.video.StreamSwitchOutcome
import com.altnautica.gcs.core.link.manual.ManualControlInput
import com.altnautica.gcs.core.map.GeoPoint
import com.altnautica.gcs.core.map.MapVehicle
import com.altnautica.gcs.core.link.services.VehicleServices
import com.altnautica.gcs.core.link.services.VehicleServicesProvider
import com.altnautica.gcs.core.model.CockpitAlert
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.FlightMode
import com.altnautica.gcs.core.model.NodeProfile
import com.altnautica.gcs.core.model.NodeSource
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.model.Reach
import com.altnautica.gcs.core.model.StreamDescriptor
import com.altnautica.gcs.core.model.StreamSwitchKind
import com.altnautica.gcs.core.model.Vehicle
import com.altnautica.gcs.core.model.VehicleProvider
import com.altnautica.gcs.core.model.VehicleTelemetry
import com.altnautica.gcs.core.model.VideoSource
import com.altnautica.gcs.core.model.VideoState
import com.altnautica.gcs.core.skills.ActivationOutcome
import com.altnautica.gcs.core.extensions.skills.ExtensionSkillContributor
import com.altnautica.gcs.core.extensions.skills.ExtensionTargetActionSkill
import com.altnautica.gcs.core.skills.SkillActivator
import com.altnautica.gcs.core.skills.MonotonicClock
import com.altnautica.gcs.core.skills.SkillNotice
import com.altnautica.gcs.core.skills.SkillSessionFactory
import com.altnautica.gcs.core.skills.binding.BindingStore
import com.altnautica.gcs.core.skills.binding.Loadout
import com.altnautica.gcs.core.skills.builtin.SetModeSkill
import com.altnautica.gcs.core.skills.builtin.SkillArgs
import com.altnautica.gcs.core.skills.heartbeatAgeMs
import com.altnautica.gcs.core.skills.input.AdosInputRouter
import com.altnautica.gcs.core.skills.input.InputCapture
import com.altnautica.gcs.core.skills.input.InputEvent
import com.altnautica.gcs.core.skills.input.ReservedInputs
import com.altnautica.gcs.core.skills.isLinkFresh
import com.altnautica.gcs.feature.cockpit.modes.ModePickerUi
import com.altnautica.gcs.feature.cockpit.modes.modeChoices
import com.altnautica.gcs.core.video.VideoSessionManager
import com.altnautica.gcs.core.video.VideoSourceSelector
import com.altnautica.gcs.core.video.RecordingState
import com.altnautica.gcs.feature.cockpit.alerts.AlertAnnouncer
import com.altnautica.gcs.feature.cockpit.alerts.AlertEngine
import com.altnautica.gcs.feature.cockpit.alerts.AlertInputs
import com.altnautica.gcs.feature.cockpit.alerts.ReceivedText
import com.altnautica.gcs.feature.cockpit.confirm.ConfirmController
import com.altnautica.gcs.feature.cockpit.input.CockpitAction
import com.altnautica.gcs.feature.cockpit.input.CockpitInputMapper
import com.altnautica.gcs.feature.cockpit.preflight.PreflightChecklist
import com.altnautica.gcs.feature.cockpit.preflight.PreflightInputs
import com.altnautica.gcs.feature.cockpit.preflight.PreflightResult
import com.altnautica.gcs.feature.cockpit.settings.AltitudeReference
import com.altnautica.gcs.feature.cockpit.settings.CockpitDensity
import com.altnautica.gcs.feature.cockpit.settings.CockpitSettings
import com.altnautica.gcs.feature.cockpit.settings.CockpitSettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Severity at or below which a STATUSTEXT raises a critical alert (MAV_SEVERITY_CRITICAL). */
private const val SEVERITY_CRITICAL = 2

/** Cockpit for one node. A ground station's cockpit flies its carried drone. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = CockpitViewModel.Factory::class)
class CockpitViewModel @AssistedInject constructor(
    @Assisted nodeIdValue: String,
    nodeSources: Set<@JvmSuppressWildcards NodeSource>,
    vehicles: VehicleProvider,
    private val services: VehicleServicesProvider,
    sessionFactory: SkillSessionFactory,
    private val bindings: BindingStore,
    private val router: AdosInputRouter,
    private val settingsRepo: CockpitSettingsRepository,
    private val videoSelector: VideoSourceSelector,
    private val videoSessions: VideoSessionManager,
    private val endpoints: AgentEndpoints,
    private val streamCatalog: StreamCatalog,
    private val announcer: AlertAnnouncer,
    private val clock: MonotonicClock,
    private val activator: SkillActivator,
    extensionSkills: ExtensionSkillContributor,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        /** Takes the raw id: Dagger cannot pass inline value classes through assisted factories. */
        fun create(nodeId: String): CockpitViewModel
    }

    private val nodeId = DeviceId(nodeIdValue)

    // ---- Node and vehicle -------------------------------------------------------------

    private val allNodes: Flow<List<NodeSummary>> =
        if (nodeSources.isEmpty()) flowOf(emptyList())
        else combine(nodeSources.map { src -> src.nodes }) { lists -> lists.flatMap { it.toList() } }

    private fun merged(nodes: List<NodeSummary>, id: DeviceId): NodeSummary? {
        val matches = nodes.filter { it.id == id }
        if (matches.isEmpty()) return null
        val reaches = matches.flatMap { it.reaches }.distinct().sortedBy { reachRank(it) }
        return matches.first().copy(reaches = reaches)
    }

    /** The node the cockpit was opened for. */
    val node: StateFlow<NodeSummary?> = allNodes.map { merged(it, nodeId) }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * The vehicle node actually flown: the node itself, or a ground station's carried
     * drone. A vehicle no link source lists (a log replay) is flown by its own id.
     */
    private val vehicleNodeId: StateFlow<DeviceId?> = node.map { n ->
        when {
            n == null -> nodeId
            n.profile == NodeProfile.GroundStation -> n.carriedDroneId
            else -> n.id
        }
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, nodeId)

    private val vehicleNode: StateFlow<NodeSummary?> = combine(allNodes, vehicleNodeId) { nodes, id ->
        id?.let { merged(nodes, it) }
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val vehicle: StateFlow<Vehicle?> = vehicleNodeId.flatMapLatest { id ->
        if (id == null) flowOf(null) else vehicles.vehicle(id)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Per-topic telemetry for the HUD, which reads each flow inside its draw pass. */
    val telemetry: StateFlow<VehicleTelemetry?> = vehicle.map { it?.telemetry }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val vehicleServices: StateFlow<VehicleServices?> = vehicleNodeId.flatMapLatest { id ->
        if (id == null) flowOf(null) else services.services(id)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val nodesLoaded: StateFlow<Boolean> = allNodes.map { true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val status: StateFlow<CockpitStatus> = combine(node, vehicleNodeId, vehicle, nodesLoaded) { n, vid, v, loaded ->
        when {
            n?.profile == NodeProfile.GroundStation && vid == null -> CockpitStatus.NoCarriedDrone
            v != null -> CockpitStatus.Ready
            n == null && loaded -> CockpitStatus.UnknownNode
            else -> CockpitStatus.Connecting
        }
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, CockpitStatus.Connecting)

    /** The node whose extensions the cockpit mounts (the flown drone). */
    val extensionNodeId: StateFlow<DeviceId?> = vehicleNodeId

    // ---- Settings ---------------------------------------------------------------------

    val settings: StateFlow<CockpitSettings> =
        settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, CockpitSettings())

    private val _maxBrightness = MutableStateFlow<Boolean?>(null)

    /** Screen at full brightness; starts from the settings default. */
    val maxBrightness: StateFlow<Boolean> = combine(_maxBrightness, settings) { override, s -> override ?: s.maxBrightnessDefault }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setMaxBrightness(on: Boolean) {
        _maxBrightness.value = on
    }

    fun setAltitudeReference(ref: AltitudeReference) = viewModelScope.launch { settingsRepo.setAltitudeReference(ref) }

    fun setDensity(density: CockpitDensity) = viewModelScope.launch { settingsRepo.setDensity(density) }

    fun setSplitRatio(ratio: Float) = viewModelScope.launch { settingsRepo.setSplitRatio(ratio) }

    fun setTelemetryPane(on: Boolean) = viewModelScope.launch { settingsRepo.setTelemetryPane(on) }

    // ---- Clocks -----------------------------------------------------------------------

    private fun ticker(periodMs: Long): Flow<Long> = flow {
        while (true) {
            emit(clock.nowMs())
            delay(periodMs)
        }
    }

    /**
     * Re-evaluation trigger for age-based state, and the one 2 Hz clock the band, alerts,
     * telemetry strip and minimap sample on, so their changes land in the same UI frame
     * instead of one frame each. Its value is only a trigger: a combine that also fires on
     * fresh telemetry reads [MonotonicClock.nowMs] itself, because a heartbeat stamped after
     * the last tick would otherwise get a negative age.
     */
    private val tick500: StateFlow<Long> = ticker(500).stateIn(viewModelScope, SharingStarted.Eagerly, clock.nowMs())

    // ---- STATUSTEXT tracking ----------------------------------------------------------

    private val criticalText = MutableStateFlow<ReceivedText?>(null)
    private val prearmText = MutableStateFlow<ReceivedText?>(null)

    init {
        viewModelScope.launch {
            telemetry.flatMapLatest { it?.statusText ?: emptyFlow() }.collect { st ->
                val now = clock.nowMs()
                when {
                    AlertEngine.isPrearm(st.text) -> prearmText.value = ReceivedText(AlertEngine.prearmBody(st.text), now)
                    st.severity <= SEVERITY_CRITICAL || AlertEngine.isFailsafeAnnouncement(st.severity, st.text) ->
                        criticalText.value = ReceivedText(st.text, now)
                }
            }
        }
    }

    // ---- Video ------------------------------------------------------------------------

    private val baseUrls: StateFlow<Map<DeviceId, String>> = vehicleNode.map { n ->
        if (n == null) return@map emptyMap()
        val ids = listOf(n.id) + n.reaches.filterIsInstance<Reach.ViaGround>().map { it.groundId }
        ids.mapNotNull { id -> endpoints.endpointFor(id)?.let { id to it.baseUrl.toString() } }.toMap()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val selectedStream = MutableStateFlow<String?>(null)
    private val switchingStream = MutableStateFlow<String?>(null)
    private val failedSources = MutableStateFlow<Set<VideoSource>>(emptySet())
    private val pipVisible = MutableStateFlow(false)

    private val candidates: StateFlow<List<VideoSource>> = combine(vehicleNode, baseUrls, selectedStream) { n, urls, stream ->
        if (n == null) emptyList() else videoSelector.candidates(n, stream) { urls[it] }.filter(videoSessions::canPlay)
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** First candidate that has not failed; on failure the next one is tried. */
    private val mainSource: StateFlow<VideoSource?> = combine(candidates, failedSources) { list, failed ->
        list.firstOrNull { it !in failed } ?: list.firstOrNull()
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val videoState: StateFlow<VideoState> = mainSource.flatMapLatest { src ->
        if (src == null) flowOf(VideoState.NoSource) else videoSessions.state(src)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, VideoState.NoSource)

    private val streams: StateFlow<List<StreamDescriptor>> = mainSource.flatMapLatest { src ->
        if (src is VideoSource.AgentWhep) streamCatalog.streams(src.node) else flowOf(emptyList())
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val video: StateFlow<VideoUi> = combine(mainSource, streams, selectedStream, switchingStream, pipVisible) { main, list, sel, switching, pip ->
        val selected = sel ?: list.firstOrNull { it.live }?.id
        val pipStream = if (pip && main is VideoSource.AgentWhep) {
            list.firstOrNull { it.id != selected && it.live && it.switchKind == StreamSwitchKind.Concurrent }
        } else null
        VideoUi(
            main = main,
            pip = pipStream?.let { (main as VideoSource.AgentWhep).copy(streamId = it.id) },
            streams = list,
            selectedStreamId = selected,
            switchingStreamId = switching,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, VideoUi(null, null, emptyList(), null, null))

    init {
        viewModelScope.launch {
            combine(mainSource, videoState) { src, state -> src to state }.collect { (src, state) ->
                if (src != null && state is VideoState.Failed && candidates.value.any { it !in failedSources.value && it != src }) {
                    failedSources.update { it + src }
                }
            }
        }
    }

    fun selectStream(streamId: String) {
        val list = streams.value
        val target = list.firstOrNull { it.id == streamId } ?: return
        val node = (mainSource.value as? VideoSource.AgentWhep)?.node ?: return
        val previous = video.value.selectedStreamId
        if (target.id == previous) return
        if (target.switchKind == StreamSwitchKind.Concurrent) {
            selectedStream.value = target.id
            return
        }
        switchingStream.value = target.id
        viewModelScope.launch {
            val result = withTimeoutOrNull(SWITCH_TIMEOUT_MS) { streamCatalog.switch(node, target.id) }
            switchingStream.value = null
            when (result) {
                is AgentResult.Ok -> selectedStream.value = when (val o = result.value) {
                    is StreamSwitchOutcome.UseCameraQuery -> o.cameraId
                    StreamSwitchOutcome.Switched -> target.id
                }
                else -> {
                    selectedStream.value = previous
                    _events.tryEmit(CockpitEvent.StreamSwitchFailed)
                }
            }
        }
    }

    fun cycleStream(delta: Int) {
        val list = streams.value.filter { it.live }
        if (list.size < 2) return
        val current = list.indexOfFirst { it.id == video.value.selectedStreamId }.coerceAtLeast(0)
        selectStream(list[(current + delta).mod(list.size)].id)
    }

    fun selectStreamIndex(index: Int) {
        streams.value.getOrNull(index)?.let { selectStream(it.id) }
    }

    fun togglePip() {
        pipVisible.update { !it }
    }

    private val recording: StateFlow<RecordingState?> = mainSource.flatMapLatest { src ->
        if (src == null) flowOf(null)
        else videoSessions.state(src).map { videoSessions.running(src)?.recording }.distinctUntilChanged()
            .flatMapLatest { rec -> rec?.state ?: flowOf(null) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** True when the current feed can record. */
    val canRecord: StateFlow<Boolean> = recording.map { it != null }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** True while the current feed records; quick settings read this rather than the whole band. */
    val recordingActive: StateFlow<Boolean> = recording.map { it is RecordingState.Recording }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun toggleRecording() {
        val src = mainSource.value ?: return
        val rec = videoSessions.running(src)?.recording ?: return
        viewModelScope.launch {
            val result = if (rec.state.value is RecordingState.Recording) rec.stop() else rec.start()
            if (result.isFailure) _events.tryEmit(CockpitEvent.RecordingFailed)
        }
    }

    // ---- Preflight --------------------------------------------------------------------

    val preflight: StateFlow<PreflightResult> = telemetry.flatMapLatest { t ->
        if (t == null) {
            combine(settings, tick500) { s, _ -> PreflightChecklist.evaluate(PreflightInputs(null, null, s.batteryBands, null, null, false, null), clock.nowMs()) }
        } else {
            combine(
                combine(t.gps, t.battery, t.ekf, t.home) { gps, batt, ekf, home -> listOf(gps, batt, ekf, home) },
                t.heartbeat,
                settings,
                prearmText,
                tick500,
            ) { parts, hb, s, prearm, _ ->
                val now = clock.nowMs()
                PreflightChecklist.evaluate(
                    PreflightInputs(
                        gps = parts[0] as com.altnautica.gcs.core.model.GpsState?,
                        batteryPct = (parts[1] as com.altnautica.gcs.core.model.BatteryState?)?.remainingPct,
                        bands = s.batteryBands,
                        ekf = parts[2] as com.altnautica.gcs.core.model.EkfState?,
                        home = parts[3] as com.altnautica.gcs.core.model.HomePosition?,
                        linkFresh = isLinkFresh(hb, now),
                        lastPrearmMs = prearm?.atMs,
                    ),
                    now,
                )
            }
        }
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, PreflightResult(emptyList()))

    // ---- Skills -----------------------------------------------------------------------

    val confirm = ConfirmController(
        scope = viewModelScope,
        clock = clock,
        failingChecks = { preflight.value.failing },
        isButtonHeld = { b -> router.buttons.value and (1 shl b) != 0 },
    )

    private val session = sessionFactory.create(vehicle, preflight.map { it.ready }.distinctUntilChanged(), viewModelScope)

    val loadout: StateFlow<Loadout> = bindings.loadout.stateIn(viewModelScope, SharingStarted.Eagerly, Loadout.DEFAULT)

    val slots: StateFlow<List<SlotUi>> = combine(loadout, session.barSkills, session.states) { l, bar, states ->
        l.slots.map { slot ->
            val skill = slot.skillId?.let { id -> bar.firstOrNull { it.id == id } }
            SlotUi(slot.index, skill, skill?.let { states[it.id] }, slot.keyChord, slot.gamepadButton)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The flight-mode picker entry, then every bar-eligible skill, with states: the drawer (the cockpit's palette). */
    val drawerSkills: StateFlow<List<SlotUi>> = combine(session.allSkills, session.barSkills, session.states) { all, bar, states ->
        val picker = all.firstOrNull { it.id == SetModeSkill.id }
        (listOfNotNull(picker) + bar).mapIndexed { i, s -> SlotUi(i, s, states[s.id], null, null) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val notices: SharedFlow<SkillNotice> = session.notices

    init {
        // Extension skills are offered their suggested bindings once, into free slots.
        viewModelScope.launch {
            session.allSkills.collect { skills -> if (skills.isNotEmpty()) bindings.seedExtensionDefaults(skills) }
        }
    }

    /** Runs [skillId]; `set-mode` without a target mode opens the flight-mode picker instead. */
    fun activate(skillId: String, args: Map<String, Any?> = emptyMap()) {
        if (skillId == SetModeSkill.id && args[SkillArgs.TARGET_MODE] == null) {
            openModePicker()
            return
        }
        viewModelScope.launch {
            val outcome = session.activate(skillId, args, confirm)
            if (outcome is ActivationOutcome.Completed && outcome.checklistOverridden) {
                Log.w(TAG, "Safety: preflight checklist overridden for $skillId on ${vehicleNodeId.value?.value}")
                _events.tryEmit(CockpitEvent.ChecklistOverridden)
            }
        }
    }

    // ---- Flight mode picker -----------------------------------------------------------

    private val _modePickerOpen = MutableStateFlow(false)

    private val modeList: Flow<Pair<List<FlightMode>, String?>> = vehicle.flatMapLatest { v ->
        if (v == null) {
            flowOf(emptyList<FlightMode>() to null)
        } else {
            combine(v.control.availableModes, v.telemetry.heartbeat.map { it?.mode?.name }.distinctUntilChanged()) { modes, current -> modes to current }
        }
    }

    /** The picker while open: every mode the flown vehicle advertises, with the `set-mode` state. */
    val modePicker: StateFlow<ModePickerUi?> = combine(_modePickerOpen, modeList, session.states) { open, (modes, current), states ->
        if (!open) return@combine null
        val state = states[SetModeSkill.id]
        ModePickerUi(modeChoices(modes, current), enabled = state?.enabled == true, reasonKey = state?.takeIf { !it.enabled }?.reasonKey)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun openModePicker() {
        _modePickerOpen.value = true
    }

    fun closeModePicker() {
        _modePickerOpen.value = false
    }

    /** Requests [modeName] through the `set-mode` skill; its CS-6 tier decides the confirm. */
    fun selectMode(modeName: String) {
        _modePickerOpen.value = false
        activate(SetModeSkill.id, mapOf(SkillArgs.TARGET_MODE to modeName))
    }

    // ---- Target designation -----------------------------------------------------------

    /** Extension target actions for the flown drone, with their states. */
    val targetActions: StateFlow<List<SlotUi>> = combine(extensionSkills.targetActions, session.context) { actions, ctx ->
        actions.mapIndexed { i, a -> SlotUi(i, a, activator.mergedState(ctx.vehicle?.id, a, ctx), null, null) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Runs a target action on a box drawn over the video. [bbox] is normalized to the
     * video frame (x, y, width, height in 0..1); the camera is the selected stream.
     */
    fun activateTarget(skill: ExtensionTargetActionSkill, bbox: Map<String, Float>) {
        viewModelScope.launch {
            val args = mapOf(
                "camera_id" to (video.value.selectedStreamId ?: PRIMARY_CAMERA),
                "bbox" to bbox,
            )
            val outcome = activator.activate(skill, session.context.value, args, confirm, session.allSkills.value)
            when (outcome) {
                is ActivationOutcome.Disabled -> outcome.reasonKey?.let { _events.tryEmit(CockpitEvent.TargetRefused(it)) }
                is ActivationOutcome.Refused -> _events.tryEmit(CockpitEvent.TargetRefused(outcome.reasonKey))
                else -> Unit
            }
        }
    }

    // ---- Manual control ---------------------------------------------------------------

    private val stickOn = MutableStateFlow(false)

    private val stickAllowed: StateFlow<Boolean> = combine(vehicle.flatMapLatest { it?.reach ?: flowOf(null) }, vehicleServices) { reach, svc ->
        svc != null && (reach == Reach.DirectFc || reach == Reach.Lan || reach is Reach.ViaGround)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val stick: StateFlow<StickChip> = combine(router.gamepadConnected, stickAllowed, stickOn) { pad, allowed, on ->
        when {
            !pad -> StickChip.NoGamepad
            !allowed -> StickChip.LinkBlocked
            else -> StickChip.Available(on)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, StickChip.NoGamepad)

    private val manualInput: StateFlow<ManualControlInput> = combine(router.axes, router.buttons) { axes, pressed ->
        // Mode 2: left stick throttle/yaw, right stick pitch/roll.
        ManualControlInput(
            pitch = axes.rightY,
            roll = axes.rightX,
            throttle = ((axes.leftY + 1f) / 2f).coerceIn(0f, 1f),
            yaw = axes.leftX,
            buttons = ReservedInputs.manualControlButtonMask(pressed),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ManualControlInput.NEUTRAL)

    private var streamerOwner: VehicleServices? = null

    init {
        viewModelScope.launch {
            combine(stick, vehicleServices) { chip, svc -> Pair((chip as? StickChip.Available)?.on == true, svc) }
                .collect { (on, svc) ->
                    stopStreamer()
                    if (on && svc != null) {
                        svc.manualControl.start(manualInput)
                        streamerOwner = svc
                    }
                }
        }
    }

    private fun stopStreamer() {
        streamerOwner?.manualControl?.stop()
        streamerOwner = null
    }

    fun toggleStickControl() {
        if (stick.value is StickChip.Available) stickOn.update { !it }
    }

    // ---- Band, alerts, strip ----------------------------------------------------------

    private val armedSince = MutableStateFlow<Long?>(null)

    init {
        viewModelScope.launch {
            telemetry.flatMapLatest { it?.heartbeat ?: flowOf(null) }
                .map { it?.armed }.distinctUntilChanged()
                .collect { armed ->
                    armedSince.value = when (armed) {
                        true -> armedSince.value ?: clock.nowMs()
                        else -> null
                    }
                }
        }
    }

    val band: StateFlow<BandState> = telemetry.flatMapLatest { t ->
        val coarse = if (t == null) flowOf(BandTelemetry.Empty) else combine(
            t.heartbeat, t.battery, t.gps, t.linkQuality, tick500,
        ) { hb, batt, gps, lq, _ ->
            val now = clock.nowMs()
            val age = hb?.let { heartbeatAgeMs(it.tMs, now) }
            BandTelemetry(
                armed = hb?.armed,
                mode = hb?.mode?.name,
                battery = batt?.remainingPct?.takeIf { it >= 0 },
                ttr = batt?.timeToReserveS,
                gpsFix = gps?.fix,
                sats = gps?.satellites,
                link = AlertEngine.linkHealth(hb?.tMs, now),
                ageDeci = age?.let { (it / 100).toInt().coerceAtMost(999) },
                rssi = lq?.rssiDbm,
                lq = lq?.lqPct,
            )
        }
        coarse
    }.combine(
        combine(node, vehicle.flatMapLatest { it?.reach ?: flowOf(null) }, settings, videoState, stick) { n, reach, s, vs, st ->
            BandContext(n, reach, s, vs, st)
        },
    ) { t, c -> t to c }
        .combine(combine(preflight, recording, armedSince) { p, r, a -> Triple(p, r, a) }) { (t, c), (p, r, a) ->
            BandState(
                nodeName = c.node?.name,
                profile = c.node?.profile ?: NodeProfile.Unknown,
                reach = c.reach?.badge(),
                armed = t.armed,
                modeName = t.mode,
                batteryPct = t.battery,
                timeToReserveS = t.ttr,
                batteryLevel = batteryBand(t.battery, c.settings),
                gpsFix = t.gpsFix,
                satellites = t.sats,
                link = t.link,
                linkAgeDeciS = t.ageDeci,
                rssiDbm = t.rssi,
                lqPct = t.lq,
                video = c.video,
                stick = c.stick,
                preflightPassed = p.passed,
                preflightTotal = p.total,
                recordingStartedAtMs = (r as? RecordingState.Recording)?.startedAtMs,
                armedSinceMs = a,
            )
        }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, BandState.Empty)

    private fun batteryBand(pct: Int?, s: CockpitSettings): BatteryBand = when {
        pct == null -> BatteryBand.Unknown
        pct < s.batteryBands.criticalPct -> BatteryBand.Critical
        pct < s.batteryBands.lowPct -> BatteryBand.Low
        else -> BatteryBand.Good
    }

    private val alertEngine = AlertEngine()

    val alerts: StateFlow<List<CockpitAlert>> = telemetry.flatMapLatest { t ->
        if (t == null) return@flatMapLatest flowOf(emptyList())
        combine(
            combine(t.heartbeat, t.systemHealth, t.battery, t.ekf, t.fence) { hb, sys, batt, ekf, fence -> AlertParts1(hb, sys, batt, ekf, fence) },
            combine(t.gps, t.vibration, videoState, criticalText, prearmText) { gps, vib, vs, crit, pre -> AlertParts2(gps, vib, vs is VideoState.Frozen, crit, pre) },
            settings,
            tick500,
        ) { a, b, s, _ ->
            val now = clock.nowMs()
            alertEngine.evaluate(
                AlertInputs(
                    armed = a.hb?.armed,
                    heartbeatMs = a.hb?.tMs,
                    systemHealth = a.sys,
                    batteryPct = a.batt?.remainingPct,
                    bands = s.batteryBands,
                    ekf = a.ekf,
                    fence = a.fence,
                    gps = b.gps,
                    vibration = b.vib,
                    videoFrozen = b.frozen,
                    criticalText = b.crit,
                    prearmText = b.pre,
                ),
                now,
            )
        }
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch {
            combine(alerts, settings) { a, s -> a to s }.collect { (a, s) -> announcer.onAlerts(a, s.audioCallouts) }
        }
        viewModelScope.launch {
            combine(telemetry.flatMapLatest { it?.heartbeat ?: flowOf(null) }.map { it?.mode?.name }.distinctUntilChanged(), settings) { m, s -> m to s }
                .collect { (mode, s) -> announcer.onModeChanged(mode, s.modeCallouts) }
        }
    }

    val strip: StateFlow<StripValues> = telemetry.flatMapLatest { t ->
        if (t == null) return@flatMapLatest flowOf(EMPTY_STRIP)
        tick500.map {
            val pos = t.position.value
            val home = t.home.value
            val vfr = t.vfr.value
            val gps = t.gps.value
            val mission = t.missionProgress.value
            val homeVec = if (pos != null && home != null) Geo.distanceBearing(pos.latDeg, pos.lonDeg, home.latDeg, home.lonDeg) else null
            StripValues(
                homeDistanceM = homeVec?.first,
                homeBearingDeg = homeVec?.second,
                climbMs = vfr?.climbMs,
                throttlePct = vfr?.throttlePct,
                missionSeq = mission?.currentSeq,
                missionTotal = mission?.total,
                satellites = gps?.satellites,
                hdop = gps?.hdop,
            )
        }
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, EMPTY_STRIP)

    /** Minimap vehicle, home and trail, sampled at 2 Hz; the trail keeps points at least 2 m apart. */
    val minimap: StateFlow<MinimapState> = telemetry.flatMapLatest { t ->
        if (t == null) return@flatMapLatest flowOf(MinimapState(null, null, emptyList()))
        val trail = ArrayDeque<GeoPoint>()
        tick500.map { now ->
            val pos = t.position.value
            val point = pos?.let { GeoPoint(it.latDeg, it.lonDeg) }
            if (point != null) {
                val last = trail.lastOrNull()
                if (last == null || Geo.distanceBearing(last.latDeg, last.lonDeg, point.latDeg, point.lonDeg).first >= TRAIL_MIN_STEP_M) {
                    trail.addLast(point)
                    if (trail.size > TRAIL_MAX_POINTS) trail.removeFirst()
                }
            }
            val heading = t.vfr.value?.headingDeg
            MinimapState(
                vehicle = point?.let { MapVehicle(it, heading, stale = now - pos.tMs > com.altnautica.gcs.feature.cockpit.hud.HudMath.STALE_MS) },
                home = t.home.value?.let { GeoPoint(it.latDeg, it.lonDeg) },
                trail = trail.toList(),
            )
        }
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, MinimapState(null, null, emptyList()))

    private val _minimapSwapped = MutableStateFlow(false)

    /** True when the map fills the screen and the video sits in the inset. */
    val minimapSwapped: StateFlow<Boolean> = _minimapSwapped.asStateFlow()

    fun toggleMinimapSwap() {
        _minimapSwapped.update { !it }
    }

    // ---- Overlays and input -----------------------------------------------------------

    private val _quickSettings = MutableStateFlow(false)
    val quickSettingsOpen: StateFlow<Boolean> = _quickSettings.asStateFlow()
    private val _drawer = MutableStateFlow(false)
    val drawerOpen: StateFlow<Boolean> = _drawer.asStateFlow()
    private val _preflightOpen = MutableStateFlow(false)
    val preflightOpen: StateFlow<Boolean> = _preflightOpen.asStateFlow()

    fun setQuickSettings(open: Boolean) {
        _quickSettings.value = open
    }

    fun setDrawer(open: Boolean) {
        _drawer.value = open
    }

    fun setPreflight(open: Boolean) {
        _preflightOpen.value = open
    }

    /** Closes every open sheet and panel; false when none was open. */
    private fun closeOverlays(): Boolean {
        val open = _quickSettings.value || _drawer.value || _preflightOpen.value || _modePickerOpen.value
        _quickSettings.value = false
        _drawer.value = false
        _preflightOpen.value = false
        _modePickerOpen.value = false
        return open
    }

    private val _events = MutableSharedFlow<CockpitEvent>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<CockpitEvent> = _events.asSharedFlow()

    private var inputJob: Job? = null

    private val capture = object : InputCapture {
        override fun consumesChord(chord: String) = CockpitInputMapper.consumesChord(chord, loadout.value)
        override val capturesEnter: Boolean get() = confirm.isOpen
    }

    /** Starts taking hardware input while the cockpit is visible. */
    fun attachInput() {
        router.capture = capture
        if (inputJob?.isActive == true) return
        inputJob = viewModelScope.launch { router.events.collect(::onInput) }
    }

    /**
     * Stops taking hardware input (the cockpit is no longer visible). The activity gets
     * no joystick events while stopped, so stick control turns off, the stream stops and
     * the router drops the last deflection and any held button.
     */
    fun detachInput() {
        if (router.capture === capture) router.capture = null
        inputJob?.cancel()
        inputJob = null
        stickOn.value = false
        stopStreamer()
        confirm.releaseHold()
        router.neutralize()
    }

    /** The window lost input focus (a system dialog, the shade): centre the sticks and release buttons. */
    fun onWindowFocusLost() {
        router.neutralize()
    }

    private fun onInput(event: InputEvent) {
        val armed = vehicle.value?.telemetry?.heartbeat?.value?.armed
        val action = when (event) {
            is InputEvent.Enter -> {
                confirm.onEnter(event.pressed)
                null
            }
            is InputEvent.Button -> {
                if (confirm.isOpen) {
                    confirm.onGamepadButton(event.button, event.pressed)
                    null
                } else if (event.pressed) {
                    CockpitInputMapper.onButtonPressed(event.button, router.buttons.value, loadout.value, armed)
                } else null
            }
            is InputEvent.Chord -> if (confirm.isOpen) null else CockpitInputMapper.onChord(event.chord, loadout.value, armed)
        } ?: return
        perform(action)
    }

    private fun perform(action: CockpitAction) {
        when (action) {
            is CockpitAction.ActivateSkill -> activate(
                action.skillId,
                action.gamepadButton?.let { mapOf(SkillArgs.GAMEPAD_BUTTON to it) } ?: emptyMap(),
            )
            CockpitAction.ToggleQuickSettings -> _quickSettings.update { !it }
            CockpitAction.ToggleSkillDrawer -> _drawer.update { !it }
            CockpitAction.Exit -> if (!closeOverlays()) _events.tryEmit(CockpitEvent.ExitRequested)
            is CockpitAction.CycleStream -> cycleStream(action.delta)
            is CockpitAction.SelectStream -> selectStreamIndex(action.index)
            CockpitAction.TogglePip -> togglePip()
        }
    }

    override fun onCleared() {
        detachInput()
        stopStreamer()
        confirm.cancel()
        announcer.shutdown()
    }

    private data class BandTelemetry(
        val armed: Boolean?,
        val mode: String?,
        val battery: Int?,
        val ttr: Int?,
        val gpsFix: com.altnautica.gcs.core.model.GpsFix?,
        val sats: Int?,
        val link: com.altnautica.gcs.feature.cockpit.alerts.LinkHealth,
        val ageDeci: Int?,
        val rssi: Int?,
        val lq: Int?,
    ) {
        companion object {
            val Empty = BandTelemetry(null, null, null, null, null, null, com.altnautica.gcs.feature.cockpit.alerts.LinkHealth.None, null, null, null)
        }
    }

    private data class BandContext(
        val node: NodeSummary?,
        val reach: Reach?,
        val settings: CockpitSettings,
        val video: VideoState,
        val stick: StickChip,
    )

    private data class AlertParts1(
        val hb: com.altnautica.gcs.core.model.HeartbeatState?,
        val sys: com.altnautica.gcs.core.model.SystemHealth?,
        val batt: com.altnautica.gcs.core.model.BatteryState?,
        val ekf: com.altnautica.gcs.core.model.EkfState?,
        val fence: com.altnautica.gcs.core.model.FenceStatus?,
    )

    private data class AlertParts2(
        val gps: com.altnautica.gcs.core.model.GpsState?,
        val vib: com.altnautica.gcs.core.model.VibrationState?,
        val frozen: Boolean,
        val crit: ReceivedText?,
        val pre: ReceivedText?,
    )

    companion object {
        private const val TAG = "Cockpit"

        /** Camera id of the agent's primary stream when no stream tab is selected. */
        private const val PRIMARY_CAMERA = "main"
        private const val SWITCH_TIMEOUT_MS = 3_000L
        private const val TRAIL_MIN_STEP_M = 2f
        private const val TRAIL_MAX_POINTS = 600
        private val EMPTY_STRIP = StripValues(null, null, null, null, null, null, null, null)

        /** Commandable links first: direct, LAN, via ground, phone radio, cloud live, cloud queue. */
        internal fun reachRank(r: Reach): Int = when (r) {
            Reach.DirectFc -> 0
            Reach.Lan -> 1
            is Reach.ViaGround -> 2
            Reach.PhoneRadio -> 3
            Reach.CloudLive -> 4
            Reach.CloudQueue -> 5
            is Reach.Blocked -> 6
        }
    }
}

/** One-off cockpit events for snackbars and dialogs. */
sealed interface CockpitEvent {
    data object ExitRequested : CockpitEvent
    data object StreamSwitchFailed : CockpitEvent
    data object RecordingFailed : CockpitEvent
    data object ChecklistOverridden : CockpitEvent

    /** A target action refused before running; [reasonKey] is a skill reason key or extension text. */
    data class TargetRefused(val reasonKey: String) : CockpitEvent
}
