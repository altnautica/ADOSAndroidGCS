package com.altnautica.gcs.core.skills.binding

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.altnautica.gcs.core.model.Binding
import com.altnautica.gcs.core.model.CommandResult
import com.altnautica.gcs.core.model.Skill
import com.altnautica.gcs.core.model.SkillCategory
import com.altnautica.gcs.core.model.SkillContext
import com.altnautica.gcs.core.model.SkillSource
import com.altnautica.gcs.core.model.SkillState
import com.altnautica.gcs.core.model.ArmRequirement
import com.altnautica.gcs.core.skills.input.GamepadButton
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LoadoutTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `factory loadout has twelve slots with two free`() {
        assertEquals(12, Loadout.DEFAULT.slots.size)
        assertEquals(2, Loadout.DEFAULT.slots.count { it.skillId == null })
        assertEquals("arm", Loadout.DEFAULT.slotForButton(GamepadButton.A)?.skillId)
        assertEquals("rth", Loadout.DEFAULT.slotForButton(GamepadButton.B)?.skillId)
        assertEquals("land", Loadout.DEFAULT.slotForButton(GamepadButton.X)?.skillId)
        assertEquals("pause", Loadout.DEFAULT.slotForButton(GamepadButton.Y)?.skillId)
    }

    @Test
    fun `seeding takes the first empty slot with its suggested inputs`() {
        val (next, outcome) = Loadout.DEFAULT.seed("gimbal:recenter", Binding("shift+c", 6))
        assertEquals(SeedOutcome.Bound, outcome)
        val slot = next.slotFor("gimbal:recenter")!!
        assertEquals(10, slot.index)
        assertEquals("shift+c", slot.keyChord)
        assertEquals(6, slot.gamepadButton)
    }

    @Test
    fun `colliding or reserved suggestions are slotted unbound`() {
        val (next, outcome) = Loadout.DEFAULT.seed("thermal:palette", Binding("shift+a", GamepadButton.START))
        assertEquals(SeedOutcome.Unbound, outcome)
        val slot = next.slotFor("thermal:palette")!!
        assertNull(slot.keyChord)
        assertNull(slot.gamepadButton)
        val (again, reserved) = next.seed("siyi:palette", Binding("p", null))
        assertEquals(SeedOutcome.Unbound, reserved)
        assertNull(again.slotFor("siyi:palette")!!.keyChord)
    }

    @Test
    fun `each skill is offered once and a full bar skips without recording`() {
        var loadout = Loadout.DEFAULT
        loadout = loadout.seed("a:1", null).first
        assertEquals(SeedOutcome.Skipped, loadout.seed("a:1", null).second)
        loadout = loadout.seed("a:2", null).first
        val (full, outcome) = loadout.seed("a:3", null)
        assertEquals(SeedOutcome.Skipped, outcome)
        assertEquals(listOf("a:1", "a:2"), full.seededSkillIds)
        // Freeing a slot lets the skipped skill land on the next offer.
        val freed = full.assign(10, null)
        assertEquals(SeedOutcome.Bound, freed.seed("a:3", null).second)
    }

    @Test
    fun `binding a reserved chord or button is refused`() {
        assertEquals(BindResult.Reserved, Loadout.DEFAULT.bindChord(0, "ctrl+k").second)
        assertEquals(BindResult.Reserved, Loadout.DEFAULT.bindButton(0, GamepadButton.SELECT).second)
        assertEquals(BindResult.OutOfRange, Loadout.DEFAULT.bindButton(0, 16).second)
    }

    @Test
    fun `a chord moves off its previous slot`() {
        val (next, result) = Loadout.DEFAULT.bindChord(10, "shift+a")
        assertEquals(BindResult.Ok, result)
        assertNull(next.slots[0].keyChord)
        assertEquals("shift+a", next.slots[10].keyChord)
    }

    @Test
    fun `store seeds extension skills and persists the loadout`() = runTest {
        val store = BindingStore(PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "t.preferences_pb") })
        val outcomes = store.seedExtensionDefaults(listOf(ext("follow:follow", Binding("shift+f", 7))))
        assertEquals(mapOf("follow:follow" to SeedOutcome.Bound), outcomes)
        assertEquals(10, store.loadout.first().slotFor("follow:follow")!!.index)
        // A second pass offers nothing new.
        assertEquals(emptyMap<String, SeedOutcome>(), store.seedExtensionDefaults(listOf(ext("follow:follow", Binding("shift+f", 7)))))
    }

    private fun ext(id: String, binding: Binding?) = object : Skill {
        override val id = id
        override val labelKey = id
        override val icon = "x"
        override val category = SkillCategory.Behavior
        override val source = SkillSource.Extension(id.substringBefore(':'))
        override val toggle = true
        override val confirm = null
        override val armRequirement = ArmRequirement.Any
        override val cooldownMs = 0L
        override val defaultBinding = binding
        override fun state(ctx: SkillContext) = SkillState(true, false, null, null, null)
        override suspend fun activate(ctx: SkillContext, args: Map<String, Any?>) = CommandResult.Accepted
    }
}
