// SPDX-License-Identifier: GPL-2.0-or-later
package com.altnautica.gcs.radio.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioDonglesTest {

    @Test
    fun `supported radios are the RTL8812EU and RTL8812AU ids`() {
        assertTrue(RadioDongles.isSupported(0x0BDA, 0xA81A))
        assertTrue(RadioDongles.isSupported(0x0BDA, 0x8812))
        assertTrue(RadioDongles.isSupported(0x0BDA, 0x881A))
        assertTrue(RadioDongles.isSupported(0x0BDA, 0x881B))
        assertEquals(0xA81A, RadioDongles.SUPPORTED_PRODUCT_IDS.first())
    }

    @Test
    fun `RTL8812BU lookalikes are refused and flagged`() {
        for (pid in listOf(0xB812, 0xB832)) {
            assertFalse(RadioDongles.isSupported(0x0BDA, pid))
            assertTrue(RadioDongles.isUnsupportedLookalike(0x0BDA, pid))
        }
    }

    @Test
    fun `other vendors never match`() {
        assertFalse(RadioDongles.isSupported(0x148F, 0xA81A))
        assertFalse(RadioDongles.isUnsupportedLookalike(0x148F, 0xB812))
    }

    @Test
    fun `device filter resource lists exactly the supported ids`() {
        // Unit tests run with the module directory as the working directory.
        val xml = java.io.File("src/main/res/xml/radio_device_filter.xml").readText()
        val ids = Regex("""vendor-id="(\d+)" product-id="(\d+)"""").findAll(xml).map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }.toList()
        assertEquals(RadioDongles.SUPPORTED_PRODUCT_IDS.map { RadioDongles.REALTEK_VENDOR_ID to it }.toSet(), ids.toSet())
    }
}
