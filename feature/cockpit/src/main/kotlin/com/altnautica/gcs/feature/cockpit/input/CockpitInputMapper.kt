package com.altnautica.gcs.feature.cockpit.input

import com.altnautica.gcs.core.skills.binding.Loadout

/** Keyboard actions of the cockpit; controller buttons go through the controller bindings instead. */
sealed interface CockpitAction {
    data class ActivateSkill(val skillId: String) : CockpitAction
    data object ToggleQuickSettings : CockpitAction
    data object ToggleSkillPicker : CockpitAction
    data object NextView : CockpitAction
    data object CycleLayerLevel : CockpitAction
    data class CycleStream(val delta: Int) : CockpitAction
    data class SelectStream(val index: Int) : CockpitAction
    data object TogglePip : CockpitAction
}

/** Maps cockpit keyboard chords to actions. Pure, so the whole map is unit-tested. */
object CockpitInputMapper {
    private val QUICK_SETTINGS_CHORDS = setOf("shift+<", "shift+;")
    private val PICKER_CHORDS = setOf("ctrl+k", "meta+k")
    private val STREAM_DIGITS = ('1'..'9').map { it.toString() }.toSet()

    /** The "arm" key disarms an armed vehicle, so one key covers both. */
    private fun flip(skillId: String, armed: Boolean?) = if (skillId == "arm" && armed == true) "disarm" else skillId

    fun onChord(chord: String, loadout: Loadout, armed: Boolean?): CockpitAction? = when {
        chord in STREAM_DIGITS -> CockpitAction.SelectStream(chord.toInt() - 1)
        chord == "`" -> CockpitAction.CycleStream(1)
        chord == "p" -> CockpitAction.TogglePip
        chord == "v" -> CockpitAction.NextView
        chord == "o" -> CockpitAction.CycleLayerLevel
        chord in QUICK_SETTINGS_CHORDS -> CockpitAction.ToggleQuickSettings
        chord in PICKER_CHORDS -> CockpitAction.ToggleSkillPicker
        else -> loadout.slotForChord(chord)?.skillId?.let { CockpitAction.ActivateSkill(flip(it, armed)) }
    }

    /** True when the cockpit handles [chord], so the key event is consumed. */
    fun consumesChord(chord: String, loadout: Loadout): Boolean =
        chord in STREAM_DIGITS || chord == "`" || chord == "p" || chord == "v" || chord == "o" ||
            chord in QUICK_SETTINGS_CHORDS || chord in PICKER_CHORDS || loadout.slotForChord(chord) != null
}
