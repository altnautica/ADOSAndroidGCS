package com.altnautica.gcs.feature.cockpit.skillbar

import com.altnautica.gcs.core.skills.NoticeLevel
import com.altnautica.gcs.core.skills.SkillNotice
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
    fun `a local Fly Here refusal reads with the Fly Here prefix, like the pilot's`() {
        assertEquals("Fly Here refused: the flight controller has control", reasonTextOf(strings, SkillReason.GOTO_CONTROL_MODE))
        assertEquals("Fly Here refused: on the ground", reasonTextOf(strings, SkillReason.GOTO_LANDED))
    }

    @Test
    fun `a pilot refusal with its own spec text shows alone, without the generic command rejected`() {
        val refused = SkillNotice("fly-here", NoticeLevel.Error, SkillReason.COMMAND_REJECTED, SkillReason.GOTO_BAD_ARGS)
        assertEquals(reasonKeyText(strings, SkillReason.GOTO_BAD_ARGS), noticeTextOf(strings, refused))
        assertEquals(false, noticeTextOf(strings, refused).contains(strings.format("refusal.command_rejected")))
        val coded = SkillNotice("pause", NoticeLevel.Error, SkillReason.COMMAND_REJECTED, "failsafe")
        assertEquals(strings.format("refusal.failsafe"), noticeTextOf(strings, coded))
    }

    @Test
    fun `a detail without spec text keeps the generic reason in front, and no detail reads the reason alone`() {
        val verbatim = SkillNotice("ext", NoticeLevel.Error, SkillReason.COMMAND_REJECTED, "gimbal busy")
        assertEquals(strings.format("refusal.command_rejected") + " · gimbal busy", noticeTextOf(strings, verbatim))
        val bare = SkillNotice("rth", NoticeLevel.Error, SkillReason.COMMAND_TIMEOUT, null)
        assertEquals(strings.format("refusal.no_response"), noticeTextOf(strings, bare))
    }

    @Test
    fun `arm state and landed state refusals read their spec text`() {
        assertEquals(strings.format("refusal.already_armed"), reasonTextOf(strings, SkillReason.ALREADY_ARMED))
        assertEquals(strings.format("refusal.already_disarmed"), reasonTextOf(strings, SkillReason.ALREADY_DISARMED))
        assertEquals(strings.format("refusal.landed_unknown"), reasonTextOf(strings, SkillReason.LANDED_UNKNOWN))
    }

    @Test
    fun `an unsupported or failed command does not blame the flight controller`() {
        assertEquals(strings.format("refusal.unknown_action"), reasonKeyText(strings, SkillReason.COMMAND_UNSUPPORTED))
        assertEquals(strings.format("mav_result.FAILED"), reasonKeyText(strings, SkillReason.COMMAND_FAILED))
        assertEquals("Failed", reasonKeyText(strings, SkillReason.COMMAND_FAILED))
    }

    @Test
    fun `extension text has no spec key`() {
        assertNull(reasonKeyText(strings, "my extension says no"))
    }
}
