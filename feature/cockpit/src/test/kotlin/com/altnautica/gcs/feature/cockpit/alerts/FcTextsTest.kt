package com.altnautica.gcs.feature.cockpit.alerts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FcTextsTest {
    @Test
    fun `a text heard on the link stays a link text when the pilot relays it later`() {
        val t = FcTexts()
        t.onStatusText(2, "GCS Failsafe", 1_000, relayed = false)
        t.onStatusText(2, "GCS Failsafe", 9_000, relayed = true)
        assertEquals(9_000L, t.critical.value!!.atMs)
        assertFalse(t.critical.value!!.relayed)
        t.onStatusText(4, "EKF3 lane switch 1", 1_000, relayed = false)
        t.onStatusText(4, "EKF3 lane switch 1", 9_000, relayed = true)
        assertFalse(t.warnings.value.single().relayed)
    }

    @Test
    fun `a text only the pilot relayed is marked relayed, and a failsafe announcement is held as critical`() {
        val t = FcTexts()
        t.onStatusText(4, "Battery failsafe", 1_000, relayed = true)
        assertTrue(t.critical.value!!.relayed)
        assertEquals(AlertEngine.MAV_SEVERITY_CRITICAL, t.critical.value!!.severity)
        t.onStatusText(3, "EKF variance", 1_000, relayed = true)
        assertTrue(t.warnings.value.single().relayed)
        assertEquals(3, t.warnings.value.single().severity)
    }
}
