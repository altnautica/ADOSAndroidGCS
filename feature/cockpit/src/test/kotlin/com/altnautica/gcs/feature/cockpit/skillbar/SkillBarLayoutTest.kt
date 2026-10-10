package com.altnautica.gcs.feature.cockpit.skillbar

import org.junit.Assert.assertEquals
import org.junit.Test

class SkillBarLayoutTest {

    @Test
    fun `a portrait phone keeps the drawer button on screen`() {
        // 411 dp wide, 64 dp slots, 8 dp gaps: five cells fit, one of them is the drawer.
        assertEquals(4, fittingSlotCount(availableDp = 411f, slotDp = 64f, gapDp = 8f, cap = visibleSlotCount(WidthClass.Compact)))
    }

    @Test
    fun `a wide pane is capped by the width class`() {
        assertEquals(6, fittingSlotCount(availableDp = 2_000f, slotDp = 64f, gapDp = 8f, cap = visibleSlotCount(WidthClass.Compact)))
    }

    @Test
    fun `a pane narrower than one slot still shows only the drawer`() {
        assertEquals(0, fittingSlotCount(availableDp = 40f, slotDp = 64f, gapDp = 8f, cap = 6))
    }
}
