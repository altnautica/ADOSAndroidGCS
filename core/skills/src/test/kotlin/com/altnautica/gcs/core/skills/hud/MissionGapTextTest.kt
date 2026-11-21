package com.altnautica.gcs.core.skills.hud

import com.altnautica.gcs.core.skills.testSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Coverage gap reasons and rows in the spec's words. */
class MissionGapTextTest {
    private val text = MissionGapText(SpecText(testSpec.setupStrings))

    @Test
    fun `every pilot gap reason has its own words`() {
        assertEquals("Inside an obstacle", text.reason("unreachable_inside_obstacle"))
        assertEquals("No way there within 200 m", text.reason("unreachable_no_path"))
        assertEquals("No progress before the countdown", text.reason("stall_timeout"))
        assertEquals("Skipped by the operator", text.reason("stall_operator"))
    }

    @Test
    fun `an unworded reason reads as its code with spaces, none as null`() {
        assertEquals("some new reason", text.reason("some_new_reason"))
        assertNull(text.reason(null))
        assertNull(text.reason(""))
    }

    @Test
    fun `a gap row names the waypoint and why`() {
        assertEquals("WP 4 · Skipped · No way there within 200 m", text.line("4", "unreachable_no_path"))
        assertEquals("WP 4 · Skipped", text.line("4", null))
    }
}
