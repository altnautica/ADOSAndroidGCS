package com.altnautica.gcs.feature.cockpit.launch

import com.altnautica.gcs.core.model.MissionState
import com.altnautica.gcs.core.model.Skill
import com.altnautica.gcs.feature.cockpit.testSpec
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Launch sheet lists every after-takeoff choice in the spec order; one not available now says why. */
class LaunchAfterChoicesTest {
    private fun ui(skills: List<Skill> = emptyList(), mission: MissionState? = null, point: NedPoint? = null) =
        LaunchUi(LaunchDraft(altM = 5f), skills, mission, point, vehicleClass = null, sequence = null)

    @Test
    fun `with nothing loaded all four choices show, three of them with their reason`() {
        val choices = launchAfterChoices(ui())
        assertEquals(listOf(LaunchAfter.Hold, LaunchAfter.Skill, LaunchAfter.Mission, LaunchAfter.Goto), choices.map { it.after })
        assertEquals(
            listOf(null, "flow.launch.after.skill_none", "flow.launch.after.mission_none", "flow.launch.pick_point_hint"),
            choices.map { it.reasonKey },
        )
        val strings = testSpec.setupStrings.strings
        for (key in choices.mapNotNull { it.reasonKey }) assertTrue(key, strings.containsKey(key))
    }

    @Test
    fun `a behaviour skill, a loaded mission and a picked point make every choice available`() {
        val choices = launchAfterChoices(ui(listOf(mockk()), MissionState.Loaded, NedPoint(10f, 0f, -5f)))
        assertEquals(LaunchAfter.entries.toList(), choices.map { it.after })
        assertTrue(choices.all { it.reasonKey == null })
    }

    @Test
    fun `a mission that is not loaded keeps its choice in the row, dimmed with its reason`() {
        val mission = launchAfterChoices(ui(mission = MissionState.Active)).single { it.after == LaunchAfter.Mission }
        assertEquals("flow.launch.after.mission_none", mission.reasonKey)
    }
}
