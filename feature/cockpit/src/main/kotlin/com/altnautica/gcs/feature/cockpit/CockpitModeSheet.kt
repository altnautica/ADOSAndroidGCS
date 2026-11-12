package com.altnautica.gcs.feature.cockpit

import com.altnautica.gcs.core.model.AvoidMode
import com.altnautica.gcs.core.model.ControlMode
import com.altnautica.gcs.core.model.FlightMode
import com.altnautica.gcs.core.model.PilotAction
import com.altnautica.gcs.core.model.PilotProfile
import com.altnautica.gcs.core.model.Reach
import com.altnautica.gcs.core.model.SkillState
import com.altnautica.gcs.core.model.Vehicle
import com.altnautica.gcs.core.skills.builtin.SetModeSkill
import com.altnautica.gcs.core.skills.builtin.SkillArgs
import com.altnautica.gcs.feature.cockpit.modes.ModePickerUi
import com.altnautica.gcs.feature.cockpit.modes.ModeSheetActions
import com.altnautica.gcs.feature.cockpit.modes.ModeSheetUi
import com.altnautica.gcs.feature.cockpit.modes.modeChoices
import com.altnautica.gcs.feature.cockpit.pilot.CockpitPilot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** The Mode sheet: pilot settings, handover and every firmware mode, and what each choice sends. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class CockpitModeSheet(
    scope: CoroutineScope,
    vehicle: StateFlow<Vehicle?>,
    pilot: CockpitPilot,
    states: StateFlow<Map<String, SkillState>>,
    private val overlays: CockpitOverlays,
    /** True when picking that FC mode ends stick control. */
    endsStick: (String) -> Boolean,
    private val activate: (String, Map<String, Any?>) -> Unit,
    /** Sends a pilot action; `true` first stops the running motion skill. */
    private val pilotAction: (PilotAction, Boolean) -> Unit,
) {
    private val modeList: Flow<Pair<List<FlightMode>, String?>> = vehicle.flatMapLatest { v ->
        if (v == null) {
            flowOf(emptyList<FlightMode>() to null)
        } else {
            combine(v.control.availableModes, v.telemetry.heartbeat.map { it?.mode?.name }.distinctUntilChanged()) { modes, current -> modes to current }
        }
    }

    /** The Mode sheet while open. */
    val ui: StateFlow<ModeSheetUi?> = combine(
        overlays.modePicker,
        modeList,
        states,
        combine(pilot.state, pilot.linked, vehicle.flatMapLatest { it?.reach ?: flowOf(null) }) { p, l, r -> Triple(p, l, r) },
    ) { open, (modes, current), s, (p, linked, reach) ->
        if (!open) return@combine null
        val state = s[SetModeSkill.id]
        ModeSheetUi(
            profile = p?.profile,
            controlMode = p?.controlMode,
            avoid = p?.avoid,
            owner = p?.owner,
            vehicleClass = p?.vehicleClass,
            elrsAvailable = reach is Reach.ViaGround,
            pilotLinked = linked,
            fcModes = ModePickerUi(modeChoices(modes, current, endsStick), enabled = state?.enabled == true, reasonKey = state?.takeIf { !it.enabled }?.reasonKey),
        )
    }.stateIn(scope, SharingStarted.WhileSubscribed(UI_STOP_MS), null)

    val actions = object : ModeSheetActions {
        override fun onProfile(profile: PilotProfile) = pilotAction(PilotAction.profile(profile), true)
        override fun onControlMode(mode: ControlMode) = pilotAction(PilotAction.controlMode(mode), true)
        override fun onAvoid(mode: AvoidMode) = pilotAction(PilotAction.avoid(mode), true)
        override fun onHandToFc(mode: String) = pilotAction(PilotAction(PilotAction.HAND_TO_FC, mapOf("mode" to mode)), true)
        override fun onTakeControl() = pilotAction(PilotAction(PilotAction.TAKE_CONTROL), false)
        override fun onFcMode(name: String) {
            overlays.modePicker.value = false
            activate(SetModeSkill.id, mapOf(SkillArgs.TARGET_MODE to name))
        }
        override fun onVtol(toFixedWing: Boolean) = pilotAction(PilotAction.vtol(toFixedWing), false)
        override fun onDismiss() = setOpen(false)
    }

    fun setOpen(open: Boolean) {
        overlays.modePicker.value = open
    }
}
