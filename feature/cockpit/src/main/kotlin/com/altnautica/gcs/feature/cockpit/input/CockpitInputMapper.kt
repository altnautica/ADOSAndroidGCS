package com.altnautica.gcs.feature.cockpit.input

import com.altnautica.gcs.core.skills.binding.Loadout
import com.altnautica.gcs.core.skills.input.GamepadButton

sealed interface CockpitAction {
    data class ActivateSkill(val skillId: String, val gamepadButton: Int?) : CockpitAction
    data object ToggleQuickSettings : CockpitAction
    data object ToggleSkillDrawer : CockpitAction
    data object Exit : CockpitAction
    data class CycleStream(val delta: Int) : CockpitAction
    data class SelectStream(val index: Int) : CockpitAction
    data object TogglePip : CockpitAction
}

/** Maps cockpit hardware input (CS-8) to actions. Pure, so the whole map is unit-tested. */
object CockpitInputMapper {
    private val QUICK_SETTINGS_CHORDS = setOf("shift+<", "shift+;")
    private val PALETTE_CHORDS = setOf("ctrl+k", "meta+k")
    private val STREAM_DIGITS = ('1'..'9').map { it.toString() }.toSet()

    /** The "arm" slot disarms an armed vehicle, so one slot covers both. */
    private fun flip(skillId: String, armed: Boolean?) = if (skillId == "arm" && armed == true) "disarm" else skillId

    /** A button press; [pressedMask] already includes [button]. Releases map to nothing. */
    fun onButtonPressed(button: Int, pressedMask: Int, loadout: Loadout, armed: Boolean?): CockpitAction? {
        val l1 = pressedMask and (1 shl GamepadButton.L1) != 0
        val r1 = pressedMask and (1 shl GamepadButton.R1) != 0
        return when (button) {
            GamepadButton.L1, GamepadButton.R1 -> if (l1 && r1) CockpitAction.ToggleQuickSettings else null
            GamepadButton.SELECT -> CockpitAction.ToggleSkillDrawer
            GamepadButton.START -> CockpitAction.Exit
            GamepadButton.DPAD_LEFT -> CockpitAction.CycleStream(-1)
            GamepadButton.DPAD_RIGHT -> CockpitAction.CycleStream(1)
            else -> loadout.slotForButton(button)?.skillId?.let { CockpitAction.ActivateSkill(flip(it, armed), button) }
        }
    }

    fun onChord(chord: String, loadout: Loadout, armed: Boolean?): CockpitAction? = when {
        chord in STREAM_DIGITS -> CockpitAction.SelectStream(chord.toInt() - 1)
        chord == "`" -> CockpitAction.CycleStream(1)
        chord == "p" -> CockpitAction.TogglePip
        chord in QUICK_SETTINGS_CHORDS -> CockpitAction.ToggleQuickSettings
        chord in PALETTE_CHORDS -> CockpitAction.ToggleSkillDrawer
        else -> loadout.slotForChord(chord)?.skillId?.let { CockpitAction.ActivateSkill(flip(it, armed), null) }
    }

    /** True when the cockpit handles [chord], so the key event is consumed. */
    fun consumesChord(chord: String, loadout: Loadout): Boolean =
        chord in STREAM_DIGITS || chord == "`" || chord == "p" || chord in QUICK_SETTINGS_CHORDS ||
            chord in PALETTE_CHORDS || loadout.slotForChord(chord) != null
}
