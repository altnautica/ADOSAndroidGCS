package com.altnautica.gcs.feature.node.overview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FcPortLabelTest {
    @Test
    fun `serial port shows its baud`() {
        assertEquals("/dev/ttyAMA0 @ 921600", fcPortLabel("/dev/ttyAMA0", 921600))
    }

    @Test
    fun `network port reported with baud 0 shows no baud`() {
        assertEquals("tcp:10.0.0.2:5760", fcPortLabel("tcp:10.0.0.2:5760", 0))
    }

    @Test
    fun `missing baud shows the port and missing port shows nothing`() {
        assertEquals("udp:14550", fcPortLabel("udp:14550", null))
        assertNull(fcPortLabel(null, 57600))
    }
}
