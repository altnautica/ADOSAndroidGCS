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
    fun `the tips file names a text key for every tip and keeps STOP, the left column, the chip, the alerts and the top bar clear`() {
        val tips = testSpec.tips
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7, 8), tips.tips.map { it.order }.sorted())
        for (t in tips.tips) assertTrue(t.id, testSpec.setupStrings.strings.containsKey("tips.${t.id}"))
        assertEquals(listOf("stop", "left_column", "flight_state_chip", "alert_stack", "top_bar"), tips.rules.neverCoverZones)
        assertEquals(false, tips.rules.showOnlyWhen["armed"])
        assertEquals(false, tips.rules.showOnlyWhen["alert_banner_visible"])
        assertTrue(testSpec.setupStrings.strings.containsKey("tips.reset"))
    }

    @Test
    fun `the shared layout values parse`() {
        val layout = testSpec.layout
        assertEquals(3000, layout.holdsMs["stop"])
        assertEquals(120f, layout.launch!!.altMaxM)
        assertEquals("exit", layout.menu!!.order.last())
        assertEquals(listOf("gimbal_pad", "zoom"), layout.zones.first { it.id == "right_column" }.dropOrder)
        assertEquals("controller", layout.zones.first { it.id == "top_bar" }.overflow!!.toMenuOrder.first())
        assertEquals(90, layout.statusPill!!.storageWarnPct)
        assertEquals(20, layout.battery!!.lowFallbackPct)
        val split = testSpec.views.views.first { it.id == "split" }.divider!!
        assertEquals(0.3f, split.min)
        assertEquals("bottom_left_card", testSpec.views.views.first { it.id == "map" }.videoSlot)
        val top = layout.zones.first { it.id == "top_bar" }
        assertEquals(0.4f, top.chip!!.maxFrac)
        assertEquals(3000L, top.link!!.iconColor.okBelowMs)
        assertEquals(10000L, top.link!!.iconColor.warningBelowMs)
        assertEquals("cockpit.link.age", top.link!!.text)
        assertEquals("battery.to_reserve_short", top.battery!!.timeToReserve!!.short)
        assertEquals("#FF8A80", testSpec.tokens.chrome.textCritical)
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
