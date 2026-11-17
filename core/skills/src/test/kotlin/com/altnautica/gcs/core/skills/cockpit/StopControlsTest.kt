package com.altnautica.gcs.core.skills.cockpit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The link notification's stop controls run in the newest open cockpit, and only while one is open. */
class StopControlsTest {
    private val none = object : NotificationControls {
        override fun reachable() = false
        override fun lockScreenHidden() = false
    }

    @Test
    fun `controls run in the newest open cockpit and are offered only while one is open`() {
        val stops = StopControls(none)
        assertFalse(stops.available.value)
        assertFalse(stops.run("pause"))

        val first = mutableListOf<String>()
        val second = mutableListOf<String>()
        val a = stops.register { first += it }
        val b = stops.register { second += it }
        assertTrue(stops.available.value)
        assertTrue(stops.run("pause"))
        assertEquals(listOf("pause"), second)

        b.close()
        assertTrue(stops.run("land"))
        assertEquals(listOf("land"), first)

        a.close()
        assertFalse(stops.available.value)
        assertFalse(stops.run("rth"))
    }
}
