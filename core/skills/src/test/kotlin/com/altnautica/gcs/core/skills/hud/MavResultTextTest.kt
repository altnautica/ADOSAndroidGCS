package com.altnautica.gcs.core.skills.hud

import com.altnautica.gcs.core.skills.testSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** A flight controller's MAV_RESULT name never reaches the operator raw. */
class MavResultTextTest {
    private val text = SpecText(testSpec.setupStrings)

    @Test
    fun `every MAV_RESULT name reads as the spec sentence`() {
        for (name in listOf("ACCEPTED", "TEMPORARILY_REJECTED", "DENIED", "UNSUPPORTED", "FAILED", "IN_PROGRESS", "CANCELLED")) {
            val sentence = text.mavResultText(name)
            assertEquals(text.text("mav_result.$name"), sentence)
            assertNotEquals(name, sentence)
        }
    }

    @Test
    fun `other text and null pass through`() {
        assertEquals("the tracker refused the target", text.mavResultText("the tracker refused the target"))
        assertEquals(null, text.mavResultText(null))
    }

    @Test
    fun `a sequence error carrying a MAV_RESULT name reads through the refusal lookup`() {
        assertEquals(text.text("mav_result.DENIED"), text.refusalText("DENIED"))
    }
}
