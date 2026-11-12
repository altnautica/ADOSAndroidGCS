package com.altnautica.gcs.feature.cockpit

import com.altnautica.gcs.core.model.PilotAction
import com.altnautica.gcs.core.model.PilotProfile
import com.altnautica.gcs.core.model.VehicleCommand
import com.altnautica.gcs.core.skills.MonotonicClock
import com.altnautica.gcs.core.skills.binding.Ds4Bindings
import com.altnautica.gcs.core.skills.builtin.BuiltinIds
import com.altnautica.gcs.core.skills.input.AdosInputRouter
import com.altnautica.gcs.core.skills.input.InputCapture
import com.altnautica.gcs.core.skills.input.InputEvent
import com.altnautica.gcs.core.skills.spec.CockpitSpecRepository
import com.altnautica.gcs.core.skills.spec.ControlBindingSpec
import com.altnautica.gcs.feature.cockpit.confirm.ConfirmController
import com.altnautica.gcs.feature.cockpit.input.CockpitAction
import com.altnautica.gcs.feature.cockpit.input.CockpitInputMapper
import com.altnautica.gcs.feature.cockpit.pilot.CockpitControllerAction
import com.altnautica.gcs.feature.cockpit.pilot.ControllerHold
import com.altnautica.gcs.feature.cockpit.pilot.Ds4ActionDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The cockpit's hardware input while it is visible: keyboard chords and Enter from the
 * router, and the controller's buttons and holds sampled at 50 Hz through the DS4
 * dispatcher. Each action runs on [vm]; with a confirm or an app-style sheet open only the
 * safety actions (brake, return, kill, cancel) act.
 */
internal class CockpitPadInput(
    private val vm: CockpitViewModel,
    private val scope: CoroutineScope,
    private val clock: MonotonicClock,
    private val router: AdosInputRouter,
    private val confirm: ConfirmController,
    private val overlays: CockpitOverlays,
    private val picker: CockpitPicker,
    private val controlRows: StateFlow<List<ControlBindingSpec>>,
    spec: CockpitSpecRepository,
) {
    private var inputJob: Job? = null
    private var controllerJob: Job? = null
    private val dispatcher = Ds4ActionDispatcher(spec.ds4, Ds4Bindings.stopHoldMs(spec.layout))
    private var lastGimbalDps = 0f

    private val _paramNudges = MutableSharedFlow<Int>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** D-pad ↑/↓ with the skill picker open: steps for the focused parameter of the open Tweak panel. */
    val paramNudges: SharedFlow<Int> = _paramNudges.asSharedFlow()

    /** The controller hold in progress (arm, kill, RTH, take-off), for the hold ring. */
    private val _hold = MutableStateFlow<ControllerHold?>(null)
    val hold: StateFlow<ControllerHold?> = _hold.asStateFlow()

    private val capture = object : InputCapture {
        override fun consumesChord(chord: String) = CockpitInputMapper.consumesChord(chord, vm.loadout.value)
        override val capturesEnter: Boolean get() = confirm.isOpen

        // Sheets other than the skill picker navigate like the app; a confirm sheet takes the buttons.
        override val uiNavigation: Boolean get() = overlays.uiNavigation
    }

    /** Starts taking router events and controller ticks; true when it was not already running. */
    fun attach(): Boolean {
        router.capture = capture
        if (inputJob?.isActive == true) return false
        inputJob = scope.launch { router.events.collect(::onInput) }
        controllerJob = scope.launch {
            while (isActive) {
                controllerTick()
                delay(CONTROLLER_TICK_MS)
            }
        }
        return true
    }

    /** Stops taking input and drops the dispatcher's state and any hold in progress. */
    fun detach() {
        if (router.capture === capture) router.capture = null
        inputJob?.cancel()
        inputJob = null
        controllerJob?.cancel()
        controllerJob = null
        dispatcher.reset()
        _hold.value = null
    }

    private fun controllerTick() {
        val pad = router.snapshot.value
        if (pad.deviceId == null) {
            _hold.value = null
            return
        }
        val actions = dispatcher.update(clock.nowMs(), pad, controlRows.value, vm.controllerContext.value)
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
                val id = if (a.skillId == BuiltinIds.ARM && vm.band.value.armed == true) BuiltinIds.DISARM else a.skillId
                vm.activate(id, if (a.held) mapOf(CockpitViewModel.ARG_HELD to true) else emptyMap())
            }
            is CockpitControllerAction.Pilot -> vm.pilotAction(a.action)
            is CockpitControllerAction.Skill -> vm.activate(a.skillId)
            CockpitControllerAction.SkillSelect -> if (overlays.picker.value) {
                picker.selected.value?.let { picker.actions.onGo(it) }
            } else {
                picker.setOpen(true)
            }
            CockpitControllerAction.SkillStop -> vm.stopOrBack()
            is CockpitControllerAction.PickerFocus -> if (overlays.picker.value) picker.moveFocus(a.delta)
            is CockpitControllerAction.ParamAdjust -> if (overlays.picker.value) _paramNudges.tryEmit(a.delta)
            is CockpitControllerAction.LayerLevel -> vm.autonomy.layers.cycleLevel(a.delta)
            is CockpitControllerAction.ProfileCycle -> {
                val profiles = PilotProfile.entries
                val at = profiles.indexOf(vm.pilotState.value?.profile ?: PilotProfile.Normal)
                vm.pilotAction(PilotAction.profile(profiles[(at + a.delta).mod(profiles.size)]))
            }
            CockpitControllerAction.Designate -> vm.autonomy.designateCentre(vm.autonomy.mainCamera())
            CockpitControllerAction.Photo -> vm.capturePhoto()
            CockpitControllerAction.RecordToggle -> vm.toggleRecording()
            is CockpitControllerAction.Zoom -> vm.camera.zoom(a.step)
            is CockpitControllerAction.GimbalPitchRate -> if (a.dps != lastGimbalDps) {
                lastGimbalDps = a.dps
                vm.runCommand(VehicleCommand.GimbalRate(a.dps, 0f))
            }
            CockpitControllerAction.GimbalRecenter -> vm.runCommand(VehicleCommand.GimbalRecenter)
            CockpitControllerAction.ViewNext -> vm.nextView()
            CockpitControllerAction.SwapVideoMap -> vm.toggleMinimapSwap()
            CockpitControllerAction.QuickSettings -> overlays.quickSettings.update { !it }
            is CockpitControllerAction.Buzz -> vm.feedback.onHoldCue(a.kind, a.level)
        }
    }

    private fun onInput(event: InputEvent) {
        val armed = vm.vehicle.value?.telemetry?.heartbeat?.value?.armed
        when (event) {
            is InputEvent.Enter -> confirm.onEnter(event.pressed)
            is InputEvent.Button -> if (confirm.isOpen) confirm.onGamepadButton(event.button, event.pressed)
            is InputEvent.Chord -> if (!confirm.isOpen) CockpitInputMapper.onChord(event.chord, vm.loadout.value, armed)?.let(::perform)
        }
    }

    private fun perform(action: CockpitAction) {
        when (action) {
            is CockpitAction.ActivateSkill -> vm.activate(action.skillId)
            CockpitAction.ToggleQuickSettings -> overlays.quickSettings.update { !it }
            CockpitAction.ToggleSkillPicker -> picker.setOpen(!overlays.picker.value)
            CockpitAction.NextView -> vm.nextView()
            CockpitAction.CycleLayerLevel -> vm.autonomy.layers.cycleLevel(1)
            is CockpitAction.CycleStream -> vm.cycleStream(action.delta)
            is CockpitAction.SelectStream -> vm.selectStreamIndex(action.index)
            CockpitAction.TogglePip -> vm.togglePip()
        }
    }

    private companion object {
        const val CONTROLLER_TICK_MS = 20L
        val SAFETY_BUILTINS = setOf(BuiltinIds.PAUSE, BuiltinIds.RTH, BuiltinIds.KILL)
    }
}
