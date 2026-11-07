package com.altnautica.gcs.feature.cockpit.pilot

import com.altnautica.gcs.core.model.PilotAction
import com.altnautica.gcs.core.model.PilotProfile
import com.altnautica.gcs.core.skills.binding.Ds4Bindings
import com.altnautica.gcs.core.skills.input.PadSnapshot
import com.altnautica.gcs.core.skills.spec.ControlBindingSpec
import com.altnautica.gcs.core.skills.spec.Ds4Spec

/** What a controller gesture asks the cockpit to do. */
sealed interface CockpitControllerAction {
    /** A built-in skill (arm, takeoff, land, rth, pause, resume, take_control, kill); [held] when its hold completed. */
    data class Builtin(val skillId: String, val held: Boolean) : CockpitControllerAction

    /** A pilot action with no built-in skill (rth_cancel, a VTOL transition, a profile). */
    data class Pilot(val action: PilotAction) : CockpitControllerAction

    /** An installed extension skill bound to a control. */
    data class Skill(val skillId: String) : CockpitControllerAction

    data object SkillSelect : CockpitControllerAction
    data object SkillStop : CockpitControllerAction
    data class PickerFocus(val delta: Int) : CockpitControllerAction
    data class ParamAdjust(val delta: Int) : CockpitControllerAction
    data class LayerLevel(val delta: Int) : CockpitControllerAction
    data class ProfileCycle(val delta: Int) : CockpitControllerAction
    data object Designate : CockpitControllerAction
    data object Photo : CockpitControllerAction
    data object RecordToggle : CockpitControllerAction
    data class Zoom(val step: Int) : CockpitControllerAction

    /** Gimbal pitch rate in deg/s (positive up). */
    data class GimbalPitchRate(val dps: Float) : CockpitControllerAction
    data object GimbalRecenter : CockpitControllerAction
    data object ViewNext : CockpitControllerAction
    data object SwapVideoMap : CockpitControllerAction
    data object QuickSettings : CockpitControllerAction

    /** A rumble cue from the binding engine: `mode_change`, `ramp` at [level], or a `countdown` tick. */
    data class Buzz(val kind: String, val level: Float) : CockpitControllerAction
}

/**
 * Turns the controller's pressed buttons into [CockpitControllerAction]s through the
 * effective binding rows ([Ds4Bindings.resolve], user overrides applied). Call [update]
 * on every pad snapshot; it is pure with respect to time, so tests drive it directly.
 */
internal class Ds4ActionDispatcher(private val ds4: Ds4Spec) {
    private val gestures = Ds4Gestures(
        doubleTapMs = ds4.doubleTapMs.toLong(),
        centeredThreshold = ds4.centeredThreshold,
        rampTickMs = ds4.rumble.ramp.tickMs.toLong(),
        rampFrom = ds4.rumble.ramp.from,
        rampTo = ds4.rumble.ramp.to,
        countdownEveryMs = ds4.rumble.countdown.everyMs.toLong(),
    )
    private val bits: Map<String, Int> = ds4.stream.buttonsBitfield
    private val builtinIds: Set<String> = ds4.actions.mapNotNull { it.builtin }.toSet()
    private var gimbalDown = 0f
    private var gimbalUp = 0f

    /** The hold in progress for the hold ring, or null. */
    val hold: ControllerHold? get() = gestures.hold

    fun reset() {
        gestures.reset()
        gimbalDown = 0f
        gimbalUp = 0f
    }

    fun update(nowMs: Long, pad: PadSnapshot, rows: List<ControlBindingSpec>, context: ControllerContext): List<CockpitControllerAction> {
        val pressed = HashSet<String>()
        for ((control, bit) in bits) if (pad.buttons and (1 shl bit) != 0) pressed += control
        val analog = mapOf("l2" to pad.axes.leftTrigger, "r2" to pad.axes.rightTrigger)
        val out = ArrayList<CockpitControllerAction>()
        for (g in gestures.update(nowMs, rows, pressed, analog, pad.axes.leftX to pad.axes.leftY, context::holds)) {
            when (g) {
                is GestureOut.Fire -> map(g.row, g.held, context)?.let(out::add)
                is GestureOut.Buzz -> out += CockpitControllerAction.Buzz(g.kind, g.level)
                is GestureOut.Analog -> gimbal(g.row, g.value)?.let(out::add)
            }
        }
        return out
    }

    private fun gimbal(row: ControlBindingSpec, value: Float): CockpitControllerAction? {
        val full = row.rateFullDps ?: GIMBAL_FULL_DPS
        when (row.action) {
            GIMBAL_DOWN -> gimbalDown = value * full
            GIMBAL_UP -> gimbalUp = value * full
            else -> return null
        }
        return CockpitControllerAction.GimbalPitchRate(gimbalUp - gimbalDown)
    }

    private fun map(row: ControlBindingSpec, held: Boolean, context: ControllerContext): CockpitControllerAction? {
        val a = row.action
        Ds4Bindings.parseSkillAction(a)?.let { return CockpitControllerAction.Skill(it) }
        if (a.startsWith(PROFILE_PREFIX)) {
            return PilotProfile.of(a.removePrefix(PROFILE_PREFIX))?.let { CockpitControllerAction.Pilot(PilotAction.profile(it)) }
        }
        if (a in builtinIds) return CockpitControllerAction.Builtin(a, held)
        return when (a) {
            "rth_cancel" -> CockpitControllerAction.Pilot(PilotAction(PilotAction.RTH_CANCEL))
            "vtol_transition" -> CockpitControllerAction.Pilot(PilotAction.vtol(toFixedWing = context.hover))
            "skill_select" -> CockpitControllerAction.SkillSelect
            "skill_stop" -> CockpitControllerAction.SkillStop
            "picker_focus_prev" -> CockpitControllerAction.PickerFocus(-1)
            "picker_focus_next" -> CockpitControllerAction.PickerFocus(1)
            "param_up" -> CockpitControllerAction.ParamAdjust(1)
            "param_down" -> CockpitControllerAction.ParamAdjust(-1)
            "layer_level_up" -> CockpitControllerAction.LayerLevel(1)
            "layer_level_down" -> CockpitControllerAction.LayerLevel(-1)
            "profile_prev" -> CockpitControllerAction.ProfileCycle(-1)
            "profile_next" -> CockpitControllerAction.ProfileCycle(1)
            "designate" -> CockpitControllerAction.Designate
            "photo" -> CockpitControllerAction.Photo
            "record_toggle" -> CockpitControllerAction.RecordToggle
            "zoom_in" -> CockpitControllerAction.Zoom(1)
            "zoom_out" -> CockpitControllerAction.Zoom(-1)
            "gimbal_recenter" -> CockpitControllerAction.GimbalRecenter
            "view_next" -> CockpitControllerAction.ViewNext
            "swap_video_map" -> CockpitControllerAction.SwapVideoMap
            "quick_settings" -> CockpitControllerAction.QuickSettings
            else -> null
        }
    }

    companion object {
        private const val PROFILE_PREFIX = "profile:"
        private const val GIMBAL_DOWN = "gimbal_pitch_down"
        private const val GIMBAL_UP = "gimbal_pitch_up"

        /** Gimbal rate at full trigger travel when a row does not set its own. */
        const val GIMBAL_FULL_DPS = 60f
    }
}
