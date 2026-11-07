package com.altnautica.gcs.feature.cockpit.skillbar

import com.altnautica.gcs.core.model.PilotState
import com.altnautica.gcs.core.model.Skill
import com.altnautica.gcs.core.model.SkillCategory
import com.altnautica.gcs.core.model.SkillSource
import com.altnautica.gcs.core.model.SkillState
import com.altnautica.gcs.feature.cockpit.SlotUi

/** The picker's tabs, one per extension skill category. */
enum class PickerTab(val category: SkillCategory) {
    Behaviors(SkillCategory.Behavior),
    Camera(SkillCategory.Camera),
    Navigation(SkillCategory.Navigation),
    Utility(SkillCategory.Utility),
}

/** One plugin's skills in a tab, in the order the plugin declares them. */
data class PickerGroup(val pluginId: String, val pluginName: String, val skills: List<SlotUi>)

/** How the bottom-right chip reads: the active skill and its state, or nothing active. */
sealed interface PickerChip {
    data object Idle : PickerChip

    /** A write awaits its echo: the chip shows a pending ring. */
    data class Pending(val skill: Skill) : PickerChip

    data class Active(val skill: Skill, val badge: String?) : PickerChip

    /** The link is lost while the skill runs: ACTIVE (stale), Stop still enabled. */
    data class ActiveStale(val skill: Skill) : PickerChip

    /** The plugin's state is old: the chip dims and reads "last seen n s". */
    data class LastSeen(val skill: Skill, val seconds: Int) : PickerChip
}

/** The open picker: the tab shown, its groups, and the skill whose Tweak panel is open. */
data class SkillPickerUi(
    val tab: PickerTab,
    val groups: List<PickerGroup>,
    val selectedId: String?,
    /** Tabs that hold at least one skill. */
    val populated: Set<PickerTab>,
)

/** Pure picker logic: grouping by plugin, the chip state, and D-pad focus moves. */
object SkillPickerModel {

    /** Registry id of the skill the pilot reports as holding the motion lease. */
    fun pilotSkillId(pilot: PilotState?): String? = pilot?.skill?.let { "${it.pluginId}:${it.skillId}" }

    private fun pluginOf(skill: Skill): Pair<String, String>? = (skill.source as? SkillSource.Extension)?.let { it.pluginId to it.pluginName }

    /** [skills] of [tab], grouped under their plugin; groups keep first-seen order. */
    fun groups(skills: List<Skill>, states: Map<String, SkillState>, tab: PickerTab): List<PickerGroup> {
        val byPlugin = LinkedHashMap<String, Pair<String, MutableList<SlotUi>>>()
        skills.forEach { skill ->
            if (skill.category != tab.category) return@forEach
            val (pluginId, name) = pluginOf(skill) ?: return@forEach
            byPlugin.getOrPut(pluginId) { name to mutableListOf() }.second += SlotUi(0, skill, states[skill.id])
        }
        return byPlugin.map { (id, entry) ->
            PickerGroup(id, entry.first, entry.second.mapIndexed { i, s -> s.copy(index = i) })
        }
    }

    fun populated(skills: List<Skill>): Set<PickerTab> =
        PickerTab.entries.filterTo(LinkedHashSet()) { tab -> skills.any { it.category == tab.category && pluginOf(it) != null } }

    /** Every pickable skill in tab order, then group order: the D-pad focus order. */
    fun focusOrder(skills: List<Skill>): List<Skill> =
        PickerTab.entries.flatMap { tab -> groups(skills, emptyMap(), tab).flatMap { g -> g.skills.mapNotNull { it.skill } } }

    /** The skill [step] places from [current] in [focusOrder], clamped at the ends. */
    fun move(skills: List<Skill>, current: String?, step: Int): Skill? {
        val order = focusOrder(skills)
        if (order.isEmpty()) return null
        val at = order.indexOfFirst { it.id == current }
        val next = if (at < 0) (if (step >= 0) 0 else order.lastIndex) else (at + step).coerceIn(0, order.lastIndex)
        return order[next]
    }

    fun tabOf(skill: Skill): PickerTab? = PickerTab.entries.firstOrNull { it.category == skill.category }

    /** The chip: the pilot's lease holder first, else the first active or pending picker skill. */
    fun chip(skills: List<Skill>, states: Map<String, SkillState>, pilot: PilotState?): PickerChip {
        val leaseId = pilotSkillId(pilot)
        val candidate = skills.firstOrNull { it.id == leaseId }
            ?: skills.firstOrNull { states[it.id]?.pending == true }
            ?: skills.firstOrNull { states[it.id]?.active == true }
            ?: return PickerChip.Idle
        val s = states[candidate.id]
        return when {
            s?.pending == true -> PickerChip.Pending(candidate)
            s?.stale == true -> PickerChip.ActiveStale(candidate)
            s?.lastSeenS != null -> PickerChip.LastSeen(candidate, s.lastSeenS!!)
            else -> PickerChip.Active(candidate, s?.badge)
        }
    }
}
