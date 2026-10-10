package com.altnautica.gcs.feature.cockpit.input

import com.altnautica.gcs.core.skills.binding.Loadout
import com.altnautica.gcs.core.skills.input.GamepadButton
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CockpitInputMapperTest {
    private val loadout = Loadout.DEFAULT
    private fun mask(vararg b: Int) = b.fold(0) { acc, x -> acc or (1 shl x) }

    @Test
    fun `gamepad face buttons activate their slots with the button for the confirm hold`() {
        assertEquals(CockpitAction.ActivateSkill("arm", GamepadButton.A), CockpitInputMapper.onButtonPressed(GamepadButton.A, mask(GamepadButton.A), loadout, false))
        assertEquals(CockpitAction.ActivateSkill("rth", GamepadButton.B), CockpitInputMapper.onButtonPressed(GamepadButton.B, mask(GamepadButton.B), loadout, true))
        assertEquals(CockpitAction.ActivateSkill("land", GamepadButton.X), CockpitInputMapper.onButtonPressed(GamepadButton.X, mask(GamepadButton.X), loadout, true))
        assertEquals(CockpitAction.ActivateSkill("pause", GamepadButton.Y), CockpitInputMapper.onButtonPressed(GamepadButton.Y, mask(GamepadButton.Y), loadout, true))
    }

    @Test
    fun `the arm slot disarms an armed vehicle`() {
        assertEquals(CockpitAction.ActivateSkill("disarm", GamepadButton.A), CockpitInputMapper.onButtonPressed(GamepadButton.A, mask(GamepadButton.A), loadout, true))
        assertEquals(CockpitAction.ActivateSkill("disarm", null), CockpitInputMapper.onChord("shift+a", loadout, true))
    }

    @Test
    fun `cockpit buttons map to cockpit actions`() {
        assertNull(CockpitInputMapper.onButtonPressed(GamepadButton.L1, mask(GamepadButton.L1), loadout, null))
        assertEquals(CockpitAction.ToggleQuickSettings, CockpitInputMapper.onButtonPressed(GamepadButton.R1, mask(GamepadButton.L1, GamepadButton.R1), loadout, null))
        assertEquals(CockpitAction.ToggleSkillDrawer, CockpitInputMapper.onButtonPressed(GamepadButton.SELECT, mask(GamepadButton.SELECT), loadout, null))
        assertEquals(CockpitAction.Exit, CockpitInputMapper.onButtonPressed(GamepadButton.START, mask(GamepadButton.START), loadout, null))
        assertEquals(CockpitAction.CycleStream(-1), CockpitInputMapper.onButtonPressed(GamepadButton.DPAD_LEFT, mask(GamepadButton.DPAD_LEFT), loadout, null))
        assertEquals(CockpitAction.CycleStream(1), CockpitInputMapper.onButtonPressed(GamepadButton.DPAD_RIGHT, mask(GamepadButton.DPAD_RIGHT), loadout, null))
    }

    @Test
    fun `keyboard chords follow the cockpit map`() {
        assertEquals(CockpitAction.SelectStream(2), CockpitInputMapper.onChord("3", loadout, null))
        assertEquals(CockpitAction.CycleStream(1), CockpitInputMapper.onChord("`", loadout, null))
        assertEquals(CockpitAction.TogglePip, CockpitInputMapper.onChord("p", loadout, null))
        assertEquals(CockpitAction.ToggleQuickSettings, CockpitInputMapper.onChord("shift+<", loadout, null))
        assertEquals(CockpitAction.ToggleSkillDrawer, CockpitInputMapper.onChord("ctrl+k", loadout, null))
        assertEquals(CockpitAction.ActivateSkill("mode.loiter", null), CockpitInputMapper.onChord("f1", loadout, null))
        assertNull(CockpitInputMapper.onChord("shift+q", loadout, null))
    }

    @Test
    fun `only handled chords are consumed`() {
        assertTrue(CockpitInputMapper.consumesChord("shift+t", loadout))
        assertTrue(CockpitInputMapper.consumesChord("5", loadout))
        assertFalse(CockpitInputMapper.consumesChord("shift+q", loadout))
    }
}
