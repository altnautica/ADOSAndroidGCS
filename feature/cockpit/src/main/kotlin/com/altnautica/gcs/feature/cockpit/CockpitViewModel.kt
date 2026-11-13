package com.altnautica.gcs.feature.cockpit

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.agent.nav.AutonomyFeedApi
import com.altnautica.gcs.core.agent.node.LocalNodeApis
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
import com.altnautica.gcs.core.link.mission.MissionMirrorSource
import com.altnautica.gcs.core.link.services.VehicleServicesProvider
import com.altnautica.gcs.core.map.GeoPoint
import com.altnautica.gcs.core.map.MapOverlays
import com.altnautica.gcs.core.model.CockpitAlert
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.NodeSource
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.model.PilotAction
import com.altnautica.gcs.core.model.PilotState
import com.altnautica.gcs.core.model.SkillState
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
import com.altnautica.gcs.core.skills.builtin.SetModeSkill
import com.altnautica.gcs.core.skills.builtin.SkillArgs
import com.altnautica.gcs.core.skills.cockpit.CockpitSummary
import com.altnautica.gcs.core.skills.cockpit.CockpitSummaryRegistry
import com.altnautica.gcs.core.skills.controls.ControlsRepository
import com.altnautica.gcs.core.skills.hud.SpecText
import com.altnautica.gcs.core.skills.hud.freshPilot
import com.altnautica.gcs.core.skills.input.AdosInputRouter
import com.altnautica.gcs.core.skills.input.PadSnapshot
import com.altnautica.gcs.core.skills.input.controller.ControllerManager
import com.altnautica.gcs.core.skills.spec.CockpitSpecRepository
import com.altnautica.gcs.core.skills.spec.SetupStringsSpec
import com.altnautica.gcs.feature.cockpit.alerts.AlertAnnouncer
import com.altnautica.gcs.feature.cockpit.alerts.AlertEngine
import com.altnautica.gcs.feature.cockpit.alerts.CockpitAlertsController
import com.altnautica.gcs.feature.cockpit.alerts.ControllerFeedback
import com.altnautica.gcs.feature.cockpit.alerts.FcTexts
import com.altnautica.gcs.feature.cockpit.autonomy.CockpitAutonomy
import com.altnautica.gcs.feature.cockpit.autonomy.pillSource
import com.altnautica.gcs.feature.cockpit.band.BatteryReserve
import com.altnautica.gcs.feature.cockpit.band.CockpitHudFlows
import com.altnautica.gcs.feature.cockpit.band.freshHeartbeatArmed
import com.altnautica.gcs.feature.cockpit.camera.CockpitCamera
import com.altnautica.gcs.feature.cockpit.camera.FrameGrab
import com.altnautica.gcs.feature.cockpit.chip.ChipButton
import com.altnautica.gcs.feature.cockpit.chip.FlightStateChipUi
import com.altnautica.gcs.feature.cockpit.chrome.CardPage
import com.altnautica.gcs.feature.cockpit.chrome.FlightValues
import com.altnautica.gcs.feature.cockpit.chrome.GimbalRateController
import com.altnautica.gcs.feature.cockpit.chrome.RedrawBeat
import com.altnautica.gcs.feature.cockpit.chrome.StatusItem
import com.altnautica.gcs.feature.cockpit.chrome.StatusModel
import com.altnautica.gcs.feature.cockpit.confirm.ConfirmController
import com.altnautica.gcs.feature.cockpit.flow.FlowUi
import com.altnautica.gcs.feature.cockpit.launch.LaunchDraft
import com.altnautica.gcs.feature.cockpit.launch.LaunchUi
import com.altnautica.gcs.feature.cockpit.manual.CockpitStickController
import com.altnautica.gcs.feature.cockpit.modes.ModeSheetActions
import com.altnautica.gcs.feature.cockpit.modes.ModeSheetUi
import com.altnautica.gcs.feature.cockpit.pilot.CockpitPilot
import com.altnautica.gcs.feature.cockpit.pilot.ControllerContext
import com.altnautica.gcs.feature.cockpit.pilot.OnScreenSticks
import com.altnautica.gcs.feature.cockpit.pilot.PilotStickStreamer
import com.altnautica.gcs.feature.cockpit.pilot.StickConditioning
import com.altnautica.gcs.feature.cockpit.pilot.pilotActionEvent
import com.altnautica.gcs.feature.cockpit.pilot.sendModeSheetAction
import com.altnautica.gcs.feature.cockpit.preflight.ArmRequest
import com.altnautica.gcs.feature.cockpit.preflight.FirstArmGate
import com.altnautica.gcs.feature.cockpit.preflight.PreflightContext
import com.altnautica.gcs.feature.cockpit.preflight.PreflightResult
import com.altnautica.gcs.feature.cockpit.preflight.cockpitPreflight
import com.altnautica.gcs.feature.cockpit.preflight.sticksAvailable
import com.altnautica.gcs.feature.cockpit.preflight.storageUsedPct
import com.altnautica.gcs.feature.cockpit.skillbar.CockpitTargetActions
import com.altnautica.gcs.feature.cockpit.skillbar.PickerChip
import com.altnautica.gcs.feature.cockpit.skillbar.SkillPickerActions
import com.altnautica.gcs.feature.cockpit.skillbar.SkillPickerUi
import com.altnautica.gcs.feature.cockpit.skillbar.WidthClass
import com.altnautica.gcs.feature.cockpit.video.CockpitVideoController
import com.altnautica.gcs.feature.cockpit.video.CockpitVideoServices
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
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
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** How long UI-only flows keep running after their last collector leaves. */
internal const val UI_STOP_MS = 5_000L

/** The view model's 2 Hz tick, in beats of the flight screen's [RedrawBeat]. */
private const val TICK_BEATS = 500L / RedrawBeat.BEAT_MS

/** The cockpit views Triangle (or `V`) cycles through. */
enum class CockpitView(val id: String) { Pilot("pilot"), Map("map"), Split("split"), Vision("vision") }

/**
 * Cockpit for one node. A ground station's cockpit flies its carried drone. The launch and
 * Fly Here ([CockpitLaunch]), skill picker ([CockpitPicker]), Mode sheet ([CockpitModeSheet]),
 * derived flight facts ([CockpitFacts]) and hardware input ([CockpitPadInput]) live in their
 * own controllers; this class wires them and is the screen's one entry point.
 */
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
    videoServices: CockpitVideoServices,
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
    @com.altnautica.gcs.feature.cockpit.di.CockpitWork work: kotlin.coroutines.CoroutineContext = kotlin.coroutines.EmptyCoroutineContext,
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
    private val statusPill by lazy { StatusModel.statusPill(specText, spec.layout) }
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

    /** The split view's video share for the flown vehicle, stored per vehicle. */
    val splitRatio: StateFlow<Float> = vehicleNodeId.flatMapLatest { id ->
        id?.let { settingsRepo.splitRatio(it.value) } ?: flowOf(CockpitSettingsRepository.DEFAULT_SPLIT)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, CockpitSettingsRepository.DEFAULT_SPLIT)

    fun setSplitRatio(ratio: Float) {
        val id = vehicleNodeId.value ?: return
        viewModelScope.launch { settingsRepo.setSplitRatio(id.value, ratio) }
    }

    fun setTelemetryPane(on: Boolean) = viewModelScope.launch { settingsRepo.setTelemetryPane(on) }

    /** On-screen sticks enabled in Settings › Controls (default off). */
    val onScreenSticksEnabled: StateFlow<Boolean> = controls.onScreenSticks.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // ---- Clocks -----------------------------------------------------------------------

    /** The flight screen's one redraw clock ([RedrawBeat]), beating while it is shown; the 2 Hz state below changes on it too. */
    val redrawBeat: RedrawBeat = RedrawBeat(shown = false).also { beat -> viewModelScope.launch { beat.run() } }

    /**
     * Re-evaluation trigger for age-based state, and the one 2 Hz clock the band, alerts,
     * telemetry strip and minimap sample on: every fifth beat of [redrawBeat] (500 ms steps while hidden), so their
     * changes land in the same UI frame as the HUD's instead of one frame each. Its value is
     * only a trigger: a combine that also fires on fresh telemetry reads
     * [MonotonicClock.nowMs] itself, because a heartbeat stamped after the last tick would
     * otherwise get a negative age.
     */
    private val tick500: StateFlow<Long> = redrawBeat.cadence.filter { it % TICK_BEATS == 0L }.map { clock.nowMs() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, clock.nowMs())

    // ---- STATUSTEXT tracking ----------------------------------------------------------

    private val fcTexts = FcTexts()
    private val criticalText = fcTexts.critical
    private val prearmText = fcTexts.prearm

    init {
        viewModelScope.launch {
            telemetry.flatMapLatest { it?.statusText ?: emptyFlow() }.collect { st -> fcTexts.onStatusText(st.severity, st.text, clock.nowMs(), relayed = false) }
        }
    }

    // ---- Video ------------------------------------------------------------------------

    private val videoCtl = videoServices.let { s ->
        CockpitVideoController(viewModelScope, nodeFlows.vehicleNode, s.selector, s.sessions, s.heroHold, s.endpoints, s.streamCatalog, _events)
    }
    val videoState: StateFlow<VideoState> = videoCtl.videoState
    val video: StateFlow<VideoUi> = videoCtl.video
    fun selectStream(streamId: String) = videoCtl.selectStream(streamId)
    fun cycleStream(delta: Int) = videoCtl.cycleStream(delta)
    fun selectStreamIndex(index: Int) = videoCtl.selectStreamIndex(index)
    fun togglePip() = videoCtl.togglePip()

    /** Dials the failed video again (the placeholder's Retry). */
    fun retryVideo() = videoCtl.retry()
    fun swapPip() = videoCtl.swapPip()
    fun setFlightViewShown(shown: Boolean) = videoCtl.setFlightViewShown(shown).also { redrawBeat.setShown(shown) }

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

    internal val pilot = CockpitPilot(viewModelScope, clock, vehicleNodeId, ::autonomyApi, navPilot, vehicle.flatMapLatest { it?.reach ?: flowOf(null) }, work)

    /** The onboard pilot's state, or null for a vehicle without one. */
    val pilotState: StateFlow<PilotState?> = pilot.state

    /**
     * The pilot's state while its frame is fresh, else null; re-checked on the 2 Hz tick, so
     * a stalled pilot's last frame never decides what the screen offers.
     */
    val freshPilotState: StateFlow<PilotState?> = combine(pilot.state, tick500) { p, _ -> freshPilot(p, clock.nowMs()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(UI_STOP_MS), freshPilot(pilot.state.value, clock.nowMs()))

    /** The return reserve from the agent's battery engine for the flown vehicle, or null without one; polled, shown on the 2 Hz tick. */
    val batteryReserve: StateFlow<BatteryReserve?> = onTick(viewModelScope, vehicleNodeId.flatMapLatest { id ->
        id?.let { batteryReservePolls { autonomyApi(it) } } ?: flowOf(null)
    }, tick500, null)

    /** The settings with the battery bands the cockpit flies by: the spec's fallback, raised to the engine's reserve. */
    private val flightSettings: StateFlow<CockpitSettings> = combine(settings, batteryReserve) { s, r ->
        val bands = AlertEngine.flightBands(s.batteryBands, spec.layout.battery?.lowFallbackPct, r?.reservePct)
        if (bands == s.batteryBands) s else s.copy(batteryBands = bands)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, settings.value)

    init {
        // The pilot's relayed FC texts go through the same classification as the link's.
        viewModelScope.launch {
            pilot.state.collect { ps ->
                ps?.statusTexts?.forEach { fcTexts.onStatusText(it.severity, it.text, ps.receivedAtMs - (it.ageMs ?: 0L), relayed = true) }
            }
        }
    }

    // ---- Preflight --------------------------------------------------------------------

    private val preflightContext = MutableStateFlow(PreflightContext.of(null, false, null))

    val preflight: StateFlow<PreflightResult> = cockpitPreflight(viewModelScope, clock, telemetry, flightSettings, prearmText, preflightContext, tick500,
        spec.layout.statusPill!!.storageWarnPct!!, storageUsedPct(viewModelScope, work))

    private val armGate = FirstArmGate()
    private val _armGateOpen = MutableStateFlow(false)

    /** True while the checklist sheet is open because an arm waits on it. */
    val armGateOpen: StateFlow<Boolean> = _armGateOpen.asStateFlow()

    /** True when [request] may go ahead; otherwise it waits behind the checklist sheet, opened for it. */
    private fun passArmGate(request: ArmRequest): Boolean {
        if (armGate.request(request)) return true
        overlays.preflight.value = true
        _armGateOpen.value = true
        return false
    }

    // ---- Skills -----------------------------------------------------------------------

    val confirm: ConfirmController = ConfirmController(
        scope = viewModelScope,
        clock = clock,
        failingChecks = { preflight.value.failing },
        isButtonHeld = { b -> router.buttons.value and (1 shl b) != 0 },
        onConfirmed = { feedback.onConfirmed() },
    )

    private val overlays: CockpitOverlays = CockpitOverlays(confirm)

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

    /**
     * Possibly in the air: from the pilot while its frame is fresh, else a fresh heartbeat.
     * An unknown arm state (neither fresh) counts as airborne.
     */
    private fun airborne(): Boolean =
        freshPilot(pilot.state.value, clock.nowMs())?.possiblyAirborne ?: (fcArmed() != false)

    /** The flight controller's arm state from a fresh heartbeat; null when unknown. */
    private fun fcArmed(): Boolean? = freshHeartbeatArmed(telemetry.value?.heartbeat?.value, clock.nowMs())

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

    /** Notices the cockpit raises itself (a refused launch Abort, a Fly Here it cannot offer). */
    private val ownNotices = MutableSharedFlow<SkillNotice>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Skill notices; a flight controller's MAV_RESULT name in the detail reads as the spec's sentence. */
    val notices: SharedFlow<SkillNotice> = merge(session.notices.map { it.copy(message = specText.mavResultText(it.message)) }, ownNotices)
        .shareIn(viewModelScope, SharingStarted.Eagerly)

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
        if ((skillId == BuiltinIds.ARM || skillId == BuiltinIds.TAKEOFF) && !passArmGate(ArmRequest.Skill(skillId, args))) return
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
            is ArmRequest.Launch -> launcher.send(parked.args)
        }
    }

    // ---- Skill picker -----------------------------------------------------------------

    private val pickerCtl = CockpitPicker(viewModelScope, session, overlays, pilot.state) { activate(it) }
    val pickerOpen: StateFlow<Boolean> = overlays.picker.asStateFlow()
    val pickerChip: StateFlow<PickerChip> = pickerCtl.chip
    val picker: StateFlow<SkillPickerUi?> = pickerCtl.ui
    val pickerActions: SkillPickerActions = pickerCtl.actions

    fun setPicker(open: Boolean) = pickerCtl.setOpen(open)

    /** Circle: close the top overlay, else stop the running skill (the pilot holds). */
    internal fun stopOrBack() {
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

    private val streamer = PilotStickStreamer(viewModelScope, work, clock, ::streamPad, conditioning, pilot.stickSink)

    private val sticks = CockpitStickController(
        viewModelScope, clock, vehicle, nodeFlows.vehicleServices, router, controllerSettings, controllerManager,
        boundButtons, pilot.route, streamer.streaming, conditioning,
    )

    fun toggleStickControl() = sticks.toggle()

    /** Stick control in every mode for the flown vehicle; null without a vehicle. */
    val stickAllModes: StateFlow<Boolean?> = sticks.allModes

    fun setStickAllModes(on: Boolean) = sticks.setAllModes(on)

    // ---- Mode sheet -------------------------------------------------------------------

    /** Sends [action] to the pilot and reports a refusal, a missing answer or a down channel. */
    internal fun pilotAction(action: PilotAction, stopsMotion: Boolean = false) {
        viewModelScope.launch {
            val result = sendModeSheetAction(action, pilot.link.value, stopMotion = { if (stopsMotion) stopMotionSkill() })
            pilotActionEvent(result, specText)?.let(_events::tryEmit)
        }
    }

    private suspend fun stopMotionSkill() {
        session.activeMotionSkill()?.let { session.stop(it.id) }
    }

    private val modeSheetCtl = CockpitModeSheet(viewModelScope, vehicle, pilot, session.states, overlays, sticks::endsStick, ::activate, ::pilotAction)

    /** The Mode sheet while open: pilot settings, handover and every firmware mode. */
    val modeSheet: StateFlow<ModeSheetUi?> = modeSheetCtl.ui
    val modeSheetActions: ModeSheetActions = modeSheetCtl.actions

    fun openModeSheet() = modeSheetCtl.setOpen(true)

    fun closeModeSheet() = modeSheetCtl.setOpen(false)

    // ---- Launch and Fly Here ----------------------------------------------------------

    private val launcher = CockpitLaunch(
        viewModelScope, clock, telemetry, pilot, overlays, session.pickerSkills, spec.setupStrings, specText, ownNotices, _events,
        airborne = ::airborne, activate = ::activate, pilotAction = { pilotAction(it) }, armGate = ::passArmGate,
    )

    val launch: StateFlow<LaunchUi?> = launcher.ui

    fun openLaunch(open: Boolean) = launcher.open(open)

    fun setLaunchDraft(draft: LaunchDraft) = launcher.setDraft(draft)

    /** A long-press on the map: Launch's go-to point on the ground, Fly Here in the air ([CockpitLaunch.pickMapPoint]). */
    fun pickMapPoint(point: GeoPoint) = launcher.pickMapPoint(point)

    /** A long-press on the local minimap, in metres from the drone as drawn ([CockpitLaunch.pickLocalPoint]). */
    fun pickLocalPoint(northM: Float, eastM: Float) = launcher.pickLocalPoint(northM, eastM)

    /** Sends the launch sequence from the sheet's choices, behind the first-arm checklist. */
    fun launchNow() = launcher.launchNow()

    /** Abort of a running launch (the banner's and the chip's). */
    fun abortLaunch() = launcher.abort()

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
        extensions, perception, localApis, settingsRepo, _events, ::activate, pilot.state, work,
    )

    private val widthClass = MutableStateFlow<WidthClass?>(null)

    /** The screen's width class, which decides whether the on-screen sticks show. */
    fun setWidthClass(width: WidthClass) {
        widthClass.value = width
    }

    init {
        viewModelScope.launch { autonomy.feed.pilot.collect { navPilot.value = it } }
        viewModelScope.launch {
            // On-screen sticks shown at this width fly as well as a pad; an equal context re-evaluates nothing.
            combine(pilot.state, router.gamepadConnected, onScreenSticksEnabled, widthClass, autonomy.navChip) { p, pad, on, w, nav ->
                PreflightContext.of(p, sticksAvailable(pad, on, w, spec.layout.onScreenSticks.classes), nav)
            }.collect { preflightContext.value = it }
        }
    }

    // ---- Views ------------------------------------------------------------------------

    private val _view = MutableStateFlow(CockpitView.Pilot)
    val view: StateFlow<CockpitView> = _view.asStateFlow()

    /** Shows [v] and stores it for the flown vehicle, so its cockpit reopens on the same view. */
    fun setView(v: CockpitView) {
        showView(v)
        val id = vehicleNodeId.value ?: return
        viewModelScope.launch { settingsRepo.setView(id.value, v.id) }
    }

    private fun showView(v: CockpitView) {
        _view.value = v
        autonomy.layers.setView(v.id)
    }

    init {
        // Each vehicle reopens on the view it was last flown with.
        viewModelScope.launch {
            vehicleNodeId.filterNotNull().distinctUntilChanged().collect { id ->
                val stored = settingsRepo.view(id.value).first()
                CockpitView.entries.firstOrNull { it.id == stored }?.let(::showView)
            }
        }
    }

    fun nextView() = setView(CockpitView.entries[(_view.value.ordinal + 1) % CockpitView.entries.size])

    // ---- Band, alerts, strip ----------------------------------------------------------

    private val hud = CockpitHudFlows(
        viewModelScope, clock, telemetry, node, vehicle, flightSettings, videoState, sticks.stick, preflight, videoCtl.recording, tick500,
    )

    val band: StateFlow<BandState> = hud.band

    private val alertsCtl = CockpitAlertsController(
        viewModelScope, clock, telemetry, videoState, criticalText, prearmText, flightSettings, tick500, announcer, sticks.raised, sticks.batteryLow,
    )

    val alerts: StateFlow<List<CockpitAlert>> = alertsCtl.alerts

    private val pill = CockpitPill(
        viewModelScope, clock, pilot, preflight, alerts, criticalText, fcTexts.warnings, tick500, statusPill,
        heartbeatSeen = telemetry.flatMapLatest { it?.heartbeat ?: flowOf(null) }.map { it != null },
        navSource = autonomy.navChip.map { it?.pillSource() },
    ) {
        // A fresh pilot without an arm state stays unknown unless the heartbeat is fresh.
        freshPilot(pilot.state.value, clock.nowMs())?.armed ?: fcArmed()
    }

    /** The status pill's items: alerts, pilot readiness, failing checks, storage, FC texts and the FC failsafe. */
    val statusItems: StateFlow<List<StatusItem>> = pill.items

    /** True until a pilot frame or a heartbeat arrives: the pill reads no link, not all systems OK. */
    val statusWaiting: StateFlow<Boolean> = pill.waiting

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

    // ---- Flight-state chip, flows and controller context ------------------------------

    private val facts: CockpitFacts = CockpitFacts(
        viewModelScope, clock, spec, pilot.state, tick500, telemetry, autonomy, band, minimap, hud::flightValuesNow, streamer.streaming,
        overlays.picker, alerts, settings,
    )

    /** The bottom row; AGL from a fresh follow feed or a fresh pilot ([CockpitFacts.flightValues]). */
    val flightValues: StateFlow<FlightValues> = facts.flightValues

    /** The flight-state chip, re-evaluated on every pilot frame and on the 2 Hz tick for staleness. */
    val chip: StateFlow<FlightStateChipUi> = facts.chip

    /** True while the newest pilot frame is fresh; re-checked on the 2 Hz tick. */
    val pilotFresh: StateFlow<Boolean> = facts.pilotFresh

    /** Banners, gate ring, stick session, countdowns and launch steps; none from a stale frame. */
    val flow: StateFlow<FlowUi> = facts.flow

    /** The tips' flight facts and banner flag; equal across pilot frames that change neither. */
    internal val tipFlight: StateFlow<TipFlightFacts> = tipFlightFacts(viewModelScope, freshPilotState, band, alerts, flow)

    /** Controller buttons held now (W3C bitfield); any press dismisses a contextual tip. */
    val padButtons: StateFlow<Int> get() = router.buttons

    /** The skill picker lists at least one skill: the skills tip's trigger. */
    val skillsAvailable: StateFlow<Boolean> by lazy {
        session.pickerSkills.map { it.isNotEmpty() }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(UI_STOP_MS), false)
    }

    fun onChipButton(button: ChipButton) {
        when (button) {
            ChipButton.Resume -> activate(BuiltinIds.RESUME)
            ChipButton.TakeControl -> pilotAction(PilotAction(PilotAction.TAKE_CONTROL))
            ChipButton.SetUpAircraft -> _events.tryEmit(CockpitEvent.SetUpAircraft)
            ChipButton.Abort -> abortLaunch()
        }
    }

    /** The Menu's Controls: leaves for the controller settings. */
    fun openControls() {
        _events.tryEmit(CockpitEvent.OpenControls)
    }

    // ---- Controller feedback and voice ------------------------------------------------

    /** Only a fresh pilot frame decides armed and landed; an old one falls back to the heartbeat. */
    internal val controllerContext: StateFlow<ControllerContext> = facts.controllerContext

    internal val feedback: ControllerFeedback = ControllerFeedback(viewModelScope, controllerManager, alerts, controllerContext, spec.ds4.lightbar, spec.ds4.rumble, work)

    /** The current theme's colours for the controller light bar. */
    fun setControllerPalette(colors: AdosColorRoles) = feedback.setPalette(colors)

    init {
        viewModelScope.launchVoicePrompts(pilot.state, settings, { spec.setupStrings }, announcer)
    }

    // ---- Overlays and input -----------------------------------------------------------

    val quickSettingsOpen: StateFlow<Boolean> = overlays.quickSettings.asStateFlow()
    val preflightOpen: StateFlow<Boolean> = overlays.preflight.asStateFlow()

    fun setQuickSettings(open: Boolean) {
        overlays.quickSettings.value = open
    }

    val cameraSettingsOpen: StateFlow<Boolean> = overlays.camera.asStateFlow()

    fun setCameraSettings(open: Boolean) {
        overlays.camera.value = open
    }

    /** The status pill's alert list; Back closes it like a sheet. */
    val statusListOpen: StateFlow<Boolean> = overlays.statusList.asStateFlow()

    fun setStatusList(open: Boolean) {
        overlays.statusList.value = open
    }

    fun setPreflight(open: Boolean) {
        overlays.preflight.value = open
        if (!open) {
            armGate.dismiss()
            _armGateOpen.value = false
        }
    }

    private val padInput = CockpitPadInput(this, viewModelScope, work, clock, router, confirm, overlays, pickerCtl, controlRows, spec)

    /** D-pad ↑/↓ with the skill picker open: steps for the focused parameter of the open Tweak panel. */
    val paramNudges: SharedFlow<Int> = padInput.paramNudges

    /** The binding action of the controller hold in progress (arm, kill, RTH, take-off); changes only when a hold starts, ends or switches action. */
    val controllerHold: StateFlow<String?> = padInput.holdAction

    /** Progress 0..1 of [controllerHold], for sampling on the redraw beat and reading in draw. */
    fun controllerHoldProgress(): Float = padInput.holdProgress()

    private var summaryJob: Job? = null

    /** Starts taking hardware input while the cockpit is visible. */
    fun attachInput() {
        if (!padInput.attach()) return
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
        padInput.detach()
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

    internal val camera: CockpitCamera = CockpitCamera(viewModelScope) { vehicle.value?.control }

    /** Digital zoom of the shown video (1, 2 or 4) once the vehicle's camera refused a zoom; label `camera.digital_zoom`. */
    val digitalZoom: StateFlow<Int> = camera.digitalZoom

    /** Every shutter press: the screen copies the shown frame at once and saves it when the camera did not take the photo. */
    val frameGrabs: SharedFlow<FrameGrab> = camera.frameGrabs

    fun capturePhoto() = camera.capturePhoto()

    fun zoomIn() = camera.zoom(1)

    fun zoomOut() = camera.zoom(-1)

    fun recenterGimbal() = runCommand(VehicleCommand.GimbalRecenter)

    internal fun runCommand(command: VehicleCommand) {
        val v = vehicle.value ?: return
        viewModelScope.launch { v.control.run(command) }
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

    companion object {
        private const val TAG = "Cockpit"

        /** Activation argument: the skill was confirmed by a completed controller hold. */
        const val ARG_HELD = "heldConfirm"

        /** Cards in the first-connect controller tour: sticks, safety, skills. */
        const val TOUR_STEPS = 3
    }
}
