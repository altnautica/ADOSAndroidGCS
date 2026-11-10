package com.altnautica.gcs.feature.cockpit.skillbar

import com.altnautica.gcs.core.skills.SkillReason
import com.altnautica.gcs.feature.cockpit.testSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Skill refusal reasons read through the spec's refusal texts. */
class ReasonTextSpecTest {
    private val strings = testSpec.setupStrings

    @Test
    fun `a known reason reads its spec text`() {
        assertEquals(strings.format("refusal.no_fc_link"), reasonKeyText(strings, SkillReason.NO_FC_LINK))
    }

    @Test
    fun `a fly here refusal reads the spec prefix and reason`() {
        assertEquals(
            strings.format("refusal.fly_here_prefix", mapOf("reason" to strings.format("refusal.no_home").replaceFirstChar(Char::lowercase))),
            reasonKeyText(strings, SkillReason.GOTO_NO_HOME),
        )
    }

    @Test
    fun `extension text has no spec key`() {
        assertNull(reasonKeyText(strings, "my extension says no"))
    }
}
