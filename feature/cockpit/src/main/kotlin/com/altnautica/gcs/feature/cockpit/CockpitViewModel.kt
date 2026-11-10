package com.altnautica.gcs.feature.cockpit

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.agent.AgentEndpoints
import com.altnautica.gcs.core.agent.nav.AutonomyFeedApi
import com.altnautica.gcs.core.agent.node.LocalNodeApis
import com.altnautica.gcs.core.agent.pilot.PilotWire
import com.altnautica.gcs.feature.cockpit.autonomy.CockpitAutonomy
import com.altnautica.gcs.core.model.CommandResult
import com.altnautica.gcs.core.model.SkillState
import com.altnautica.gcs.core.agent.video.StreamCatalog
import com.altnautica.gcs.core.data.cockpit.AltitudeReference
import com.altnautica.gcs.core.data.cockpit.CockpitDensity
import com.altnautica.gcs.core.data.cockpit.CockpitSettings
import com.altnautica.gcs.core.data.cockpit.CockpitSettingsRepository
import com.altnautica.gcs.core.data.controller.ControllerSettingsRepository
import com.altnautica.gcs.core.designsystem.tokens.AdosColorRoles
import com.altnautica.gcs.core.extensions.data.ExtensionRepository
import com.altnautica.gcs.core.extensions.data.PerceptionFeed
import com.altnautica.gcs.core.extensions.skills.ExtensionSkillContributor
import com.altnautica.gcs.core.extensions.skills.ExtensionTargetActionSkill
import com.altnautica.gcs.core.link.services.VehicleServicesProvider
import com.altnautica.gcs.core.map.GeoPoint
import com.altnautica.gcs.core.model.AvoidMode
import com.altnautica.gcs.core.model.CockpitAlert
import com.altnautica.gcs.core.model.ControlMode
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.FlightMode
import com.altnautica.gcs.core.model.MissionState
import com.altnautica.gcs.core.model.NodeSource
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.model.PilotAction
import com.altnautica.gcs.core.model.PilotOwner
import com.altnautica.gcs.core.model.PilotProfile
import com.altnautica.gcs.core.model.PilotState
import com.altnautica.gcs.core.model.Reach
import com.altnautica.gcs.core.model.Skill
import com.altnautica.gcs.core.model.SkillCategory
import com.altnautica.gcs.core.model.Vehicle
import com.altnautica.gcs.core.model.VehicleCommand
import com.altnautica.gcs.core.model.VehicleProvider
import com.altnautica.gcs.core.model.VehicleTelemetry
import com.altnautica.gcs.core.model.VideoState
import com.altnautica.gcs.core.skills.ActivationOutcome
import com.altnautica.gcs.core.skills.ConfirmResult
import com.altnautica.gcs.core.skills.MonotonicClock
import com.altnautica.gcs.core.skills.SkillActivator
import com.altnautica.gcs.core.skills.SkillConfirmer
import com.altnautica.gcs.core.skills.SkillNotice
import com.altnautica.gcs.core.skills.SkillSessionFactory
import com.altnautica.gcs.core.skills.binding.BindingStore
import com.altnautica.gcs.core.skills.binding.Ds4Bindings
import com.altnautica.gcs.core.skills.binding.Loadout
import com.altnautica.gcs.core.skills.builtin.BuiltinIds
import com.altnautica.gcs.core.skills.builtin.FlyHereSkill
import com.altnautica.gcs.core.skills.builtin.SetModeSkill
import com.altnautica.gcs.core.skills.builtin.SkillArgs
import com.altnautica.gcs.core.skills.cockpit.CockpitSummary
import com.altnautica.gcs.core.skills.cockpit.CockpitSummaryRegistry
import com.altnautica.gcs.core.skills.controls.ControlsRepository
import com.altnautica.gcs.core.skills.input.AdosInputRouter
import com.altnautica.gcs.core.skills.input.InputCapture
import com.altnautica.gcs.core.skills.input.InputEvent
import com.altnautica.gcs.core.skills.input.PadSnapshot
import com.altnautica.gcs.core.skills.input.controller.ControllerManager
import com.altnautica.gcs.core.skills.spec.CockpitSpecRepository
import com.altnautica.gcs.core.skills.spec.SetupStringsSpec
import com.altnautica.gcs.core.video.VideoSessionManager
import com.altnautica.gcs.core.video.VideoSourceSelector
import com.altnautica.gcs.feature.cockpit.alerts.AlertAnnouncer
import com.altnautica.gcs.feature.cockpit.alerts.AlertEngine
import com.altnautica.gcs.feature.cockpit.alerts.CockpitAlertsController
import com.altnautica.gcs.feature.cockpit.alerts.ControllerFeedback
import com.altnautica.gcs.feature.cockpit.alerts.ReceivedText
import com.altnautica.gcs.feature.cockpit.band.CockpitHudFlows
import com.altnautica.gcs.feature.cockpit.band.BatteryReserve
import com.altnautica.gcs.feature.cockpit.chip.ChipButton
import com.altnautica.gcs.feature.cockpit.chip.ChipContext
import com.altnautica.gcs.feature.cockpit.chip.FlightStateChipResolver
import com.altnautica.gcs.feature.cockpit.chip.FlightStateChipUi
import com.altnautica.gcs.feature.cockpit.confirm.ConfirmController
import com.altnautica.gcs.feature.cockpit.flow.FlowUi
import com.altnautica.gcs.feature.cockpit.hud.layers.ArRules
import com.altnautica.gcs.feature.cockpit.input.CockpitAction
import com.altnautica.gcs.feature.cockpit.input.CockpitInputMapper
import com.altnautica.gcs.feature.cockpit.launch.LaunchDraft
import com.altnautica.gcs.feature.cockpit.launch.LaunchUi
import com.altnautica.gcs.feature.cockpit.launch.LaunchAfter
import com.altnautica.gcs.feature.cockpit.launch.NedPoint
import com.altnautica.gcs.feature.cockpit.launch.launchArgs
import com.altnautica.gcs.feature.cockpit.launch.launchGotoNed
import com.altnautica.gcs.feature.cockpit.manual.CockpitStickController
import com.altnautica.gcs.feature.cockpit.modes.ModePickerUi
import com.altnautica.gcs.feature.cockpit.modes.ModeSheetActions
import com.altnautica.gcs.feature.cockpit.modes.ModeSheetUi
import com.altnautica.gcs.feature.cockpit.modes.modeChoices
import com.altnautica.gcs.feature.cockpit.pilot.CockpitControllerAction
import com.altnautica.gcs.feature.cockpit.pilot.CockpitPilot
import com.altnautica.gcs.feature.cockpit.pilot.ControllerContext
import com.altnautica.gcs.feature.cockpit.pilot.ControllerHold
import com.altnautica.gcs.feature.cockpit.pilot.Ds4ActionDispatcher
import com.altnautica.gcs.feature.cockpit.pilot.OnScreenSticks
import com.altnautica.gcs.feature.cockpit.pilot.PilotStickStreamer
import com.altnautica.gcs.feature.cockpit.pilot.StickConditioning
import com.altnautica.gcs.feature.cockpit.pilot.StickRoute
import com.altnautica.gcs.feature.cockpit.pilot.pilotActionEvent
import com.altnautica.gcs.feature.cockpit.pilot.sendModeSheetAction
import com.altnautica.gcs.feature.cockpit.preflight.ArmRequest
import com.altnautica.gcs.feature.cockpit.preflight.FirstArmGate
import com.altnautica.gcs.feature.cockpit.preflight.PreflightContext
import com.altnautica.gcs.feature.cockpit.preflight.PreflightResult
import com.altnautica.gcs.feature.cockpit.preflight.cockpitPreflight
import com.altnautica.gcs.feature.cockpit.skillbar.CockpitTargetActions
import com.altnautica.gcs.feature.cockpit.skillbar.PickerChip
import com.altnautica.gcs.feature.cockpit.skillbar.PickerTab
import com.altnautica.gcs.feature.cockpit.skillbar.SkillPickerActions
import com.altnautica.gcs.feature.cockpit.skillbar.SkillPickerModel
import com.altnautica.gcs.feature.cockpit.skillbar.SkillPickerUi
import com.altnautica.gcs.feature.cockpit.skillbar.firesDirectly
import com.altnautica.gcs.feature.cockpit.video.CockpitVideoController
import com.altnautica.gcs.feature.cockpit.autonomy.TargetState
import com.altnautica.gcs.feature.cockpit.chrome.CardPage
import com.altnautica.gcs.feature.cockpit.chrome.FlightValues
import com.altnautica.gcs.feature.cockpit.chrome.GimbalRateController
import com.altnautica.gcs.feature.cockpit.chrome.StatusItem
import com.altnautica.gcs.feature.cockpit.chrome.StatusModel
import com.altnautica.gcs.core.skills.hud.SpecText
import com.altnautica.gcs.core.skills.hud.aglMark
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlin.math.cos
import kotlin.math.hypot
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.altnautica.gcs.core.link.mission.MissionMirrorSource
import com.altnautica.gcs.core.map.MapOverlays

/** Severity at or below which a STATUSTEXT raises a critical alert (MAV_SEVERITY_CRITICAL). */
private const val SEVERITY_CRITICAL = 2

/** A text seen again on the other path within this window is the same message. */
private const val DUPLICATE_TEXT_MS = 5_000L

/** How long UI-only flows keep running after their last collector leaves. */
internal const val UI_STOP_MS = 5_000L

/** The cockpit views Triangle (or `V`) cycles through. */
enum class CockpitView(val id: String) { Pilot("pilot"), Map("map"), Split("split"), Vision("vision") }

/** Cockpit for one node. A ground station's cockpit flies its carried drone. */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = CockpitViewModel.Factory::class)
class CockpitViewModel @AssistedInject constructor(
    @Assisted nodeIdValue: String,
    nodeSources: Set<@JvmSuppressWildcards NodeSource>,
    vehicles: VehicleProvider,
    services: VehicleServicesProvider,
    sessionFactory: SkillSessionFactory,
    private val bindings: BindingStore,
    private val router: AdosInputRouter,
    private val settingsRepo: CockpitSettingsRepository,
    controllerSettings: ControllerSettingsRepository,
    controllerManager: ControllerManager,
    private val summaries: CockpitSummaryRegistry,
    videoSelector: VideoSourceSelector,
    videoSessions: VideoSessionManager,
    endpoints: AgentEndpoints,
    streamCatalog: StreamCatalog,
    private val announcer: AlertAnnouncer,
    private val clock: MonotonicClock,
    activator: SkillActivator,
    extensionSkills: ExtensionSkillContributor,
    private val extensions: ExtensionRepository,
    perception: PerceptionFeed,
    private val localApis: Set<@JvmSuppressWildcards LocalNodeApis>,
    private val spec: CockpitSpecRepository,
    private val controls: ControlsRepository,
    missionMirror: MissionMirrorSource,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        /** Takes the raw id: Dagger cannot pass inline value classes through assisted factories. */
        fun create(nodeId: String): CockpitViewModel
    }

    private val cockpitNodeId = DeviceId(nodeIdValue)
    private val nodeFlows = CockpitNodeFlows(viewModelScope, cockpitNodeId, nodeSources, vehicles, services)

    private val _events = MutableSharedFlow<CockpitEvent>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<CockpitEvent> = _events.asSharedFlow()

    /** The cockpit spec's shared strings, provided to the composition. */
    val specStrings: SetupStringsSpec get() = spec.setupStrings

    /** Spec strings with the shared interpolation rules (refusals, link loss, AGL marks). */
    private val specText by lazy { SpecText(spec.setupStrings) }
    internal val cockpitSpec: CockpitSpecRepository get() = spec

    // ---- Node and vehicle -------------------------------------------------------------

    /** The node the cockpit was opened for. */
    val node: StateFlow<NodeSummary?> = nodeFlows.node
    private val vehicleNodeId: StateFlow<DeviceId?> = nodeFlows.vehicleNodeId
    val vehicle: StateFlow<Vehicle?> = nodeFlows.vehicle

    /** Per-topic telemetry for the HUD, which reads each flow inside its draw pass. */
    val telemetry: StateFlow<VehicleTelemetry?> = nodeFlows.telemetry
    val status: StateFlow<CockpitStatus> = nodeFlows.status

    /** The node whose extensions the cockpit mounts (the flown drone). */
    val extensionNodeId: StateFlow<DeviceId?> = vehicleNodeId

    // ---- Settings ---------------------------------------------------------------------

    val settings: StateFlow<CockpitSettings> =
        settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, CockpitSettings())

    private val _maxBrightness = MutableStateFlow<Boolean?>(null)

    /** Screen at full brightness; starts from the settings default. */
    val maxBrightness: StateFlow<Boolean> = combine(_maxBrightness, settings) { override, s -> override ?: s.maxBrightnessDefault }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(UI_STOP_MS), false)

    fun setMaxBrightness(on: Boolean) {
        _maxBrightness.value = on
    }

    fun setAltitudeReference(ref: AltitudeReference) = viewModelScope.launch { settingsRepo.setAltitudeReference(ref) }

    fun setDensity(density: CockpitDensity) = viewModelScope.launch { settingsRepo.setDensity(density) }

    fun setSplitRatio(ratio: Float) = viewModelScope.launch { settingsRepo.setSplitRatio(ratio) }

    fun setTelemetryPane(on: Boolean) = viewModelScope.launch { settingsRepo.setTelemetryPane(on) }

    /** On-screen sticks enabled in Settings › Controls (default off). */
    val onScreenSticksEnabled: StateFlow<Boolean> = controls.onScreenSticks.stateIn(viewModelScope, SharingStarted.Eagerly, false)

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

    /** Recent FC status text at WARNING or ERROR severity, newest last, for the status pill. */
    private val warningTexts = MutableStateFlow<List<ReceivedText>>(emptyList())

    init {
        viewModelScope.launch {
            telemetry.flatMapLatest { it?.statusText ?: emptyFlow() }.collect { st -> onStatusText(st.severity, st.text, clock.nowMs(), relayed = false) }
        }
    }

    /**
     * One FC status text, from the MAVLink link or [relayed] by the onboard pilot, classified
     * once: a prearm reason, a critical or failsafe alert, or a pill warning. The pilot relays
     * its recent texts on every frame, so a relayed text is placed by the time it was produced:
     * a copy of a text already held (same text within [DUPLICATE_TEXT_MS]) changes nothing, and
     * an older text never replaces a newer held one. Re-feeding a relay is therefore a no-op.
     */
    private fun onStatusText(severity: Int, text: String, atMs: Long, relayed: Boolean) {
        when {
            AlertEngine.isPrearm(text) -> {
                val body = AlertEngine.prearmBody(text)
                if (!relayed || replacesHeld(prearmText.value, body, atMs)) prearmText.value = ReceivedText(body, atMs)
            }
            severity <= SEVERITY_CRITICAL || AlertEngine.isFailsafeAnnouncement(severity, text) ->
                if (!relayed || replacesHeld(criticalText.value, text, atMs)) criticalText.value = ReceivedText(text, atMs)
            severity <= StatusModel.MAV_SEVERITY_WARNING -> warningTexts.update { list ->
                val held = list.firstOrNull { it.text == text }
                if (relayed && held != null && atMs - held.atMs <= DUPLICATE_TEXT_MS) list
                else (list.filter { it.text != text } + ReceivedText(text, atMs)).sortedBy { it.atMs }.takeLast(StatusModel.WARNING_TEXT_MAX)
            }
        }
    }

    /** True when a relayed [text] produced at [atMs] replaces [held]: it is newer, and not a copy of the held text. */
    private fun replacesHeld(held: ReceivedText?, text: String, atMs: Long): Boolean =
        held == null || (atMs > held.atMs && !(held.text == text && atMs - held.atMs <= DUPLICATE_TEXT_MS))

    // ---- Video ------------------------------------------------------------------------

    private val videoCtl = CockpitVideoController(viewModelScope, nodeFlows.vehicleNode, videoSelector, videoSessions, endpoints, streamCatalog, _events)
    val videoState: StateFlow<VideoState> = videoCtl.videoState
    val video: StateFlow<VideoUi> = videoCtl.video
    fun selectStream(streamId: String) = videoCtl.selectStream(streamId)
    fun cycleStream(delta: Int) = videoCtl.cycleStream(delta)
    fun selectStreamIndex(index: Int) = videoCtl.selectStreamIndex(index)
    fun togglePip() = videoCtl.togglePip()
    fun swapPip() = videoCtl.swapPip()

    /** True when the current feed can record. */
    val canRecord: StateFlow<Boolean> = videoCtl.canRecord

    /** True while the current feed records; quick settings read this rather than the whole band. */
    val recordingActive: StateFlow<Boolean> = videoCtl.recordingActive
    fun toggleRecording() = videoCtl.toggleRecording()

    // ---- Onboard pilot ----------------------------------------------------------------

    /** The navigation stream's `pilot` kind, bridged in once the autonomy feed exists. */
    private val navPilot = MutableStateFlow<PilotState?>(null)

    private suspend fun autonomyApi(id: DeviceId): AutonomyFeedApi? =
        localApis.firstNotNullOfOrNull { it.autonomy(id) } ?: extensions.api(id)

    internal val pilot = CockpitPilot(viewModelScope, clock, vehicleNodeId, ::autonomyApi, navPilot, vehicle.flatMapLatest { it?.reach ?: flowOf(null) })

    /** The onboard pilot's state, or null for a vehicle without one. */
    val pilotState: StateFlow<PilotState?> = pilot.state

    /** The return reserve from the agent's battery engine for the flown vehicle, or null without one. */
    val batteryReserve: StateFlow<BatteryReserve?> = vehicleNodeId.flatMapLatest { id ->
        if (id == null) return@flatMapLatest flowOf<BatteryReserve?>(null)
        flow {
            while (true) {
                val r = autonomyApi(id)?.let { runCatching { it.battery() }.getOrNull() }
                emit(BatteryReserve.from((r as? com.altnautica.gcs.core.agent.http.AgentResult.Ok)?.value))
                delay(BatteryReserve.POLL_MS)
            }
        }
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(UI_STOP_MS), null)

    init {
        // The pilot's relayed FC texts go through the same classification as the link's.
        viewModelScope.launch {
            pilot.state.collect { ps ->
                ps?.statusTexts?.forEach { onStatusText(it.severity, it.text, ps.receivedAtMs - (it.ageMs ?: 0L), relayed = true) }
            }
        }
    }

    // ---- Preflight --------------------------------------------------------------------

    private val preflightContext = MutableStateFlow(PreflightContext(null, false, null))

    val preflight: StateFlow<PreflightResult> = cockpitPreflight(viewModelScope, clock, telemetry, settings, prearmText, preflightContext, tick500)

    private val armGate = FirstArmGate()
    private val _armGateOpen = MutableStateFlow(false)

    /** True while the checklist sheet is open because an arm waits on it. */
    val armGateOpen: StateFlow<Boolean> = _armGateOpen.asStateFlow()

    // ---- Skills -----------------------------------------------------------------------

    val confirm = ConfirmController(
        scope = viewModelScope,
        clock = clock,
        failingChecks = { preflight.value.failing },
        isButtonHeld = { b -> router.buttons.value and (1 shl b) != 0 },
        onConfirmed = { feedback.onConfirmed() },
    )

    private val overlays = CockpitOverlays(confirm)

    /** The first-connect controller tour card on screen (0..[TOUR_STEPS]-1), or null. */
    val tourStep: StateFlow<Int?> = overlays.tour.asStateFlow()

    init {
        // The first pad connected anywhere opens the three-card tour once, here as in Settings,
        // but only on the ground: in the air it waits for the landing, and a takeoff puts an
        // open tour away unseen, so the pad never leaves flight control for it.
        overlays.onTourClosed = ::finishTour
        viewModelScope.launch {
            combine(router.gamepadConnected, controls.tourSeen, tick500) { pad, seen, _ -> (pad && !seen) to airborne() }
                .distinctUntilChanged()
                .collect { (show, air) ->
                    when {
                        air -> overlays.tour.value = null
                        show && overlays.tour.value == null -> overlays.tour.value = 0
                    }
                }
        }
    }

    /** True while armed and not landed: from the pilot when it reports, else from the heartbeat. */
    private fun airborne(): Boolean =
        pilot.state.value?.let { it.armed && !it.landed } ?: (vehicle.value?.telemetry?.heartbeat?.value?.armed == true)

    /** Next card; past the last one the tour is marked seen. */
    fun nextTourStep() {
        val step = overlays.tour.value ?: return
        if (step + 1 < TOUR_STEPS) overlays.tour.value = step + 1 else finishTour()
    }

    fun finishTour() {
        overlays.tour.value = null
        viewModelScope.launch { controls.setTourSeen(true) }
    }

    private val session = sessionFactory.create(
        vehicle,
        preflight.map { it.ready }.distinctUntilChanged(),
        viewModelScope,
        pilot = pilot.state,
        pilotLink = pilot.link,
        stopReason = { pluginId -> extensions.stopReason(vehicleNodeId.value ?: cockpitNodeId, pluginId) },
    )

    val loadout: StateFlow<Loadout> = bindings.loadout.stateIn(viewModelScope, SharingStarted.Eagerly, Loadout.defaults(spec.builtins))

    val notices: SharedFlow<SkillNotice> = session.notices

    /** Every skill's current state by id: enabled flags for the follow rail. */
    val skillStates: StateFlow<Map<String, SkillState>> = session.states

    init {
        // Extension skills are offered their suggested keys once, into free slots.
        viewModelScope.launch {
            session.allSkills.collect { skills -> if (skills.isNotEmpty()) bindings.seedExtensionDefaults(skills) }
        }
        viewModelScope.launch {
            telemetry.flatMapLatest { it?.heartbeat ?: flowOf(null) }.map { it?.armed == true }.distinctUntilChanged().collect { armed ->
                if (armed) armGate.onArmed()
            }
        }
    }

    /** A skill run by a completed controller hold: the hold itself confirmed it. */
    private val holdConfirmed = SkillConfirmer { ConfirmResult.Confirmed() }

    /** Runs [skillId]; `set-mode` without a target mode opens the Mode sheet instead. */
    fun activate(skillId: String, args: Map<String, Any?> = emptyMap()) {
        if (skillId == SetModeSkill.id && args[SkillArgs.TARGET_MODE] == null) {
            openModeSheet()
            return
        }
        val heldBy = args[ARG_HELD] == true
        if ((skillId == BuiltinIds.ARM || skillId == BuiltinIds.TAKEOFF) && !armGate.request(ArmRequest.Skill(skillId, args))) {
            overlays.preflight.value = true
            _armGateOpen.value = true
            return
        }
        viewModelScope.launch {
            val outcome = session.activate(skillId, args - ARG_HELD, if (heldBy) holdConfirmed else confirm)
            if (outcome is ActivationOutcome.Completed && outcome.checklistOverridden) {
                Log.w(TAG, "Safety: preflight checklist overridden for $skillId on ${vehicleNodeId.value?.value}")
                _events.tryEmit(CockpitEvent.ChecklistOverridden)
            }
        }
    }

    /** The checklist's Continue: with nothing blocking, the parked arm or launch runs. */
    fun continuePreflight() {
        val parked = armGate.proceed(preflight.value) ?: return
        overlays.preflight.value = false
        _armGateOpen.value = false
        when (parked) {
            is ArmRequest.Skill -> activate(parked.skillId, parked.args)
            is ArmRequest.Launch -> sendLaunch(parked.args)
        }
    }

    // ---- Skill picker -----------------------------------------------------------------

    private val pickerTab = MutableStateFlow(PickerTab.Behaviors)
    private val pickerSelected = MutableStateFlow<String?>(null)
    val pickerOpen: StateFlow<Boolean> = overlays.picker.asStateFlow()

    val pickerChip: StateFlow<PickerChip> = combine(session.pickerSkills, session.states, pilot.state) { skills, states, p ->
        SkillPickerModel.chip(skills, states, p)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(UI_STOP_MS), PickerChip.Idle)

    val picker: StateFlow<SkillPickerUi?> = combine(overlays.picker, session.pickerSkills, session.states, pickerTab, pickerSelected) { open, skills, states, tab, sel ->
        if (!open) return@combine null
        val populated = SkillPickerModel.populated(skills)
        val shown = if (tab in populated || populated.isEmpty()) tab else populated.first()
        SkillPickerUi(shown, SkillPickerModel.groups(skills, states, shown), sel, populated)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(UI_STOP_MS), null)

    val pickerActions = object : SkillPickerActions {
        override fun onTab(tab: PickerTab) {
            pickerTab.value = tab
        }

        override fun onSelect(skillId: String) {
            val skill = session.pickerSkills.value.firstOrNull { it.id == skillId } ?: return
            if (firesDirectly(skill)) activate(skillId) else pickerSelected.value = skillId
        }

        override fun onGo(skillId: String) = activate(skillId)

        override fun onStop(skillId: String) {
            viewModelScope.launch { session.stop(skillId) }
        }

        override fun onDismiss() = setPicker(false)
    }

    fun setPicker(open: Boolean) {
        overlays.picker.value = open
        if (open) {
            val active = (pickerChip.value as? PickerChip.Active)?.skill
            pickerSelected.value = active?.id ?: pickerSelected.value
            active?.let { SkillPickerModel.tabOf(it) }?.let { pickerTab.value = it }
        }
    }

    private fun movePickerFocus(step: Int) {
        val next = SkillPickerModel.move(session.pickerSkills.value, pickerSelected.value, step) ?: return
        pickerSelected.value = next.id
        SkillPickerModel.tabOf(next)?.let { pickerTab.value = it }
    }

    /** Circle: close the top overlay, else stop the running skill (the pilot holds). */
    private fun stopOrBack() {
        if (overlays.closeTop()) return
        val running = session.activeMotionSkill()
            ?: (pickerChip.value as? PickerChip.Active)?.skill
            ?: (pickerChip.value as? PickerChip.ActiveStale)?.skill
        running?.let { viewModelScope.launch { session.stop(it.id) } }
    }

    // ---- Manual control ---------------------------------------------------------------

    private val controlRows = controls.bindings.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private val boundButtons: StateFlow<Int> = controlRows.map { Ds4Bindings.boundButtonMask(spec.ds4, it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    private val conditioning: StateFlow<StickConditioning> = combine(controls.stickMode, controls.deadzone, controls.calibrations) { mode, dz, cal ->
        StickConditioning(mode = mode, deadzone = dz, calibrations = cal, triggerPressed = spec.ds4.stream.triggerPressedThreshold)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, StickConditioning())

    /** The on-screen sticks; they stream only while a finger holds a pad. */
    val onScreenSticks = OnScreenSticks()

    private fun streamPad(): PadSnapshot {
        val screen = onScreenSticks.snapshot(clock.nowMs())
        return if (screen.deviceId != null) screen else router.snapshot.value
    }

    private val streamer = PilotStickStreamer(viewModelScope, clock, ::streamPad, conditioning, pilot.stickSink)

    private val sticks = CockpitStickController(
        viewModelScope, clock, vehicle, nodeFlows.vehicleServices, router, controllerSettings, controllerManager,
        boundButtons, pilot.route, streamer.streaming,
    )

    fun toggleStickControl() = sticks.toggle()

    /** Stick control in every mode for the flown vehicle; null without a vehicle. */
    val stickAllModes: StateFlow<Boolean?> = sticks.allModes

    fun setStickAllModes(on: Boolean) = sticks.setAllModes(on)

    // ---- Mode sheet -------------------------------------------------------------------

    private val modeList: Flow<Pair<List<FlightMode>, String?>> = vehicle.flatMapLatest { v ->
        if (v == null) {
            flowOf(emptyList<FlightMode>() to null)
        } else {
            combine(v.control.availableModes, v.telemetry.heartbeat.map { it?.mode?.name }.distinctUntilChanged()) { modes, current -> modes to current }
        }
    }

    /** The Mode sheet while open: pilot settings, handover and every firmware mode. */
    val modeSheet: StateFlow<ModeSheetUi?> = combine(
        overlays.modePicker,
        modeList,
        session.states,
        combine(pilot.state, pilot.linked, vehicle.flatMapLatest { it?.reach ?: flowOf(null) }) { p, l, r -> Triple(p, l, r) },
    ) { open, (modes, current), states, (p, linked, reach) ->
        if (!open) return@combine null
        val state = states[SetModeSkill.id]
        ModeSheetUi(
            profile = p?.profile,
            controlMode = p?.controlMode,
            avoid = p?.avoid,
            owner = p?.owner,
            vehicleClass = p?.vehicleClass,
            elrsAvailable = reach is Reach.ViaGround,
            pilotLinked = linked,
            fcModes = ModePickerUi(modeChoices(modes, current, sticks::endsStick), enabled = state?.enabled == true, reasonKey = state?.takeIf { !it.enabled }?.reasonKey),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(UI_STOP_MS), null)

    /** Sends [action] to the pilot and reports a refusal, a missing answer or a down channel. */
    private fun pilotAction(action: PilotAction, stopsMotion: Boolean = false) {
        viewModelScope.launch {
            val result = sendModeSheetAction(action, pilot.link.value, stopMotion = { if (stopsMotion) stopMotionSkill() })
            pilotActionEvent(result, specText)?.let(_events::tryEmit)
        }
    }

    private suspend fun stopMotionSkill() {
        session.activeMotionSkill()?.let { session.stop(it.id) }
    }

    val modeSheetActions = object : ModeSheetActions {
        override fun onProfile(profile: PilotProfile) = pilotAction(PilotAction.profile(profile), stopsMotion = true)
        override fun onControlMode(mode: ControlMode) = pilotAction(PilotAction.controlMode(mode), stopsMotion = true)
        override fun onAvoid(mode: AvoidMode) = pilotAction(PilotAction.avoid(mode), stopsMotion = true)
        override fun onHandToFc(mode: String) = pilotAction(PilotAction(PilotAction.HAND_TO_FC, mapOf("mode" to mode)), stopsMotion = true)
        override fun onTakeControl() = pilotAction(PilotAction(PilotAction.TAKE_CONTROL))
        override fun onFcMode(name: String) {
            overlays.modePicker.value = false
            activate(SetModeSkill.id, mapOf(SkillArgs.TARGET_MODE to name))
        }
        override fun onDismiss() = closeModeSheet()
    }

    fun openModeSheet() {
        overlays.modePicker.value = true
    }

    fun closeModeSheet() {
        overlays.modePicker.value = false
    }

    // ---- Launch -----------------------------------------------------------------------

    private val launchDraft = MutableStateFlow(LaunchDraft())

    /** The map point a long-press picked for the launch's go-to option. */
    private val launchPoint = MutableStateFlow<GeoPoint?>(null)

    val launch: StateFlow<LaunchUi?> = combine(overlays.launch, launchDraft, session.pickerSkills, pilot.state, launchPoint) { open, draft, skills, p, point ->
        if (!open) return@combine null
        LaunchUi(
            draft = draft,
            skills = skills.filter { it.category == SkillCategory.Behavior && it.motion },
            mission = p?.mission?.state,
            gotoPoint = gotoNed(point, draft.altM),
            vehicleClass = p?.vehicleClass,
            sequence = p?.sequence,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(UI_STOP_MS), null)

    fun openLaunch(open: Boolean) {
        overlays.launch.value = open
    }

    fun setLaunchDraft(draft: LaunchDraft) {
        launchDraft.value = draft
    }

    /**
     * A long-press on the map. On the ground it picks the Launch sheet's go-to point and
     * opens the sheet on that option; in the air it flies there at the current height above
     * home: through the pilot's goto action when the agent offers it, else the fly-here skill
     * (behind its hold confirm).
     */
    fun pickMapPoint(point: GeoPoint) {
        if (airborne()) {
            val alt = telemetry.value?.position?.value?.altRelM ?: return
            // One entry, behind fly-here's hold confirm and gates: the skill sends the pilot's
            // goto only while the pilot owns the vehicle, the FC's guided goto otherwise.
            activate(FlyHereSkill.id, mapOf(SkillArgs.LAT to point.latDeg, SkillArgs.LON to point.lonDeg, SkillArgs.ALTITUDE_M to alt))
            return
        }
        launchPoint.value = point
        launchDraft.update { it.copy(after = LaunchAfter.Goto) }
        overlays.launch.value = true
    }

    /** [point] in the pilot's local frame at [altM] above it: from the vehicle's local position, else from home. */
    private fun gotoNed(point: GeoPoint?, altM: Float): NedPoint? {
        if (point == null) return null
        val t = telemetry.value ?: return null
        val local = t.localPosition.value
        val fix = t.position.value
        if (local != null && fix != null) {
            return launchGotoNed(fix.latDeg, fix.lonDeg, local.northM, local.eastM, point.latDeg, point.lonDeg, altM)
        }
        val home = t.home.value ?: return null
        return launchGotoNed(home.latDeg, home.lonDeg, 0f, 0f, point.latDeg, point.lonDeg, altM)
    }

    /** Sends the launch sequence from the sheet's choices, behind the first-arm checklist. */
    fun launchNow() {
        val draft = launchDraft.value
        val args = launchArgs(draft, session.pickerSkills.value, gotoNed(launchPoint.value, draft.altM)) ?: return
        if (!armGate.request(ArmRequest.Launch(args))) {
            overlays.preflight.value = true
            _armGateOpen.value = true
            return
        }
        sendLaunch(args)
    }

    /**
     * Sends the launch and closes the Launch sheet: left open, its armed "Hold to launch"
     * stayed live through the flight and the stale sheet state carried past the landing.
     */
    private fun sendLaunch(args: Map<String, Any?>) {
        overlays.launch.value = false
        pilotAction(PilotAction(PilotAction.LAUNCH, args))
    }

    /** Cancels the low-battery return countdown. */
    fun cancelRthCountdown() = pilotAction(PilotAction(PilotAction.RTH_CANCEL))

    /** Takes the stick session over from another surface. */
    fun takeSticks() {
        if (pilot.link.value?.takeSession() != true) _events.tryEmit(CockpitEvent.PilotUnavailable)
    }

    // ---- Target designation -----------------------------------------------------------

    private val targets = CockpitTargetActions(viewModelScope, extensionSkills, session, activator, confirm, { video.value.selectedStreamId }, _events)

    /** Extension target actions for the flown drone, with their states. */
    val targetActions = targets.actions

    /** Runs a target action on a box drawn over the video (frame px x, y, width, height); the camera is the selected stream. */
    fun activateTarget(skill: ExtensionTargetActionSkill, bbox: Map<String, Float>) = targets.activate(skill, bbox)

    /** Detections, follow and navigation state, designation, nav-source chip, minimap frame and data layers. */
    internal val autonomy = CockpitAutonomy(
        viewModelScope, clock, vehicleNodeId, telemetry, nodeFlows.vehicleServices, video, session.allSkills, tick500,
        extensions, perception, localApis, settingsRepo, _events, ::activate,
    )

    init {
        viewModelScope.launch { autonomy.feed.pilot.collect { navPilot.value = it } }
        viewModelScope.launch {
            combine(pilot.state, router.gamepadConnected, autonomy.navChip) { p, pad, nav -> PreflightContext(p, pad, nav) }
                .collect { preflightContext.value = it }
        }
    }

    // ---- Views ------------------------------------------------------------------------

    private val _view = MutableStateFlow(CockpitView.Pilot)
    val view: StateFlow<CockpitView> = _view.asStateFlow()

    fun setView(v: CockpitView) {
        _view.value = v
        autonomy.layers.setView(v.id)
    }

    fun nextView() = setView(CockpitView.entries[(_view.value.ordinal + 1) % CockpitView.entries.size])

    // ---- Band, alerts, strip ----------------------------------------------------------

    private val hud = CockpitHudFlows(
        viewModelScope, clock, telemetry, node, vehicle, settings, videoState, sticks.stick, preflight, videoCtl.recording, tick500,
    )

    val band: StateFlow<BandState> = hud.band

    private val alertsCtl = CockpitAlertsController(
        viewModelScope, clock, telemetry, videoState, criticalText, prearmText, settings, tick500, announcer, sticks.raised, sticks.batteryLow,
    )

    val alerts: StateFlow<List<CockpitAlert>> = alertsCtl.alerts

    /** The status pill's items: alerts, pilot readiness, failing nav-source and storage checks, FC warnings (from the link and the agent's pilot). */
    val statusItems: StateFlow<List<StatusItem>> = combine(alerts, preflight, warningTexts, tick500) { a, p, w, _ ->
        val now = clock.nowMs()
        StatusModel.items(a, p, w.filter { now - it.atMs <= StatusModel.WARNING_TEXT_HOLD_MS })
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(UI_STOP_MS), emptyList())

    /**
     * The bottom row. AGL comes from the follow feed while it is fresh, else from the agent's
     * pilot while it is fresh; a height not measured by a rangefinder carries its reference
     * as a tag. The 2 Hz tick re-checks freshness, so AGL goes to a dash with the other
     * cells after a link loss.
     */
    val flightValues: StateFlow<FlightValues> = combine(hud.flightValues, autonomy.feed.follow, pilot.state, tick500) { v, f, p, _ ->
        val follow = f?.takeIf { autonomy.nowMs() - it.atMs <= TargetState.FOLLOW_STALE_MS }?.value?.takeIf { it.aglM != null }
        val fromPilot = p?.takeIf { clock.nowMs() - it.receivedAtMs <= PilotState.STALE_MS && it.aglM != null }
        val (agl, tag) = when {
            v == FlightValues.Empty -> null to null
            // The pilot's height is measured only from a rangefinder, else it is the EKF height
            // above home; the follow kind's is measured unless it is the barometer's.
            fromPilot != null -> fromPilot.aglM to specStrings.aglMark(home = fromPilot.aglSource != "rangefinder")
            follow != null -> follow.aglM to specStrings.aglMark(baro = follow.aglSource == "baro")
            else -> null to null
        }
        if (agl == v.aglM && tag == v.aglTag) v else v.copy(aglM = agl, aglTag = tag)
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(UI_STOP_MS), FlightValues.Empty)

    /** The bottom-left card's page; a swipe or a tap on its dots switches it (L3 swaps video and map). */
    val cardPage: StateFlow<CardPage> = hud.cardPage

    fun stepCard(delta: Int) = hud.stepCard(delta)

    /** Gimbal rate control for the pad, shown only where the agent reports it available. */
    internal val gimbal = GimbalRateController(viewModelScope, vehicleNodeId) { id ->
        localApis.firstNotNullOfOrNull { it.gimbal(id) } ?: extensions.api(id)?.gimbal
    }

    /** Minimap vehicle, home and trail, sampled at 2 Hz; the trail keeps points at least 2 m apart. */
    val minimap: StateFlow<MinimapState> = hud.minimap

    /** The route the vehicle holds (its agent's mission mirror), drawn on every cockpit map. */
    val missionRoute: StateFlow<MapOverlays> = combine(
        vehicleNodeId.flatMapLatest { id -> id?.let(missionMirror::observe) ?: flowOf(null) },
        minimap.map { it.home }.distinctUntilChanged(),
    ) { mirror, home -> missionRouteOverlays(mirror, home) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(UI_STOP_MS), MapOverlays.Empty)

    /** True when the map fills the screen and the video sits in the inset. */
    val minimapSwapped: StateFlow<Boolean> = hud.minimapSwapped

    fun toggleMinimapSwap() = hud.toggleMinimapSwap()

    // ---- Flight-state chip and flows --------------------------------------------------

    private val chipResolver = FlightStateChipResolver(spec.flightState, spec.tokens)
    private val missionCompleteSince = MutableStateFlow<Long?>(null)

    init {
        viewModelScope.launch {
            pilot.state.map { it?.mission?.state }.distinctUntilChanged().collect { s ->
                missionCompleteSince.value = if (s == MissionState.Complete) clock.nowMs() else null
            }
        }
    }

    /** The flight-state chip, re-evaluated on every pilot frame and on the 2 Hz tick for staleness. */
    val chip: StateFlow<FlightStateChipUi> = combine(pilot.state, tick500, band, missionCompleteSince, minimap) { p, _, b, done, map ->
        chipResolver.resolve(
            p,
            ChipContext(
                nowMs = clock.nowMs(),
                missionCompleteSinceMs = done,
                homeDistM = homeDistanceM(map),
                goalDistM = p?.goalNed?.let { goalDistanceM(it, telemetry.value?.localPosition?.value) },
                missionEtaS = autonomy.layers.store.ar.let { ArRules.nextWaypointEtaS(it.cur, it.ageMs(autonomy.nowMs())) },
                vtolTransitioning = telemetry.value?.vtolTransitioning?.value == true,
                fcModeName = b.modeName,
                modeDetail = p?.let { FlightStateChipResolver.modeDetail(it.ownerLabel, it.skill?.label?.uppercase()) },
            ),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(UI_STOP_MS), chipResolver.resolve(null, ChipContext(clock.nowMs())))

    fun onChipButton(button: ChipButton) {
        when (button) {
            ChipButton.Resume -> activate(BuiltinIds.RESUME)
            ChipButton.TakeControl -> pilotAction(PilotAction(PilotAction.TAKE_CONTROL))
            ChipButton.SetUpAircraft -> _events.tryEmit(CockpitEvent.SetUpAircraft)
        }
    }

    /** Banners, gate ring, stick session and countdowns from the pilot state. */
    val flow: StateFlow<FlowUi> = combine(pilot.state, streamer.streaming, tick500) { p, streaming, _ ->
        val f = FlowUi.of(p, PilotWire.SRC_ANDROID, streaming)
        // A banner past its vehicle time is dropped by the pilot; the local cap keeps a stale frame from pinning it.
        if (p != null && clock.nowMs() - p.receivedAtMs > PilotState.STALE_MS) f.copy(banner = null, gateProgress = null) else f
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(UI_STOP_MS), FlowUi())

    private val _paramNudges = MutableSharedFlow<Int>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** D-pad ↑/↓ with the skill picker open: steps for the focused parameter of the open Tweak panel. */
    val paramNudges: SharedFlow<Int> = _paramNudges.asSharedFlow()

    /** The controller hold in progress (arm, kill, RTH, take-off), for the hold ring. */
    private val _hold = MutableStateFlow<ControllerHold?>(null)
    val controllerHold: StateFlow<ControllerHold?> = _hold.asStateFlow()

    // ---- Controller feedback ----------------------------------------------------------

    private val controllerContext: StateFlow<ControllerContext> = combine(
        pilot.state,
        band,
        overlays.picker,
        alerts,
    ) { p, b, pickerOpen, a ->
        ControllerContext.of(
            pilot = p,
            armed = b.armed == true,
            modeName = b.modeName,
            pickerOpen = pickerOpen,
            lowBattery = (b.batteryPct ?: 100) <= settings.value.batteryBands.criticalPct,
            failsafe = a.any { it.level == com.altnautica.gcs.core.model.AlertLevel.Critical },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ControllerContext())

    private val feedback = ControllerFeedback(viewModelScope, controllerManager, alerts, controllerContext, spec.ds4.lightbar, spec.ds4.rumble)

    /** The current theme's colours for the controller light bar. */
    fun setControllerPalette(colors: AdosColorRoles) = feedback.setPalette(colors)

    // ---- Voice prompts ----------------------------------------------------------------

    init {
        viewModelScope.launch {
            var lastOwner: PilotOwner? = null
            var lastMission: MissionState? = null
            var lastRth = false
            pilot.state.collect { p ->
                if (p == null || !settings.value.voicePrompts) return@collect
                val strings = spec.setupStrings
                val owner = p.owner
                if (lastOwner != null && owner != lastOwner) {
                    when (owner) {
                        PilotOwner.Pilot -> announcer.speak(strings.format("voice.ados_control"))
                        PilotOwner.FcMode -> announcer.speak(strings.format("voice.fc_control"))
                        else -> Unit
                    }
                }
                lastOwner = owner
                val mission = p.mission?.state
                if (lastMission != null && mission != lastMission) {
                    when (mission) {
                        MissionState.Active -> announcer.speak(strings.format("voice.mission_active"))
                        MissionState.Paused -> announcer.speak(strings.format("voice.mission_paused"))
                        MissionState.Complete -> announcer.speak(strings.format("voice.mission_complete"))
                        MissionState.Returning -> announcer.speak(strings.format("voice.rth"))
                        else -> Unit
                    }
                }
                lastMission = mission
                val rth = p.rthCountdownS != null
                if (rth && !lastRth) announcer.speak(strings.format("voice.low_battery"))
                lastRth = rth
            }
        }
    }

    // ---- Overlays and input -----------------------------------------------------------

    val quickSettingsOpen: StateFlow<Boolean> = overlays.quickSettings.asStateFlow()
    val preflightOpen: StateFlow<Boolean> = overlays.preflight.asStateFlow()

    fun setQuickSettings(open: Boolean) {
        overlays.quickSettings.value = open
    }

    fun setPreflight(open: Boolean) {
        overlays.preflight.value = open
        if (!open) {
            armGate.dismiss()
            _armGateOpen.value = false
        }
    }

    private var inputJob: Job? = null
    private var controllerJob: Job? = null
    private var summaryJob: Job? = null
    private val dispatcher = Ds4ActionDispatcher(spec.ds4)
    private var lastGimbalDps = 0f

    private val capture = object : InputCapture {
        override fun consumesChord(chord: String) = CockpitInputMapper.consumesChord(chord, loadout.value)
        override val capturesEnter: Boolean get() = confirm.isOpen

        // Sheets other than the skill picker navigate like the app; a confirm sheet takes the buttons.
        override val uiNavigation: Boolean get() = overlays.uiNavigation
    }

    /** Starts taking hardware input while the cockpit is visible. */
    fun attachInput() {
        router.capture = capture
        if (inputJob?.isActive == true) return
        inputJob = viewModelScope.launch { router.events.collect(::onInput) }
        controllerJob = viewModelScope.launch {
            while (isActive) {
                controllerTick()
                delay(CONTROLLER_TICK_MS)
            }
        }
        streamer.start()
        feedback.start()
        summaryJob = viewModelScope.launch {
            // The mission without its age, so the summary changes only when what it shows does.
            val mission = pilot.state.map { it?.mission?.copy(ageMs = null) }.distinctUntilChanged()
            combine(preflight, alerts, sticks.stickOn, mission) { p, a, on, m ->
                CockpitSummary(cockpitNodeId, p.passed, p.total, a.size, a.firstOrNull()?.let(announcer::alertText), on, m)
            }.distinctUntilChanged().collect(summaries::publish)
        }
    }

    /**
     * Stops taking hardware input (the cockpit is no longer visible). The activity gets
     * no joystick events while stopped, so the stick stream releases, stick control turns
     * off and the router drops the last deflection and any held button.
     */
    fun detachInput() {
        if (router.capture === capture) router.capture = null
        inputJob?.cancel()
        inputJob = null
        controllerJob?.cancel()
        controllerJob = null
        dispatcher.reset()
        _hold.value = null
        streamer.stop()
        summaryJob?.cancel()
        summaryJob = null
        summaries.clear(cockpitNodeId)
        feedback.stop()
        sticks.forceOff()
        confirm.releaseHold()
        router.neutralize()
    }

    /** The window lost input focus (a system dialog, the shade): centre the sticks and release buttons. */
    fun onWindowFocusLost() {
        router.neutralize()
    }

    private fun controllerTick() {
        val pad = router.snapshot.value
        if (pad.deviceId == null) {
            _hold.value = null
            return
        }
        val actions = dispatcher.update(clock.nowMs(), pad, controlRows.value, controllerContext.value)
        _hold.value = dispatcher.hold
        for (a in actions) {
            // A confirm sheet or an app-style sheet takes the buttons; the brake, RTH and kill always act.
            val busy = confirm.isOpen || overlays.uiNavigation
            if (busy && !isSafety(a)) continue
            perform(a)
        }
    }

    private fun isSafety(a: CockpitControllerAction): Boolean = when (a) {
        is CockpitControllerAction.Builtin -> a.skillId in SAFETY_BUILTINS
        is CockpitControllerAction.Pilot -> a.action.name == PilotAction.RTH_CANCEL
        is CockpitControllerAction.Buzz -> true
        else -> false
    }

    private fun perform(a: CockpitControllerAction) {
        when (a) {
            is CockpitControllerAction.Builtin -> {
                val id = if (a.skillId == BuiltinIds.ARM && band.value.armed == true) BuiltinIds.DISARM else a.skillId
                activate(id, if (a.held) mapOf(ARG_HELD to true) else emptyMap())
            }
            is CockpitControllerAction.Pilot -> pilotAction(a.action)
            is CockpitControllerAction.Skill -> activate(a.skillId)
            CockpitControllerAction.SkillSelect -> if (overlays.picker.value) {
                pickerSelected.value?.let { pickerActions.onGo(it) }
            } else {
                setPicker(true)
            }
            CockpitControllerAction.SkillStop -> stopOrBack()
            is CockpitControllerAction.PickerFocus -> if (overlays.picker.value) movePickerFocus(a.delta)
            is CockpitControllerAction.ParamAdjust -> if (overlays.picker.value) _paramNudges.tryEmit(a.delta)
            is CockpitControllerAction.LayerLevel -> autonomy.layers.cycleLevel(a.delta)
            is CockpitControllerAction.ProfileCycle -> {
                val profiles = PilotProfile.entries
                val at = profiles.indexOf(pilot.state.value?.profile ?: PilotProfile.Normal)
                pilotAction(PilotAction.profile(profiles[(at + a.delta).mod(profiles.size)]))
            }
            CockpitControllerAction.Designate -> autonomy.designateCentre(autonomy.mainCamera())
            CockpitControllerAction.Photo -> capturePhoto()
            CockpitControllerAction.RecordToggle -> toggleRecording()
            is CockpitControllerAction.Zoom -> zoom(a.step)
            is CockpitControllerAction.GimbalPitchRate -> if (a.dps != lastGimbalDps) {
                lastGimbalDps = a.dps
                runCommand(VehicleCommand.GimbalRate(a.dps, 0f))
            }
            CockpitControllerAction.GimbalRecenter -> runCommand(VehicleCommand.GimbalRecenter)
            CockpitControllerAction.ViewNext -> nextView()
            CockpitControllerAction.SwapVideoMap -> toggleMinimapSwap()
            CockpitControllerAction.QuickSettings -> overlays.quickSettings.update { !it }
            is CockpitControllerAction.Buzz -> feedback.onHoldCue(a.kind, a.level)
        }
    }

    private val _digitalZoom = MutableStateFlow(1)

    /**
     * Digital zoom of the shown video (1, 2 or 4) once the vehicle's camera refused a zoom
     * command; label `camera.digital_zoom`. Stays 1 while the camera zooms itself.
     */
    val digitalZoom: StateFlow<Int> = _digitalZoom

    private val _frameGrabs = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** A photo the vehicle's camera could not take: the screen saves the shown frame on the device (`camera.saved`). */
    val frameGrabs: SharedFlow<Unit> = _frameGrabs

    /** The vehicle's camera takes the photo when it can; otherwise the shown frame is saved here. */
    fun capturePhoto() {
        val v = vehicle.value
        if (v == null) {
            _frameGrabs.tryEmit(Unit)
            return
        }
        viewModelScope.launch {
            if (!v.control.run(VehicleCommand.CapturePhoto).cameraTookIt()) _frameGrabs.tryEmit(Unit)
        }
    }

    fun zoomIn() = zoom(1)

    fun zoomOut() = zoom(-1)

    /** The vehicle's camera zooms when it can; otherwise the video zooms digitally 1× → 2× → 4×. */
    private fun zoom(step: Int) {
        val v = vehicle.value
        if (_digitalZoom.value > 1 || v == null) {
            _digitalZoom.value = digitalZoomStep(_digitalZoom.value, step)
            return
        }
        viewModelScope.launch {
            if (!v.control.run(VehicleCommand.CameraZoom(step)).cameraTookIt()) _digitalZoom.value = digitalZoomStep(_digitalZoom.value, step)
        }
    }

    fun recenterGimbal() = runCommand(VehicleCommand.GimbalRecenter)

    private fun runCommand(command: VehicleCommand) {
        val v = vehicle.value ?: return
        viewModelScope.launch { v.control.run(command) }
    }

    private fun onInput(event: InputEvent) {
        val armed = vehicle.value?.telemetry?.heartbeat?.value?.armed
        when (event) {
            is InputEvent.Enter -> confirm.onEnter(event.pressed)
            is InputEvent.Button -> if (confirm.isOpen) confirm.onGamepadButton(event.button, event.pressed)
            is InputEvent.Chord -> if (!confirm.isOpen) CockpitInputMapper.onChord(event.chord, loadout.value, armed)?.let(::perform)
        }
    }

    private fun perform(action: CockpitAction) {
        when (action) {
            is CockpitAction.ActivateSkill -> activate(action.skillId)
            CockpitAction.ToggleQuickSettings -> overlays.quickSettings.update { !it }
            CockpitAction.ToggleSkillPicker -> setPicker(!overlays.picker.value)
            CockpitAction.NextView -> nextView()
            CockpitAction.CycleLayerLevel -> autonomy.layers.cycleLevel(1)
            is CockpitAction.CycleStream -> cycleStream(action.delta)
            is CockpitAction.SelectStream -> selectStreamIndex(action.index)
            CockpitAction.TogglePip -> togglePip()
        }
    }

    /** Back with nothing open asks to leave the cockpit. */
    fun back() {
        if (!overlays.closeTop()) _events.tryEmit(CockpitEvent.ExitRequested)
    }

    override fun onCleared() {
        detachInput()
        videoCtl.cancel()
        confirm.cancel()
        announcer.shutdown()
    }

    private fun homeDistanceM(map: MinimapState): Float? {
        val v = map.vehicle?.position ?: return null
        val h = map.home ?: return null
        val dLat = Math.toRadians(v.latDeg - h.latDeg)
        val dLon = Math.toRadians(v.lonDeg - h.lonDeg) * cos(Math.toRadians(h.latDeg))
        return (hypot(dLat, dLon) * EARTH_RADIUS_M).toFloat()
    }

    companion object {
        private const val TAG = "Cockpit"
        private const val CONTROLLER_TICK_MS = 20L
        private const val EARTH_RADIUS_M = 6_371_000.0

        /** Activation argument: the skill was confirmed by a completed controller hold. */
        const val ARG_HELD = "heldConfirm"

        /** Cards in the first-connect controller tour: sticks, safety, skills. */
        const val TOUR_STEPS = 3

        private val SAFETY_BUILTINS = setOf(BuiltinIds.PAUSE, BuiltinIds.RTH, BuiltinIds.KILL)
    }
}

/** Horizontal metres from the vehicle's local position to [goal] (NED); null when either is unknown. */
internal fun goalDistanceM(goal: List<Float>, local: com.altnautica.gcs.core.model.LocalPosition?): Float? {
    if (local == null || goal.size < 2) return null
    return hypot(goal[0] - local.northM, goal[1] - local.eastM)
}

/** The camera accepted (or queued) the command itself. */
private fun CommandResult.cameraTookIt(): Boolean = this == CommandResult.Accepted || this is CommandResult.Queued

/** The next digital zoom level, 1× ↔ 2× ↔ 4×. */
internal fun digitalZoomStep(current: Int, step: Int): Int {
    val levels = listOf(1, 2, 4)
    val at = levels.indexOf(current).coerceAtLeast(0)
    return levels[(at + step.coerceIn(-1, 1)).coerceIn(0, levels.lastIndex)]
}
