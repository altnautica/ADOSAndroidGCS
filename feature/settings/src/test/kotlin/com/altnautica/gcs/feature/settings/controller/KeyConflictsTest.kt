package com.altnautica.gcs.feature.settings.controller

import com.altnautica.gcs.core.model.ArmRequirement
import com.altnautica.gcs.core.model.Binding
import com.altnautica.gcs.core.model.CommandResult
import com.altnautica.gcs.core.model.ConfirmPolicy
import com.altnautica.gcs.core.model.Skill
import com.altnautica.gcs.core.model.SkillCategory
import com.altnautica.gcs.core.model.SkillContext
import com.altnautica.gcs.core.model.SkillSource
import com.altnautica.gcs.core.model.SkillState
import com.altnautica.gcs.core.skills.binding.BUILTIN_HOLDER
import com.altnautica.gcs.core.skills.binding.KeyConflict
import com.altnautica.gcs.core.skills.binding.RESERVED_HOLDER
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyConflictsTest {
    private class Ext(override val id: String, override val labelKey: String) : Skill {
        override val icon = "x"
        override val category = SkillCategory.Behavior
        override val source = SkillSource.Extension("p")
        override val toggle = false
        override val confirm: ConfirmPolicy? = null
        override val armRequirement = ArmRequirement.Any
        override val cooldownMs = 0L
        override val defaultBinding: Binding? = null
        override fun state(ctx: SkillContext) = SkillState(enabled = true, active = false, reasonKey = null, cooldownEndsAtMs = null, charges = null)
        override suspend fun activate(ctx: SkillContext, args: Map<String, Any?>) = CommandResult.Accepted
    }

    @Test
    fun `seed collisions are listed with the skills' names and who holds the key`() {
        val skills = listOf(Ext("p:orbit", "Orbit"), Ext("p:scan", "Scan"))
        val rows = keyConflictsUi(
            listOf(
                KeyConflict("o", "p:orbit", "p:scan"),
                KeyConflict("space", RESERVED_HOLDER, "p:orbit"),
                KeyConflict("h", BUILTIN_HOLDER, "p:gone"),
            ),
            skills,
        )
        assertEquals(
            listOf(KeyConflictUi("o", "Scan", "Orbit"), KeyConflictUi("space", "Orbit", null), KeyConflictUi("h", "p:gone", "")),
            rows,
        )
    }
}
