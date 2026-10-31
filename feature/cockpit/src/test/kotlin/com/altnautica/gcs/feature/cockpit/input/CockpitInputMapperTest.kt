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
    private fun press(button: Int, vararg held: Int, armed: Boolean? = null) =
        CockpitInputMapper.onButtonPressed(button, mask(button, *held), loadout, armed)

    @Test
    fun `skill buttons activate their slots with the button for the confirm hold`() {
        assertEquals(CockpitAction.ActivateSkill("arm", GamepadButton.A), press(GamepadButton.A, armed = false))
        assertEquals(CockpitAction.ActivateSkill("pause", GamepadButton.X), press(GamepadButton.X, armed = true))
        assertEquals(CockpitAction.ActivateSkill("land", GamepadButton.L1), press(GamepadButton.L1, armed = true))
        assertEquals(CockpitAction.ActivateSkill("rth", GamepadButton.R1), press(GamepadButton.R1, armed = true))
        assertEquals(CockpitAction.ActivateSkill("takeoff", GamepadButton.L2), press(GamepadButton.L2, armed = false))
        assertEquals(CockpitAction.ActivateSkill("mode.loiter", GamepadButton.R2), press(GamepadButton.R2, armed = true))
    }

    @Test
    fun `the arm slot disarms an armed vehicle`() {
        assertEquals(CockpitAction.ActivateSkill("disarm", GamepadButton.A), press(GamepadButton.A, armed = true))
        assertEquals(CockpitAction.ActivateSkill("disarm", null), CockpitInputMapper.onChord("shift+a", loadout, true))
    }

    @Test
    fun `cockpit buttons map to cockpit actions`() {
        assertEquals(CockpitAction.Back, press(GamepadButton.B))
        assertEquals(CockpitAction.SwapView, press(GamepadButton.Y))
        assertEquals(CockpitAction.ToggleRecording, press(GamepadButton.SELECT))
        assertEquals(CockpitAction.CycleStream(-1), press(GamepadButton.DPAD_LEFT))
        assertEquals(CockpitAction.CycleStream(1), press(GamepadButton.DPAD_RIGHT))
        assertEquals(CockpitAction.OpenModePicker, press(GamepadButton.DPAD_UP))
        assertEquals(CockpitAction.ToggleSkillDrawer, press(GamepadButton.DPAD_DOWN))
    }

    @Test
    fun `stick control toggles only with both stick clicks`() {
        assertNull(press(GamepadButton.L3))
        assertNull(press(GamepadButton.R3))
        assertEquals(CockpitAction.ToggleStickControl, press(GamepadButton.R3, GamepadButton.L3))
        assertEquals(CockpitAction.ToggleStickControl, press(GamepadButton.L3, GamepadButton.R3))
    }

    @Test
    fun `start and home do nothing on press`() {
        assertNull(press(GamepadButton.START))
        assertNull(press(GamepadButton.HOME))
    }

    @Test
    fun `a short start release opens quick settings and a long one does not`() {
        assertEquals(CockpitAction.ToggleQuickSettings, CockpitInputMapper.onButtonReleased(GamepadButton.START, 200))
        assertNull(CockpitInputMapper.onButtonReleased(GamepadButton.START, 600))
        assertNull(CockpitInputMapper.onButtonReleased(GamepadButton.A, 100))
        assertNull(CockpitInputMapper.onButtonReleased(GamepadButton.HOME, 100))
    }

    @Test
    fun `kill has no gamepad binding`() {
        val buttons = (0..GamepadButton.HOME).mapNotNull { press(it) as? CockpitAction.ActivateSkill }
        assertTrue(buttons.none { it.skillId == "kill" })
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
