package com.altnautica.gcs.feature.cockpit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The link popover, the link cell's text and the Menu's rows, read from the spec strings. */
class LinkPopoverTest {
    private val strings = testSpec.setupStrings
    private val order = testSpec.layout.menu!!.order

    @Test
    fun `the link popover lists the age, then RSSI and link quality only when the radio reports them`() {
        assertEquals(listOf("Link: 0.4 s", "RSSI -71 dBm", "LQ 92%"), linkRows(strings, "0.4 s", -71, 92, reducedRate = false))
        assertEquals(listOf("Link: 0.4 s", "LQ 92%"), linkRows(strings, "0.4 s", null, 92, reducedRate = false))
        assertEquals(listOf("Link: 0.4 s"), linkRows(strings, "0.4 s", null, null, reducedRate = false))
    }

    @Test
    fun `the radio line joins RSSI and link quality, or names the one it has`() {
        assertEquals("-71 dBm · 92%", radioText(strings, -71, 92))
        assertEquals("-71 dBm", radioText(strings, -71, null))
        assertEquals("LQ 92%", radioText(strings, null, 92))
        assertNull(radioText(strings, null, null))
    }

    @Test
    fun `the reduced update rate is named on the link cell, in the popover and on the Menu's link row`() {
        val notice = strings.format("nav.reduced_rate")
        assertEquals("Reduced update rate (radio)", notice)

        val cell = linkCellText(strings, "0.4 s", reducedRate = true)
        assertEquals("0.4 s · $notice", cell)
        assertEquals("0.4 s", linkCellText(strings, "0.4 s", reducedRate = false))

        assertEquals(notice, linkRows(strings, "0.4 s", -71, 92, reducedRate = true).last())

        assertEquals("Link: 0.4 s · $notice · -71 dBm · 92%", menuLinkRow(strings, cell, -71, 92))
        assertEquals("Link: 0.4 s", menuLinkRow(strings, linkCellText(strings, "0.4 s", reducedRate = false), null, null))
    }

    @Test
    fun `the Menu follows the spec order and drops the entries that do not apply now`() {
        assertEquals(
            listOf("overflow", "view", "cameras", "layers", "preflight", "modes", "controls", "extension_panels", "setup", "settings", "exit"),
            order,
        )
        assertEquals(order, menuEntries(order, liveStreams = 2, preflightTotal = 8, setupNeeded = true))
        assertEquals(
            listOf("overflow", "view", "layers", "modes", "controls", "extension_panels", "settings", "exit"),
            menuEntries(order, liveStreams = 1, preflightTotal = 0, setupNeeded = false),
        )
    }

    @Test
    fun `an entry the Menu does not know is left out`() {
        assertEquals(listOf("view"), menuEntries(listOf("view", "teleport"), liveStreams = 0, preflightTotal = 0, setupNeeded = false))
    }

    @Test
    fun `the Menu falls back to the spec's own order`() {
        assertEquals(order, DEFAULT_MENU_ORDER)
    }
}
