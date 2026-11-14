package com.altnautica.gcs.feature.cockpit.autonomy

import com.altnautica.gcs.feature.cockpit.testSpec
import org.junit.Assert.assertEquals
import org.junit.Test

/** The HUD tags and nav words come from the spec strings, drawn in capitals with units kept. */
class TargetTextSpecTest {
    private fun edited(key: String, value: String) =
        TargetText(testSpec.setupStrings.let { it.copy(strings = it.strings + (key to value)) })

    @Test
    fun `nav label follows the spec text`() {
        val chip = NavChip(NavLabel.ExtNav, null, NavTone.Good)
        assertEquals("EXTERNAL NAVIGATION", edited("nav_source.ext_nav", "External navigation").navLabel(chip))
    }

    @Test
    fun `predicting tag keeps its unit in lower case, and every recovery tag has spec text`() {
        val text = TargetText(testSpec.setupStrings)
        assertEquals("PREDICTING 1.4 s", text.tag(TargetTag.Predicting(1.4f)))
        assertEquals("MOVING TO LOOK", text.tag(TargetTag.Repositioning))
        assertEquals("MOVING TO LOOK · 064°", text.tag(TargetTag.RepositioningBearing(64)))
        assertEquals("WAITING FOR YOU", text.tag(TargetTag.Awaiting))
    }

    @Test
    fun `fix words come from the spec`() {
        val text = TargetText(testSpec.setupStrings)
        assertEquals(testSpec.setupStrings.format("nav_source.fix.rtk_fixed").uppercase(), text.fixWord(6))
    }
}
