package com.altnautica.gcs.feature.settings

import com.altnautica.gcs.core.demo.DemoScenario
import org.junit.Assert.assertEquals
import org.junit.Test

class DemoScenarioLabelTest {
    @Test
    fun `scenario names read as sentences`() {
        assertEquals("Mission paused", demoScenarioLabel(DemoScenario.MissionPaused))
        assertEquals("Fc failsafe rtl", demoScenarioLabel(DemoScenario.FcFailsafeRtl))
        assertEquals("Follow", demoScenarioLabel(DemoScenario.Follow))
    }
}
