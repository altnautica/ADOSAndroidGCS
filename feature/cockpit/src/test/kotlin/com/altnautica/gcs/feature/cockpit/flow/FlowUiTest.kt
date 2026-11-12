package com.altnautica.gcs.feature.cockpit.flow

import com.altnautica.gcs.core.model.LaunchSequence
import com.altnautica.gcs.core.model.LaunchStep
import com.altnautica.gcs.core.model.PilotBanner
import com.altnautica.gcs.core.model.PilotOwner
import com.altnautica.gcs.feature.cockpit.testSpec
import com.altnautica.gcs.feature.cockpit.pilotState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FlowUiTest {
    @Test
    fun `a running launch stays on screen until it is done`() {
        val climbing = LaunchSequence(LaunchStep.Takeoff, "hold", null)
        val pilot = pilotState(PilotOwner.Idle, armed = true, landed = false).copy(sequence = climbing)
        assertEquals(climbing, FlowUi.of(pilot, "android", streaming = false).launch)
        assertNull(FlowUi.of(pilot.copy(sequence = climbing.copy(step = LaunchStep.Done)), "android", streaming = false).launch)
        assertNull(FlowUi.of(pilot.copy(sequence = null), "android", streaming = false).launch)
    }

    @Test
    fun `the return countdown reads the shared readout, rounded up`() {
        assertEquals(5L, lowBatteryCountdown(4.2f))
        assertEquals(5L, lowBatteryCountdown(5f))
        assertEquals(1L, lowBatteryCountdown(0.1f))
        assertEquals(0L, lowBatteryCountdown(0f))
        assertEquals(0L, lowBatteryCountdown(-1f))
    }

    @Test
    fun `pilot banners name their cause, source and link-loss action in words`() {
        val banner = PilotBanner("pilot.banner.fc_mode_change", mapOf("mode" to "RTL", "cause" to "rc_or_failsafe"), null)
        assertEquals("FC switched to RTL: RC switch or failsafe", bannerLine(testSpec.setupStrings, banner))
        val unnamed = PilotBanner("pilot.banner.fc_mode_change", mapOf("mode" to "LAND", "cause" to "battery"), null)
        assertEquals("a cause the spec does not name stays as sent", "FC switched to LAND: battery", bannerLine(testSpec.setupStrings, unnamed))
        val engaged = PilotBanner("pilot.banner.ados_engaged", mapOf("source" to "rc"), null)
        assertEquals("ADOS engaged by RC switch", bannerLine(testSpec.setupStrings, engaged))
        val linkLoss = PilotBanner("pilot.banner.link_loss", mapOf("action" to "rth", "in_s" to "7"), null)
        assertEquals("Link lost: Return home in 7 s", bannerLine(testSpec.setupStrings, linkLoss))
    }
}
