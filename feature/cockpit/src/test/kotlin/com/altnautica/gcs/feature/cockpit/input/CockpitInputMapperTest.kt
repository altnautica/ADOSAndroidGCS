package com.altnautica.gcs.feature.cockpit.input

import com.altnautica.gcs.core.skills.binding.Loadout
import com.altnautica.gcs.feature.cockpit.testSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CockpitInputMapperTest {
    private val loadout = Loadout.defaults(testSpec.builtins)

    @Test
    fun `built-in keyboard chords from the spec table activate their skills`() {
        assertEquals(CockpitAction.ActivateSkill("pause"), CockpitInputMapper.onChord("space", loadout, armed = true))
        assertEquals(CockpitAction.ActivateSkill("takeoff"), CockpitInputMapper.onChord("shift+t", loadout, armed = false))
        assertEquals(CockpitAction.ActivateSkill("land"), CockpitInputMapper.onChord("shift+l", loadout, armed = true))
        assertEquals(CockpitAction.ActivateSkill("rth"), CockpitInputMapper.onChord("shift+r", loadout, armed = true))
        assertEquals(CockpitAction.ActivateSkill("resume"), CockpitInputMapper.onChord("shift+p", loadout, armed = true))
        assertEquals(CockpitAction.ActivateSkill("take_control"), CockpitInputMapper.onChord("shift+c", loadout, armed = true))
        assertEquals(CockpitAction.ActivateSkill("kill"), CockpitInputMapper.onChord("shift+x", loadout, armed = true))
    }

    @Test
    fun `the arm key disarms an armed vehicle`() {
        assertEquals(CockpitAction.ActivateSkill("arm"), CockpitInputMapper.onChord("shift+a", loadout, armed = false))
        assertEquals(CockpitAction.ActivateSkill("disarm"), CockpitInputMapper.onChord("shift+a", loadout, armed = true))
    }

    @Test
    fun `function keys pick the flight profile`() {
        val f1 = CockpitInputMapper.onChord("f1", loadout, armed = true) as CockpitAction.ActivateSkill
        val f3 = CockpitInputMapper.onChord("f3", loadout, armed = true) as CockpitAction.ActivateSkill
        assertTrue(f1.skillId.contains("cine"))
        assertTrue(f3.skillId.contains("sport"))
    }

    @Test
    fun `view, layer, stream, picture-in-picture, picker and quick settings keys`() {
        assertEquals(CockpitAction.NextView, CockpitInputMapper.onChord("v", loadout, armed = null))
        assertEquals(CockpitAction.CycleLayerLevel, CockpitInputMapper.onChord("o", loadout, armed = null))
        assertEquals(CockpitAction.SelectStream(2), CockpitInputMapper.onChord("3", loadout, armed = null))
        assertEquals(CockpitAction.CycleStream(1), CockpitInputMapper.onChord("`", loadout, armed = null))
        assertEquals(CockpitAction.TogglePip, CockpitInputMapper.onChord("p", loadout, armed = null))
        assertEquals(CockpitAction.ToggleSkillPicker, CockpitInputMapper.onChord("ctrl+k", loadout, armed = null))
        assertEquals(CockpitAction.ToggleQuickSettings, CockpitInputMapper.onChord("shift+<", loadout, armed = null))
    }

    @Test
    fun `the mode presets and abort are gone and unbound keys pass through`() {
        assertFalse(loadout.slots.any { it.skillId == "abort" || it.skillId?.startsWith("mode.") == true })
        assertNull(CockpitInputMapper.onChord("q", loadout, armed = true))
        assertFalse(CockpitInputMapper.consumesChord("q", loadout))
        assertTrue(CockpitInputMapper.consumesChord("space", loadout))
    }
}
