package com.altnautica.gcs.feature.node.common

import com.altnautica.gcs.feature.node.R
import com.altnautica.gcs.feature.node.agent.carriesLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Agent enums reach the operator as words; an unknown value falls back to the wire text. */
class NodeLabelsTest {

    @Test
    fun agentEnumsHaveWords() {
        assertEquals(R.string.node_diag_mis_keyed, linkDiagnosisLabelRes("mis_keyed"))
        assertEquals(R.string.node_diag_deaf, linkDiagnosisLabelRes("deaf"))
        assertEquals(R.string.node_state_stale, linkStateLabelRes("stale"))
        assertEquals(R.string.node_state_connected, linkStateLabelRes("connected"))
        assertEquals(R.string.node_uplink_wifi, uplinkLabelRes("wlan0_client"))
        assertEquals(R.string.node_uplink_eth, uplinkLabelRes("eth0"))
        assertEquals(R.string.node_uplink_cellular, uplinkLabelRes("wwan0"))
        assertEquals(R.string.node_service_failed, serviceStateLabelRes("failed"))
        assertNull(linkDiagnosisLabelRes("something_new"))
    }

    @Test
    fun thermalZoneKeysReadAsWords() {
        assertEquals("CPU", sensorName("cpu_thermal"))
        assertEquals("SoC", sensorName("soc-thermal"))
        assertEquals("Big core0", sensorName("bigcore0_thermal".replace("bigcore0", "big_core0")))
        assertEquals("thermal", sensorName("thermal"))
    }

    @Test
    fun linkCarryingUnitsAreNamed() {
        assertTrue(carriesLink("ados-mavlink"))
        assertTrue(carriesLink("ados-wfb-rx.service"))
        assertTrue(carriesLink("ados-video"))
        assertFalse(carriesLink("ados-logd"))
    }
}
