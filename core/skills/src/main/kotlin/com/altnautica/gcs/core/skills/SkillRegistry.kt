package com.altnautica.gcs.core.skills

import com.altnautica.gcs.core.model.PilotVehicleClass
import com.altnautica.gcs.core.model.Skill
import com.altnautica.gcs.core.model.SkillCategory
import com.altnautica.gcs.core.model.SkillContributor
import com.altnautica.gcs.core.model.SkillSource
import com.altnautica.gcs.core.skills.builtin.BuiltinSkills
import com.altnautica.gcs.core.skills.spec.CockpitSpecRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onStart

/**
 * Built-in skills (from the cockpit spec's built-in table) plus every [SkillContributor]
 * (extensions). A contributed skill whose id collides with a built-in is dropped:
 * built-ins own their ids.
 */
@Singleton
class SkillRegistry @Inject constructor(
    private val contributors: Set<@JvmSuppressWildcards SkillContributor>,
    private val spec: CockpitSpecRepository,
) {
    /** Every registered skill, ordered by category then registration order. */
    val skills: Flow<List<Skill>> = run {
        val builtins: Flow<List<Skill>> = flow { emit(BuiltinSkills.from(spec.builtins, spec.ds4)) }
        val sources: List<Flow<List<Skill>>> = listOf(builtins) + contributors.map { it.skills.onStart { emit(emptyList()) } }
        combine(sources) { lists -> orderSkills(lists.toList()) }.distinctUntilChanged()
    }

    companion object {
        private fun categoryRank(category: SkillCategory) = when (category) {
            SkillCategory.Flight -> 0
            SkillCategory.Behavior -> 1
            SkillCategory.Camera -> 2
            SkillCategory.Navigation -> 3
            SkillCategory.Utility -> 4
            SkillCategory.Safety -> 5
        }

        /** Flattens contributed lists in registration order, de-duplicates ids, then sorts by category. */
        fun orderSkills(lists: List<List<Skill>>): List<Skill> {
            val seen = HashSet<String>()
            val flat = ArrayList<Skill>()
            for (list in lists) for (skill in list) if (seen.add(skill.id)) flat += skill
            // sortedBy is stable, so registration order survives within a category.
            return flat.sortedBy { categoryRank(it.category) }
        }

        /**
         * Extension skills offered in the skill picker for a vehicle of class
         * [vehicleClass]; a skill that excludes the class is hidden. An unknown class
         * hides nothing.
         */
        fun pickerSkills(all: List<Skill>, vehicleClass: PilotVehicleClass?): List<Skill> = all.filter { skill ->
            skill.source is SkillSource.Extension && (vehicleClass == null || vehicleClass in skill.vehicleClasses)
        }
    }
}
