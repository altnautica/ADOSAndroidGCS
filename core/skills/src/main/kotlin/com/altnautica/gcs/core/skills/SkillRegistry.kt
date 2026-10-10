package com.altnautica.gcs.core.skills

import com.altnautica.gcs.core.model.Skill
import com.altnautica.gcs.core.model.SkillCategory
import com.altnautica.gcs.core.model.SkillContext
import com.altnautica.gcs.core.model.SkillContributor
import com.altnautica.gcs.core.skills.builtin.BuiltinSkills
import com.altnautica.gcs.core.skills.builtin.BarTraits
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart

/**
 * Built-in skills plus every [SkillContributor] (extensions). A contributed skill
 * whose id collides with a built-in is dropped: built-ins own their ids.
 */
@Singleton
class SkillRegistry @Inject constructor(
    private val contributors: Set<@JvmSuppressWildcards SkillContributor>,
) {
    /** Every registered skill, ordered by category then registration order. */
    val skills: Flow<List<Skill>> = run {
        val sources: List<Flow<List<Skill>>> =
            listOf(flowOf(BuiltinSkills.all)) + contributors.map { it.skills.onStart { emit(emptyList()) } }
        combine(sources) { lists -> orderSkills(lists.toList()) }.distinctUntilChanged()
    }

    companion object {
        private fun categoryRank(category: SkillCategory) = when (category) {
            SkillCategory.Flight -> 0
            SkillCategory.Behavior -> 1
            SkillCategory.Camera -> 2
            SkillCategory.Safety -> 3
        }

        /** Flattens contributed lists in registration order, de-duplicates ids, then sorts by category. */
        fun orderSkills(lists: List<List<Skill>>): List<Skill> {
            val seen = HashSet<String>()
            val flat = ArrayList<Skill>()
            for (list in lists) for (skill in list) if (seen.add(skill.id)) flat += skill
            // sortedBy is stable, so registration order survives within a category.
            return flat.sortedBy { categoryRank(it.category) }
        }

        /** Skills that may occupy a Skill Bar slot for the vehicle described by [ctx]. */
        fun barSkills(all: List<Skill>, ctx: SkillContext): List<Skill> = all.filter { skill ->
            val traits = skill as? BarTraits
            if (traits?.bindable == false) return@filter false
            if (traits?.presentFor(ctx) == false) return@filter false
            // Hidden only when autonomous navigation is known to be absent; unknown keeps it.
            !(traits?.requiresAutonomousNav == true && ctx.autonomousNav == false)
        }
    }
}
