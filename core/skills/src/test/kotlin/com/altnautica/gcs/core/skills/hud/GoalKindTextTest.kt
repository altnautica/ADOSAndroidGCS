package com.altnautica.gcs.core.skills.hud

import com.altnautica.gcs.core.model.FollowView
import com.altnautica.gcs.core.model.GoalKind
import com.altnautica.gcs.core.skills.adosPilot
import com.altnautica.gcs.core.skills.testSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The goal chip: the skill's goal in the spec's words, with the viewpoint the pilot picked for an intent. */
class GoalKindTextTest {
    private val text = GoalKindText(SpecText(testSpec.setupStrings))

    @Test
    fun `a follow intent shows the viewpoint the pilot picked`() {
        val intent = adosPilot().copy(goalKind = GoalKind.FollowIntent, view = FollowView(15.4f, 7.6f, 4.5f, 0.9f))
        assertEquals("Follow intent · view 15° 8 m", text.chip(intent))
        assertEquals("Follow intent · view 0° 6 m", text.chip(intent.copy(view = FollowView(359.7f, 6f, 4f, 1f))))
        assertEquals("Follow intent", text.chip(intent.copy(view = null)))
    }

    @Test
    fun `every other kind shows its label`() {
        val cases = mapOf(
            GoalKind.Follow to "Ring",
            GoalKind.Orbit to "Orbit",
            GoalKind.Position to "Position",
            GoalKind.Vantage to "Vantage",
        )
        for ((kind, want) in cases) assertEquals(want, text.chip(adosPilot().copy(goalKind = kind)))
    }

    @Test
    fun `nothing shows while no skill goal flies`() {
        assertNull(text.chip(adosPilot()))
        assertNull(text.chip(null))
    }
}
