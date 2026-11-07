package com.altnautica.gcs.feature.cockpit.skillbar

import com.altnautica.gcs.core.model.ArmRequirement
import com.altnautica.gcs.core.model.Binding
import com.altnautica.gcs.core.model.CommandResult
import com.altnautica.gcs.core.model.ConfirmPolicy
import com.altnautica.gcs.core.model.Skill
import com.altnautica.gcs.core.model.SkillCategory
import com.altnautica.gcs.core.model.SkillContext
import com.altnautica.gcs.core.model.SkillSource
import com.altnautica.gcs.core.model.SkillState
import org.junit.Assert.assertEquals
import org.junit.Test

class SkillPickerModelTest {
    private class S(
        override val id: String,
        override val category: SkillCategory,
        override val source: SkillSource,
    ) : Skill {
        override val labelKey = id
        override val icon = "x"
        override val toggle = true
        override val confirm: ConfirmPolicy? = null
        override val armRequirement = ArmRequirement.Any
        override val cooldownMs = 0L
        override val defaultBinding: Binding? = null
        override fun state(ctx: SkillContext) = SkillState(true, false, null, null, null)
        override suspend fun activate(ctx: SkillContext, args: Map<String, Any?>) = CommandResult.Accepted
    }

    private val gimbal = SkillSource.Extension("cam.gimbal", "Gimbal")
    private val follow = SkillSource.Extension("follow", "Follow me")
    private val skills = listOf(
        S("cam.gimbal:photo", SkillCategory.Camera, gimbal),
        S("follow:follow", SkillCategory.Behavior, follow),
        S("cam.gimbal:record", SkillCategory.Camera, gimbal),
        S("follow:orbit", SkillCategory.Behavior, follow),
        S("cam.gimbal:track", SkillCategory.Behavior, gimbal),
        S("rth", SkillCategory.Flight, SkillSource.Builtin),
    )

    private fun ids(groups: List<PickerGroup>) = groups.map { g -> g.pluginName to g.skills.map { it.skill!!.id to it.index } }

    @Test
    fun `a tab groups skills under their plugin in first-seen order and declared order`() {
        assertEquals(
            listOf(
                "Follow me" to listOf("follow:follow" to 0, "follow:orbit" to 1),
                "Gimbal" to listOf("cam.gimbal:track" to 0),
            ),
            ids(SkillPickerModel.groups(skills, emptyMap(), PickerTab.Behaviors)),
        )
        assertEquals(
            listOf("Gimbal" to listOf("cam.gimbal:photo" to 0, "cam.gimbal:record" to 1)),
            ids(SkillPickerModel.groups(skills, emptyMap(), PickerTab.Camera)),
        )
    }

    @Test
    fun `built-ins never appear and only tabs with skills are populated`() {
        assertEquals(setOf(PickerTab.Behaviors, PickerTab.Camera), SkillPickerModel.populated(skills))
        assertEquals(emptyList<PickerGroup>(), SkillPickerModel.groups(skills, emptyMap(), PickerTab.Utility))
    }

    @Test
    fun `focus moves through tabs then groups and clamps at the ends`() {
        val order = SkillPickerModel.focusOrder(skills).map { it.id }
        assertEquals(listOf("follow:follow", "follow:orbit", "cam.gimbal:track", "cam.gimbal:photo", "cam.gimbal:record"), order)
        assertEquals("follow:follow", SkillPickerModel.move(skills, null, 1)!!.id)
        assertEquals("cam.gimbal:record", SkillPickerModel.move(skills, null, -1)!!.id)
        assertEquals("cam.gimbal:photo", SkillPickerModel.move(skills, "cam.gimbal:track", 1)!!.id)
        assertEquals("cam.gimbal:record", SkillPickerModel.move(skills, "cam.gimbal:record", 1)!!.id)
    }

    @Test
    fun `the chip reads pending, then stale, then last seen, then active`() {
        val orbit = skills[3]
        fun chip(st: SkillState) = SkillPickerModel.chip(skills, mapOf(orbit.id to st), null)
        val base = SkillState(true, true, null, null, null)
        assertEquals(PickerChip.Pending(orbit), chip(base.copy(pending = true, stale = true)))
        assertEquals(PickerChip.ActiveStale(orbit), chip(base.copy(stale = true, lastSeenS = 9)))
        assertEquals(PickerChip.LastSeen(orbit, 9), chip(base.copy(lastSeenS = 9)))
        assertEquals(PickerChip.Active(orbit, "2"), chip(base.copy(badge = "2")))
        assertEquals(PickerChip.Idle, SkillPickerModel.chip(skills, emptyMap(), null))
    }
}
