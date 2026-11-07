package com.altnautica.gcs.core.skills.spec

import com.altnautica.gcs.core.skills.binding.Ds4Bindings
import com.altnautica.gcs.core.skills.controls.Gesture
import com.altnautica.gcs.core.skills.testSpec
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The spec copy the app ships parses whole and carries the built-in and controller tables. */
class CockpitSpecTest {
    private val dir = File("../../feature/cockpit/src/main/assets/cockpit-spec")

    @Test
    fun `every shipped spec file parses`() {
        for (file in CockpitSpecFile.entries) {
            CockpitSpecParser.parse(file, File(dir, file.fileName).readText())
        }
    }

    @Test
    fun `the built-in table is exactly the cockpit standard`() {
        val ids = testSpec.builtins.builtins.map { it.id }
        assertEquals(listOf("arm", "disarm", "takeoff", "land", "rth", "pause", "resume", "take_control", "kill"), ids)
        val rows = testSpec.builtins.builtins.associateBy { it.id }
        assertEquals("shift+x", rows.getValue("kill").keyboard)
        assertEquals("space", rows.getValue("pause").keyboard)
    }

    @Test
    fun `the shipped controller map holds every locked binding and the PS press brake`() {
        val ds4 = testSpec.ds4
        assertTrue(Ds4Bindings.missingLocked(ds4, ds4.bindings).isEmpty())
        val ps = ds4.bindings.filter { it.control == "ps" && it.with.isEmpty() }
        assertTrue(ps.any { it.gesture == Gesture.PRESS && it.action == "pause" && it.locked })
        assertTrue(ps.any { it.gesture == Gesture.HOLD && it.holdMs == 2000 && it.action == "rth" })
        // Dropping the pause row leaves a locked entry unmet.
        val withoutPause = ds4.bindings.filterNot { it.action == "pause" && it.locked }
        assertFalse(Ds4Bindings.missingLocked(ds4, withoutPause).isEmpty())
    }
}
