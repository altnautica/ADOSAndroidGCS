package com.altnautica.gcs.feature.cockpit.input

import com.altnautica.gcs.core.skills.binding.Loadout
import com.altnautica.gcs.core.skills.input.GamepadButton

sealed interface CockpitAction {
    data class ActivateSkill(val skillId: String, val gamepadButton: Int?) : CockpitAction
    data object ToggleQuickSettings : CockpitAction
    data object ToggleSkillDrawer : CockpitAction

    /** Closes the top overlay, or asks to leave the cockpit when none is open. */
    data object Back : CockpitAction

    /** Swaps the main view between video and map. */
    data object SwapView : CockpitAction
    data object ToggleStickControl : CockpitAction
    data object OpenModePicker : CockpitAction
    data object ToggleRecording : CockpitAction
    data class CycleStream(val delta: Int) : CockpitAction
    data class SelectStream(val index: Int) : CockpitAction
    data object TogglePip : CockpitAction
}

/** Maps cockpit hardware input to actions. Pure, so the whole map is unit-tested. */
object CockpitInputMapper {
    private val QUICK_SETTINGS_CHORDS = setOf("shift+<", "shift+;")
    private val PALETTE_CHORDS = setOf("ctrl+k", "meta+k")
    private val STREAM_DIGITS = ('1'..'9').map { it.toString() }.toSet()

    /** A Start press shorter than this opens quick settings; longer is the app menu, handled by the router. */
    const val START_SHORT_MS = 600L

    /** The "arm" slot disarms an armed vehicle, so one slot covers both. */
    private fun flip(skillId: String, armed: Boolean?) = if (skillId == "arm" && armed == true) "disarm" else skillId

    private fun held(mask: Int, button: Int) = mask and (1 shl button) != 0

    /** A button press; [pressedMask] already includes [button]. */
    fun onButtonPressed(button: Int, pressedMask: Int, loadout: Loadout, armed: Boolean?): CockpitAction? = when (button) {
        GamepadButton.B -> CockpitAction.Back
        GamepadButton.Y -> CockpitAction.SwapView
        GamepadButton.SELECT -> CockpitAction.ToggleRecording
        GamepadButton.L3 -> if (held(pressedMask, GamepadButton.R3)) CockpitAction.ToggleStickControl else null
        GamepadButton.R3 -> if (held(pressedMask, GamepadButton.L3)) CockpitAction.ToggleStickControl else null
        GamepadButton.DPAD_LEFT -> CockpitAction.CycleStream(-1)
        GamepadButton.DPAD_RIGHT -> CockpitAction.CycleStream(1)
        GamepadButton.DPAD_UP -> CockpitAction.OpenModePicker
        GamepadButton.DPAD_DOWN -> CockpitAction.ToggleSkillDrawer
        // Start acts on release; Home is the app's quick menu, emitted by the router.
        GamepadButton.START, GamepadButton.HOME -> null
        else -> loadout.slotForButton(button)?.skillId?.let { CockpitAction.ActivateSkill(flip(it, armed), button) }
    }

    /** A button release after [heldMs]: a short Start toggles quick settings. */
    fun onButtonReleased(button: Int, heldMs: Long): CockpitAction? =
        if (button == GamepadButton.START && heldMs < START_SHORT_MS) CockpitAction.ToggleQuickSettings else null

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
