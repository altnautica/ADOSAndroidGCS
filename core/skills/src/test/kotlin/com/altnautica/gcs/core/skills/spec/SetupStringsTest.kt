package com.altnautica.gcs.core.skills.spec

import org.junit.Assert.assertEquals
import org.junit.Test

class SetupStringsTest {
    private val strings = SetupStringsSpec(
        v = 1,
        locale = "en",
        strings = mapOf(
            "setup.compass.body" to "Rotate the aircraft {n} times.",
            "setup.compass.body.large" to "Walk the large aircraft round.",
        ),
    )

    @Test
    fun `a body variant the strings lack falls back to the step's body`() {
        assertEquals("Rotate the aircraft 3 times.", strings.format("setup.compass.body.tailsitter", mapOf("n" to "3")))
    }

    @Test
    fun `a body variant the strings carry wins`() {
        assertEquals("Walk the large aircraft round.", strings.format("setup.compass.body.large"))
    }

    @Test
    fun `any other missing key reads as the key itself`() {
        assertEquals("setup.compass.title", strings.format("setup.compass.title"))
        assertEquals("setup.gps.body.x", strings.format("setup.gps.body.x"))
    }
}
