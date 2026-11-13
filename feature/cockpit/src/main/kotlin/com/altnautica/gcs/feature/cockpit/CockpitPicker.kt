package com.altnautica.gcs.feature.cockpit

import com.altnautica.gcs.core.model.PilotState
import com.altnautica.gcs.core.skills.SkillSession
import com.altnautica.gcs.feature.cockpit.skillbar.PickerChip
import com.altnautica.gcs.feature.cockpit.skillbar.PickerTab
import com.altnautica.gcs.feature.cockpit.skillbar.SkillPickerActions
import com.altnautica.gcs.feature.cockpit.skillbar.SkillPickerModel
import com.altnautica.gcs.feature.cockpit.skillbar.SkillPickerUi
import com.altnautica.gcs.feature.cockpit.skillbar.firesDirectly
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The skill picker: its chip, its open sheet's tab and selection, and the sheet's actions. */
internal class CockpitPicker(
    private val scope: CoroutineScope,
    private val session: SkillSession,
    private val overlays: CockpitOverlays,
    pilot: StateFlow<PilotState?>,
    private val activate: (String) -> Unit,
) {
    private val tab = MutableStateFlow(PickerTab.Behaviors)

    /** The skill the open picker has selected, if any. */
    val selected = MutableStateFlow<String?>(null)

    /** The chip reads only the pilot's leased skill: other pilot fields changing re-resolve nothing. */
    val chip: StateFlow<PickerChip> = combine(
        session.pickerSkills, session.states,
        pilot.distinctUntilChanged { a, b -> SkillPickerModel.pilotSkillId(a) == SkillPickerModel.pilotSkillId(b) },
    ) { skills, states, p ->
        SkillPickerModel.chip(skills, states, p)
    }.stateIn(scope, SharingStarted.WhileSubscribed(UI_STOP_MS), PickerChip.Idle)

    val ui: StateFlow<SkillPickerUi?> = combine(overlays.picker, session.pickerSkills, session.states, tab, selected) { open, skills, states, t, sel ->
        if (!open) return@combine null
        val populated = SkillPickerModel.populated(skills)
        val shown = if (t in populated || populated.isEmpty()) t else populated.first()
        SkillPickerUi(shown, SkillPickerModel.groups(skills, states, shown), sel, populated)
    }.stateIn(scope, SharingStarted.WhileSubscribed(UI_STOP_MS), null)

    val actions = object : SkillPickerActions {
        override fun onTab(tab: PickerTab) {
            this@CockpitPicker.tab.value = tab
        }

        override fun onSelect(skillId: String) {
            val skill = session.pickerSkills.value.firstOrNull { it.id == skillId } ?: return
            if (firesDirectly(skill)) activate(skillId) else selected.value = skillId
        }

        override fun onGo(skillId: String) = activate(skillId)

        override fun onStop(skillId: String) {
            scope.launch { session.stop(skillId) }
        }

        override fun onDismiss() = setOpen(false)
    }

    fun setOpen(open: Boolean) {
        overlays.picker.value = open
        if (open) {
            val active = (chip.value as? PickerChip.Active)?.skill
            selected.value = active?.id ?: selected.value
            active?.let { SkillPickerModel.tabOf(it) }?.let { tab.value = it }
        }
    }

    /** Moves the selection [step] skills on, switching to its tab. */
    fun moveFocus(step: Int) {
        val next = SkillPickerModel.move(session.pickerSkills.value, selected.value, step) ?: return
        selected.value = next.id
        SkillPickerModel.tabOf(next)?.let { tab.value = it }
    }
}
