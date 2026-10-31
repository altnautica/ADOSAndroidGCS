package com.altnautica.gcs.feature.cockpit

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.agent.AgentEndpoints
import com.altnautica.gcs.core.agent.video.StreamCatalog
import com.altnautica.gcs.core.link.services.VehicleServicesProvider
import com.altnautica.gcs.core.model.CockpitAlert
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.FlightMode
import com.altnautica.gcs.core.model.NodeSource
import com.altnautica.gcs.core.model.NodeSummary
import com.altnautica.gcs.core.model.Vehicle
import com.altnautica.gcs.core.model.VehicleProvider
import com.altnautica.gcs.core.model.VehicleTelemetry
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
import com.altnautica.gcs.core.skills.input.AdosInputRouter
import com.altnautica.gcs.core.skills.input.InputCapture
import com.altnautica.gcs.core.skills.input.InputEvent
import com.altnautica.gcs.feature.cockpit.preflight.cockpitPreflight
import com.altnautica.gcs.feature.cockpit.modes.ModePickerUi
import com.altnautica.gcs.feature.cockpit.modes.modeChoices
import com.altnautica.gcs.core.video.VideoSessionManager
import com.altnautica.gcs.core.video.VideoSourceSelector
import com.altnautica.gcs.feature.cockpit.alerts.AlertAnnouncer
import com.altnautica.gcs.feature.cockpit.alerts.AlertEngine
import com.altnautica.gcs.feature.cockpit.alerts.CockpitAlertsController
import com.altnautica.gcs.feature.cockpit.alerts.ReceivedText
import com.altnautica.gcs.feature.cockpit.band.CockpitHudFlows
import com.altnautica.gcs.feature.cockpit.confirm.ConfirmController
import com.altnautica.gcs.feature.cockpit.input.CockpitAction
import com.altnautica.gcs.feature.cockpit.input.CockpitInputMapper
import com.altnautica.gcs.feature.cockpit.manual.CockpitManualControl
import com.altnautica.gcs.feature.cockpit.preflight.PreflightResult
import com.altnautica.gcs.feature.cockpit.skillbar.CockpitTargetActions
import com.altnautica.gcs.feature.cockpit.video.CockpitVideoController
import com.altnautica.gcs.core.data.cockpit.AltitudeReference
import com.altnautica.gcs.core.data.cockpit.CockpitDensity
import com.altnautica.gcs.core.data.cockpit.CockpitSettings
import com.altnautica.gcs.core.data.cockpit.CockpitSettingsRepository
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

/** Severity at or below which a STATUSTEXT raises a critical alert (MAV_SEVERITY_CRITICAL). */
private const val SEVERITY_CRITICAL = 2

/** How long UI-only flows keep running after their last collector leaves. */
internal const val UI_STOP_MS = 5_000L

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
    videoSelector: VideoSourceSelector,
    videoSessions: VideoSessionManager,
    endpoints: AgentEndpoints,
    streamCatalog: StreamCatalog,
    private val announcer: AlertAnnouncer,
    private val clock: MonotonicClock,
    activator: SkillActivator,
    extensionSkills: ExtensionSkillContributor,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        /** Takes the raw id: Dagger cannot pass inline value classes through assisted factories. */
        fun create(nodeId: String): CockpitViewModel
    }

    private val nodeFlows = CockpitNodeFlows(viewModelScope, DeviceId(nodeIdValue), nodeSources, vehicles, services)

    private val _events = MutableSharedFlow<CockpitEvent>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<CockpitEvent> = _events.asSharedFlow()

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

    private val videoCtl = CockpitVideoController(viewModelScope, nodeFlows.vehicleNode, videoSelector, videoSessions, endpoints, streamCatalog, _events)
    val videoState: StateFlow<VideoState> = videoCtl.videoState
    val video: StateFlow<VideoUi> = videoCtl.video
    fun selectStream(streamId: String) = videoCtl.selectStream(streamId)
    fun cycleStream(delta: Int) = videoCtl.cycleStream(delta)
    fun selectStreamIndex(index: Int) = videoCtl.selectStreamIndex(index)
    fun togglePip() = videoCtl.togglePip()

    /** True when the current feed can record. */
    val canRecord: StateFlow<Boolean> = videoCtl.canRecord

    /** True while the current feed records; quick settings read this rather than the whole band. */
    val recordingActive: StateFlow<Boolean> = videoCtl.recordingActive
    fun toggleRecording() = videoCtl.toggleRecording()

    // ---- Preflight --------------------------------------------------------------------

    val preflight: StateFlow<PreflightResult> = cockpitPreflight(viewModelScope, clock, telemetry, settings, prearmText, tick500)

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
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(UI_STOP_MS), emptyList())

    /** The flight-mode picker entry, then every bar-eligible skill, with states: the drawer (the cockpit's palette). */
    val drawerSkills: StateFlow<List<SlotUi>> = combine(session.allSkills, session.barSkills, session.states) { all, bar, states ->
        val picker = all.firstOrNull { it.id == SetModeSkill.id }
        (listOfNotNull(picker) + bar).mapIndexed { i, s -> SlotUi(i, s, states[s.id], null, null) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(UI_STOP_MS), emptyList())

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
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(UI_STOP_MS), null)

    fun openModePicker() {
        _modePickerOpen.value = true
    }

    fun closeModePicker() {
        _modePickerOpen.value = false
    }

    /** Requests [modeName] through the `set-mode` skill; its risk tier decides the confirm. */
    fun selectMode(modeName: String) {
        _modePickerOpen.value = false
        activate(SetModeSkill.id, mapOf(SkillArgs.TARGET_MODE to modeName))
    }

    // ---- Target designation -----------------------------------------------------------

    private val targets = CockpitTargetActions(viewModelScope, extensionSkills, session, activator, confirm, { video.value.selectedStreamId }, _events)

    /** Extension target actions for the flown drone, with their states. */
    val targetActions: StateFlow<List<SlotUi>> = targets.actions

    /** Runs a target action on a box drawn over the video (normalized x, y, width, height); the camera is the selected stream. */
    fun activateTarget(skill: ExtensionTargetActionSkill, bbox: Map<String, Float>) = targets.activate(skill, bbox)

    // ---- Manual control ---------------------------------------------------------------

    private val manual = CockpitManualControl(viewModelScope, vehicle, nodeFlows.vehicleServices, router)

    fun toggleStickControl() = manual.toggle()

    // ---- Band, alerts, strip ----------------------------------------------------------

    private val hud = CockpitHudFlows(
        viewModelScope, clock, telemetry, node, vehicle, settings, videoState, manual.stick, preflight, videoCtl.recording, tick500,
    )

    val band: StateFlow<BandState> = hud.band

    private val alertsCtl = CockpitAlertsController(
        viewModelScope, clock, telemetry, videoState, criticalText, prearmText, settings, tick500, announcer,
    )

    val alerts: StateFlow<List<CockpitAlert>> = alertsCtl.alerts
    val strip: StateFlow<StripValues> = hud.strip

    /** Minimap vehicle, home and trail, sampled at 2 Hz; the trail keeps points at least 2 m apart. */
    val minimap: StateFlow<MinimapState> = hud.minimap

    /** True when the map fills the screen and the video sits in the inset. */
    val minimapSwapped: StateFlow<Boolean> = hud.minimapSwapped

    fun toggleMinimapSwap() = hud.toggleMinimapSwap()

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
        manual.forceOff()
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
        videoCtl.cancel()
        confirm.cancel()
        announcer.shutdown()
    }

    companion object {
        private const val TAG = "Cockpit"
    }
}
