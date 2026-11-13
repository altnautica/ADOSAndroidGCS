package com.altnautica.gcs.feature.cockpit.flow

import com.altnautica.gcs.core.model.LaunchSequence
import com.altnautica.gcs.core.model.LaunchStep
import com.altnautica.gcs.core.model.PilotBanner
import com.altnautica.gcs.core.model.PilotOwner
import com.altnautica.gcs.core.model.HandoverGate
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

    @Test
    fun `pilot frames that change nothing shown leave the flow state equal`() {
        val pilot = pilotState(PilotOwner.Pilot)
        // A countdown within one shown second, and a gate filling: equal state, no redraw.
        val a = FlowUi.of(pilot.copy(linkLossInS = 6.9f, rthCountdownS = 12.4f, gate = HandoverGate(true, 0.2f)), "android", streaming = true)
        val b = FlowUi.of(pilot.copy(linkLossInS = 6.05f, rthCountdownS = 12.01f, gate = HandoverGate(true, 0.6f)), "android", streaming = true)
        assertEquals(a, b)
        assertEquals(7f, a.linkLossInS)
        assertEquals(13f, a.rthCountdownS)
        assertEquals(true, a.gatePending)
        // The next shown second is a change.
        val c = FlowUi.of(pilot.copy(linkLossInS = 5.9f, rthCountdownS = 12.01f, gate = HandoverGate(true, 0.7f)), "android", streaming = true)
        assertEquals(6f, c.linkLossInS)
        // The banner's words read the same whole seconds as before.
        assertEquals(lowBatteryCountdown(12.4f), lowBatteryCountdown(a.rthCountdownS!!))
        assertEquals(false, FlowUi.of(pilot.copy(gate = HandoverGate(true, 0.2f)), "android", streaming = false).gatePending)
    }
}
