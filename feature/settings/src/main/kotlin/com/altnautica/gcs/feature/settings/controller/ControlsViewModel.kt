package com.altnautica.gcs.feature.settings.controller

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.data.controller.ControllerSettings
import com.altnautica.gcs.core.data.controller.ControllerSettingsRepository
import com.altnautica.gcs.core.link.node.NodeRepository
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.Skill
import com.altnautica.gcs.core.model.SkillSource
import com.altnautica.gcs.core.skills.SkillRegistry
import com.altnautica.gcs.core.skills.binding.BindingStore
import com.altnautica.gcs.core.skills.binding.KeyConflict
import com.altnautica.gcs.core.skills.binding.BUILTIN_HOLDER
import com.altnautica.gcs.core.skills.binding.RESERVED_HOLDER
import com.altnautica.gcs.core.skills.binding.BindingConflict
import com.altnautica.gcs.core.skills.binding.Ds4Bindings
import com.altnautica.gcs.core.skills.controls.BindingProblem
import com.altnautica.gcs.core.skills.controls.ControlsProfile
import com.altnautica.gcs.core.skills.controls.ControlsRepository
import com.altnautica.gcs.core.skills.controls.ControlsState
import com.altnautica.gcs.core.skills.controls.EditRefusal
import com.altnautica.gcs.core.skills.controls.EditResult
import com.altnautica.gcs.core.skills.controls.ImportResult
import com.altnautica.gcs.core.skills.controls.PadKind
import com.altnautica.gcs.core.skills.controls.SaveResult
import com.altnautica.gcs.core.skills.controls.StickMode
import com.altnautica.gcs.core.skills.controls.addRow
import com.altnautica.gcs.core.skills.controls.moveRow
import com.altnautica.gcs.core.skills.controls.padKey
import com.altnautica.gcs.core.skills.controls.setRowAction
import com.altnautica.gcs.core.skills.input.AdosInputRouter
import com.altnautica.gcs.core.skills.input.InputCapture
import com.altnautica.gcs.core.skills.input.InputEvent
import com.altnautica.gcs.core.skills.input.PadSnapshot
import com.altnautica.gcs.core.skills.input.controller.ControllerCapabilities
import com.altnautica.gcs.core.skills.input.controller.ControllerInfo
import com.altnautica.gcs.core.skills.input.controller.ControllerManager
import com.altnautica.gcs.core.skills.input.controller.Rumble
import com.altnautica.gcs.core.skills.input.controller.isDualShock4
import com.altnautica.gcs.core.skills.spec.CockpitSpecRepository
import com.altnautica.gcs.core.skills.spec.ControlBindingSpec
import com.altnautica.gcs.core.skills.spec.LockedActionSpec
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One installed plugin skill as a binding target. */
data class SkillOption(val action: String, val label: String)

/** A plugin's skills, listed under its name in the action picker. */
data class SkillGroup(val pluginName: String, val skills: List<SkillOption>)

/** "Press a control" mode: the next physical press opens that control, or moves [row] onto it. */
data class PressMode(val row: ControlBindingSpec? = null)

enum class CalibrationPhase { Moving, Release, Saved }

/** The stick calibration card: [coverage] is the share of the eight stick edges reached so far. */
data class CalibrationUi(val phase: CalibrationPhase, val coverage: Float)

/** A hardware check in progress. */
enum class HardwareTest { Rumble, Lightbar, Sticks }

/** One-shot outcomes shown as a snackbar. */
sealed interface ControlsMessage {
    data object Locked : ControlsMessage

    data object SameControl : ControlsMessage

    data object Saved : ControlsMessage

    data object SaveRefused : ControlsMessage

    data object Imported : ControlsMessage

    data object ImportUnreadable : ControlsMessage

    /** [actions] are the missing locked actions' labels. */
    data class ImportMissingLocked(val actions: List<String>) : ControlsMessage

    data object ImportRefused : ControlsMessage

    data object FileFailed : ControlsMessage
}

data class ControlsUiState(
    val loaded: Boolean = false,
    /** The controller driving input, or null with none attached. */
    val controller: ControllerInfo? = null,
    val connection: PadConnection = PadConnection.Unknown,
    val capabilities: ControllerCapabilities = ControllerCapabilities.None,
    val pad: PadKind = PadKind.Ds4,
    val stickMode: StickMode = StickMode.Mode2,
    val deadzone: Float = 0f,
    val profile: ControlsProfile = ControlsProfile.AdosDefault,
    /** The binding list on screen: the unsaved edit when there is one, else the saved list. */
    val rows: List<ControlBindingSpec> = emptyList(),
    val conflicts: List<BindingConflict> = emptyList(),
    val missingLocked: List<LockedActionSpec> = emptyList(),
    /** True while [rows] holds edits not yet saved. */
    val dirty: Boolean = false,
    /** The control whose binding sheet is open. */
    val editing: String? = null,
    val press: PressMode? = null,
    val skillGroups: List<SkillGroup> = emptyList(),
    val calibration: CalibrationUi? = null,
    /** True when the attached pad has a stored calibration. */
    val calibrated: Boolean = false,
    val test: HardwareTest? = null,
    /** The first-connect tour card on screen (0..2), or null. */
    val tourStep: Int? = null,
    val settings: ControllerSettings = ControllerSettings(),
    /** Extension skills whose suggested keyboard key another skill or the cockpit already held. */
    val keyConflicts: List<KeyConflictUi> = emptyList(),
    val onScreenSticks: Boolean = false,
    val allModesVehicles: List<AllModesVehicle> = emptyList(),
    /** True once a gamepad or joystick was attached at some point in this process. */
    val padSeen: Boolean = false,
) {
    /** Every control of every conflicting row: the hotspots outlined red. */
    val conflictControls: Set<String> get() = conflicts.flatMapTo(HashSet()) { it.controls }

    val canSave: Boolean get() = dirty && conflicts.isEmpty() && missingLocked.isEmpty()

    /** The prompt shown while no controller drives input. */
    val padPrompt: PadPrompt get() = padPromptFor(controller != null, padSeen)
}

/** Settings › Controls: the controller drawing and its bindings, calibration, hardware checks and preferences. */
@HiltViewModel
class ControlsViewModel @Inject constructor(
    private val manager: ControllerManager,
    private val settings: ControllerSettingsRepository,
    private val controls: ControlsRepository,
    val spec: CockpitSpecRepository,
    private val router: AdosInputRouter,
    private val connections: PadConnectionProbe,
    padMemory: PadSeenMemory,
    registry: SkillRegistry,
    nodeRepository: NodeRepository,
    bindings: BindingStore,
) : ViewModel() {

    private val draft = MutableStateFlow<List<ControlBindingSpec>?>(null)
    private val ui = MutableStateFlow(Transient())
    private val installed = MutableStateFlow<List<Skill>>(emptyList())

    private data class Transient(
        val editing: String? = null,
        val press: PressMode? = null,
        val calibration: CalibrationUi? = null,
        val test: HardwareTest? = null,
        val tourStep: Int? = null,
        val tourDismissed: Boolean = false,
    )

    private val _messages = MutableSharedFlow<ControlsMessage>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val messages: SharedFlow<ControlsMessage> = _messages.asSharedFlow()

    /** The active pad's live input, read by the drawing at display refresh. */
    val input: StateFlow<PadSnapshot> = router.snapshot

    private val saved: StateFlow<ControlsState?> = controls.state.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val preferences = combine(settings.settings, nodeRepository.nodes) { s, nodes ->
        s to s.allModesVehicles.sorted().map { value ->
            val id = DeviceId(value)
            AllModesVehicle(id, nodes?.firstOrNull { it.id == id }?.name)
        }
    }

    val state: StateFlow<ControlsUiState> = combine(
        combine(manager.active, saved, draft, installed, bindings.keyConflicts) { active, s, d, skills, keys -> Inputs(active, s, d, skills, keys) },
        ui,
        preferences,
        padMemory.seen,
    ) { inputs, t, (prefs, vehicles), seen -> build(inputs, t, prefs, vehicles, seen) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ControlsUiState())

    private data class Inputs(
        val active: ControllerInfo?,
        val saved: ControlsState?,
        val draft: List<ControlBindingSpec>?,
        val skills: List<Skill>,
        val keyConflicts: List<KeyConflict>,
    )

    private var pressJob: Job? = null
    private var calibrationJob: Job? = null
    private var testJob: Job? = null

    private val pressCapture = object : InputCapture {
        override fun consumesChord(chord: String) = false
        override val capturesEnter: Boolean get() = false
        override val uiNavigation: Boolean get() = false
    }

    init {
        viewModelScope.launch { registry.skills.collect { installed.value = it } }
    }

    private fun build(inputs: Inputs, t: Transient, prefs: ControllerSettings, vehicles: List<AllModesVehicle>, padSeen: Boolean): ControlsUiState {
        val saved = inputs.saved ?: return ControlsUiState()
        val ds4 = spec.ds4
        val active = inputs.active
        val rows: List<ControlBindingSpec>
        val conflicts: List<BindingConflict>
        if (inputs.draft != null) {
            val resolved = Ds4Bindings.resolve(ds4, inputs.draft, inputs.skills)
            rows = resolved.rows
            conflicts = resolved.conflicts
        } else {
            rows = saved.bindings
            conflicts = saved.conflicts
        }
        val tourStep = when {
            t.tourStep != null -> t.tourStep
            active != null && !saved.tourSeen && !t.tourDismissed -> 0
            else -> null
        }
        return ControlsUiState(
            loaded = true,
            controller = active,
            connection = active?.let { connections.connection(it) } ?: PadConnection.Unknown,
            capabilities = active?.let { manager.capabilities(it.deviceId) } ?: ControllerCapabilities.None,
            pad = if (active == null || active.isDualShock4()) PadKind.Ds4 else PadKind.Generic,
            stickMode = saved.stickMode,
            deadzone = saved.deadzone,
            profile = if (inputs.draft != null) ControlsProfile.User else saved.profile,
            rows = rows,
            conflicts = conflicts,
            missingLocked = Ds4Bindings.missingLocked(ds4, rows),
            dirty = inputs.draft != null,
            editing = t.editing,
            press = t.press,
            skillGroups = skillGroups(inputs.skills),
            calibration = t.calibration,
            calibrated = active != null && padKey(active.vendorId, active.productId) in saved.calibrations,
            test = t.test,
            tourStep = tourStep,
            settings = prefs,
            onScreenSticks = saved.onScreenSticks,
            keyConflicts = keyConflictsUi(inputs.keyConflicts, inputs.skills),
            allModesVehicles = vehicles,
            padSeen = padSeen,
        )
    }

    // ---- binding editor ---------------------------------------------------------------

    fun openControl(control: String) {
        ui.value = ui.value.copy(editing = control)
    }

    fun closeControl() {
        ui.value = ui.value.copy(editing = null)
    }

    /** Binds [action] to [row]; a locked row refuses with a message. */
    fun setAction(row: ControlBindingSpec, action: String) = applyEdit(setRowAction(currentRows(), row, action))

    /** Adds a row binding [action] to [control] with [gesture]. */
    fun addBinding(control: String, gesture: String, action: String) = applyEdit(addRow(currentRows(), control, gesture, action))

    /** Moves [row] to [target] (tapping a hotspot while moving). */
    fun moveTo(row: ControlBindingSpec, target: String) {
        endPress()
        applyEdit(moveRow(currentRows(), row, target))
        ui.value = ui.value.copy(editing = target)
    }

    private fun currentRows(): List<ControlBindingSpec> = draft.value ?: state.value.rows

    private fun applyEdit(result: EditResult) {
        when (result) {
            is EditResult.Applied -> draft.value = result.rows
            is EditResult.Refused -> when (result.reason) {
                EditRefusal.Locked -> _messages.tryEmit(ControlsMessage.Locked)
                EditRefusal.SameControl -> _messages.tryEmit(ControlsMessage.SameControl)
                EditRefusal.Missing -> Unit
            }
        }
    }

    fun save() {
        val rows = draft.value ?: return
        viewModelScope.launch {
            when (controls.saveBindings(rows)) {
                SaveResult.Saved -> {
                    draft.value = null
                    _messages.tryEmit(ControlsMessage.Saved)
                }
                is SaveResult.Refused -> _messages.tryEmit(ControlsMessage.SaveRefused)
            }
        }
    }

    fun discard() {
        draft.value = null
    }

    fun resetToDefault() {
        draft.value = null
        viewModelScope.launch { controls.resetBindings() }
    }

    fun setStickMode(mode: StickMode) {
        viewModelScope.launch { controls.setStickMode(mode) }
    }

    fun setDeadzone(value: Float) {
        viewModelScope.launch { controls.setDeadzone(value) }
    }

    /**
     * Starts "Press a control": the next physical button opens its sheet, or receives
     * [moving]. Holding Circle for a second cancels, so every button stays selectable.
     */
    fun startPress(moving: ControlBindingSpec? = null) {
        ui.value = ui.value.copy(press = PressMode(moving), editing = if (moving != null) null else ui.value.editing)
        router.capture = pressCapture
        pressJob?.cancel()
        pressJob = viewModelScope.launch {
            val byIndex = spec.ds4.stream.buttonsBitfield.entries.associate { (control, index) -> index to control }
            router.events.collect { event ->
                if (event !is InputEvent.Button || event.pressed) return@collect
                val press = ui.value.press ?: return@collect
                if (event.button == CANCEL_BUTTON && event.heldMs >= CANCEL_HOLD_MS) {
                    endPress()
                    return@collect
                }
                val control = byIndex[event.button] ?: return@collect
                val row = press.row
                if (row != null) moveTo(row, control) else {
                    endPress()
                    openControl(control)
                }
            }
        }
    }

    /** A hotspot tapped on screen while "Press a control" is on. */
    fun pressedOnScreen(control: String) {
        val row = ui.value.press?.row
        if (row != null) moveTo(row, control) else {
            endPress()
            openControl(control)
        }
    }

    fun endPress() {
        pressJob?.cancel()
        pressJob = null
        if (router.capture === pressCapture) router.capture = null
        ui.value = ui.value.copy(press = null)
    }

    // ---- calibration ------------------------------------------------------------------

    /** Records the attached pad's stick centre and extents: every edge first, then a still release. */
    fun startCalibration() {
        val controller = manager.active.value ?: return
        val key = padKey(controller.vendorId, controller.productId)
        router.suppressStickNavigation = true
        calibrationJob?.cancel()
        ui.value = ui.value.copy(calibration = CalibrationUi(CalibrationPhase.Moving, 0f))
        calibrationJob = viewModelScope.launch {
            val recorder = CalibrationRecorder()
            try {
                // Polled at display rate: a stick held still sends no events, yet stillness must end the release.
                while (true) {
                    val snap = router.snapshot.value
                    val result = if (snap.deviceId == null) null else recorder.add(snap.raw, System.nanoTime() / NANOS_PER_MS)
                    if (result != null) {
                        controls.setCalibration(key, result)
                        ui.value = ui.value.copy(calibration = CalibrationUi(CalibrationPhase.Saved, 1f))
                        break
                    }
                    ui.value = ui.value.copy(calibration = CalibrationUi(recorder.phase, recorder.coverage))
                    delay(CALIBRATION_POLL_MS)
                }
            } finally {
                router.suppressStickNavigation = ui.value.test == HardwareTest.Sticks
            }
        }
    }

    fun closeCalibration() {
        calibrationJob?.cancel()
        calibrationJob = null
        router.suppressStickNavigation = ui.value.test == HardwareTest.Sticks
        ui.value = ui.value.copy(calibration = null)
    }

    // ---- hardware checks --------------------------------------------------------------

    /** Plays the three rumble patterns in turn, even with rumble turned off. */
    fun testRumble() = runCheck(HardwareTest.Rumble) {
        for (pattern in listOf(Rumble.ModeChange, Rumble.Warning, Rumble.Critical)) {
            manager.rumble(pattern, force = true)
            delay((pattern.pulsesMs.sum() + pattern.gapMs * (pattern.pulsesMs.size - 1)).toLong() + RUMBLE_PAUSE_MS)
        }
    }

    /** Cycles the light bar through the cockpit's status colours, then restores the controller default. */
    fun testLightbar() = runCheck(HardwareTest.Lightbar) {
        try {
            for (hex in spec.tokens.lightbar.values) {
                manager.setLightbar(argbOf(hex), force = true)
                delay(LIGHT_STEP_MS)
            }
        } finally {
            manager.setLightbar(null, force = true)
        }
    }

    /** Shows the stick readout and keeps the left stick off UI focus while the sticks are tried; again to stop. */
    fun toggleStickTest() {
        if (ui.value.test == HardwareTest.Sticks) {
            stopTest()
            return
        }
        testJob?.cancel()
        router.suppressStickNavigation = true
        ui.value = ui.value.copy(test = HardwareTest.Sticks)
    }

    private fun runCheck(test: HardwareTest, block: suspend () -> Unit) {
        if (ui.value.test != null) stopTest()
        ui.value = ui.value.copy(test = test)
        testJob = viewModelScope.launch {
            try {
                block()
            } finally {
                if (ui.value.test == test) ui.value = ui.value.copy(test = null)
            }
        }
    }

    fun stopTest() {
        val was = ui.value.test
        testJob?.cancel()
        testJob = null
        if (was == HardwareTest.Sticks && ui.value.calibration == null) router.suppressStickNavigation = false
        ui.value = ui.value.copy(test = null)
    }

    // ---- export, import, tour ---------------------------------------------------------

    suspend fun exportText(): String = controls.exportFile(state.value.pad)

    fun importText(text: String) {
        viewModelScope.launch {
            val message = when (val result = controls.importFile(text)) {
                is ImportResult.Imported -> {
                    draft.value = null
                    ControlsMessage.Imported
                }
                ImportResult.Unreadable -> ControlsMessage.ImportUnreadable
                is ImportResult.Refused -> {
                    val missing = result.problems.filterIsInstance<BindingProblem.MissingLocked>()
                    if (missing.isNotEmpty()) {
                        ControlsMessage.ImportMissingLocked(missing.map { actionLabel(it.locked.action) })
                    } else {
                        ControlsMessage.ImportRefused
                    }
                }
            }
            _messages.tryEmit(message)
        }
    }

    fun fileFailed() {
        _messages.tryEmit(ControlsMessage.FileFailed)
    }

    fun nextTourStep() {
        val step = state.value.tourStep ?: return
        if (step >= TOUR_STEPS - 1) finishTour() else ui.value = ui.value.copy(tourStep = step + 1)
    }

    fun finishTour() {
        ui.value = ui.value.copy(tourStep = null, tourDismissed = true)
        viewModelScope.launch { controls.setTourSeen(true) }
    }

    // ---- preferences ------------------------------------------------------------------

    fun setRumble(on: Boolean) {
        viewModelScope.launch { settings.setRumble(on) }
    }

    fun setLightbar(on: Boolean) {
        viewModelScope.launch { settings.setLightbar(on) }
    }

    fun setExpo(pct: Int) {
        viewModelScope.launch { settings.setExpoPct(pct) }
    }

    fun setOnScreenSticks(on: Boolean) {
        viewModelScope.launch { controls.setOnScreenSticks(on) }
    }

    fun removeAllModes(id: DeviceId) {
        viewModelScope.launch { settings.setAllModes(id, enabled = false) }
    }

    /** The spec label of [action], or the installed skill's label, or the id itself. */
    fun actionLabel(action: String): String = labelOf(action, installed.value)

    private fun labelOf(action: String, skills: List<Skill>): String {
        spec.ds4.actions.firstOrNull { it.id == action }?.let { return it.label }
        val skillId = Ds4Bindings.parseSkillAction(action) ?: return action
        return skills.firstOrNull { it.id == skillId }?.labelKey ?: action
    }

    private fun skillGroups(skills: List<Skill>): List<SkillGroup> = skills
        .filter { it.source is SkillSource.Extension }
        .groupBy { (it.source as SkillSource.Extension).pluginName }
        .map { (plugin, list) -> SkillGroup(plugin, list.map { SkillOption(Ds4Bindings.skillAction(it), it.labelKey) }) }
        .sortedBy { it.pluginName }

    override fun onCleared() {
        endPress()
        closeCalibration()
        stopTest()
    }

    companion object {
        const val TOUR_STEPS = 3

        /** Holding Circle this long cancels "Press a control". */
        const val CANCEL_HOLD_MS = 1_000L
        private const val CANCEL_BUTTON = 1
        private const val LIGHT_STEP_MS = 700L
        private const val RUMBLE_PAUSE_MS = 600L
        private const val CALIBRATION_POLL_MS = 16L
        private const val NANOS_PER_MS = 1_000_000L
    }
}

/** One extension skill left without its suggested key: [holder] null means the cockpit reserves it, "" a built-in. */
data class KeyConflictUi(val chord: String, val skill: String, val holder: String?)

/** Labels the loadout's seed conflicts with the installed skills' names. */
internal fun keyConflictsUi(conflicts: List<KeyConflict>, skills: List<Skill>): List<KeyConflictUi> {
    fun label(id: String) = skills.firstOrNull { it.id == id }?.labelKey ?: id
    return conflicts.map { c ->
        val holder = when (c.holder) {
            RESERVED_HOLDER -> null
            BUILTIN_HOLDER -> ""
            else -> label(c.holder)
        }
        KeyConflictUi(c.chord, label(c.skillId), holder)
    }
}

/** `#RRGGBB` or `#RRGGBBAA` as ARGB. */
internal fun argbOf(hex: String): Int {
    val digits = hex.removePrefix("#")
    val rgb = digits.substring(0, 6).toLong(16).toInt()
    val alpha = if (digits.length >= 8) digits.substring(6, 8).toInt(16) else 0xFF
    return (alpha shl 24) or (rgb and 0xFFFFFF)
}
