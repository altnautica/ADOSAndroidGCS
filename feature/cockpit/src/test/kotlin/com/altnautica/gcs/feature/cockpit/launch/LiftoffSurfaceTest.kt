package com.altnautica.gcs.feature.cockpit.launch

import com.altnautica.gcs.core.model.LaunchSequence
import com.altnautica.gcs.core.model.LaunchStep
import com.altnautica.gcs.core.model.Liftoff
import com.altnautica.gcs.core.model.LiftoffPhase
import com.altnautica.gcs.core.model.PilotAction
import com.altnautica.gcs.core.model.ReadinessItem
import com.altnautica.gcs.core.model.ReadinessSeverity
import com.altnautica.gcs.feature.cockpit.preflight.ReadinessFixes
import com.altnautica.gcs.feature.cockpit.testSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiftoffSurfaceTest {
    private fun seq(phase: LiftoffPhase, text: String?, detail: String? = null, error: String? = null) =
        LaunchSequence(LaunchStep.Takeoff, "hold", error, Liftoff(phase, text, "learned", 0.5f, detail))

    @Test
    fun `the running take-off shows its status line and detail`() {
        assertEquals("Spinning up. Motors at idle, checking they're steady.", liftoffLine(seq(LiftoffPhase.Spool, "Spinning up", "Motors at idle, checking they're steady")))
        assertEquals("Lifting off.", liftoffLine(seq(LiftoffPhase.Commit, "Lifting off")))
        assertEquals("Airborne. Climbing to 3 m.", liftoffLine(seq(LiftoffPhase.Done, "Airborne", "Climbing to 3 m")))
        assertNull(liftoffLine(LaunchSequence(LaunchStep.Takeoff, "hold", null)))
        assertNull(liftoffStopText(seq(LiftoffPhase.Commit, "Lifting off")))
    }

    @Test
    fun `a stopped take-off shows the pilot's stop words in place of the refusal code`() {
        val stopped = seq(LiftoffPhase.Stopped, "Take-off stopped", "The drone started to tip. Motors off.", error = "liftoff_tipping")
        assertNull(liftoffLine(stopped))
        assertEquals("Take-off stopped. The drone started to tip. Motors off.", liftoffStopText(stopped))
    }

    @Test
    fun `the readiness rows carry their names`() {
        val strings = testSpec.setupStrings
        assertEquals("Balance check", strings.format("readiness.item.airframe_balance"))
        assertEquals("Steady take-off", strings.format("readiness.item.supervised_liftoff"))
    }

    @Test
    fun `a take-off stopped before lift-off reads the spec text, not the code`() {
        val strings = testSpec.setupStrings
        assertEquals(
            "Take-off stopped. The flight controller did not take the steady take-off settings (GUID_OPTIONS, GCS_PID_MASK).",
            com.altnautica.gcs.feature.cockpit.skillbar.reasonTextOf(strings, "liftoff_fc_params"),
        )
    }

    @Test
    fun `no rate data reads the agent's current words`() {
        val strings = testSpec.setupStrings
        val words = "Take-off stopped. The flight controller sends no rate data to balance the drone with. Motors off."
        assertEquals(words, strings.format("refusal.liftoff_no_rate_data"))
        assertEquals(words, strings.format("setup.blocker.liftoff_no_rate_data"))
    }

    @Test
    fun `a relayed pilot refusal reads its refusal text`() {
        val strings = testSpec.setupStrings
        val notice = com.altnautica.gcs.core.skills.SkillNotice(
            "takeoff",
            com.altnautica.gcs.core.skills.NoticeLevel.Error,
            com.altnautica.gcs.core.skills.SkillReason.COMMAND_REJECTED,
            com.altnautica.gcs.core.agent.telemetry.TelemetrySnapshotSource.rejectReason("pilot refused launch: not_ready:airframe_balance"),
        )
        assertEquals("Not ready: Balance check", com.altnautica.gcs.feature.cockpit.skillbar.noticeTextOf(strings, notice))
    }

    @Test
    fun `I rebalanced it shows on a failing balance check while disarmed and sends airframe_reset`() {
        val warn = ReadinessItem("airframe_balance", ok = false, severity = ReadinessSeverity.Warn, msg = "Balance: nose-heavy. Off by about 35 mm. Move the battery back.")
        assertTrue(ReadinessFixes.resetsAirframe(warn, armed = false))
        assertFalse(ReadinessFixes.resetsAirframe(warn, armed = true))
        assertFalse(ReadinessFixes.resetsAirframe(warn, armed = null))
        assertFalse(ReadinessFixes.resetsAirframe(warn.copy(ok = true), armed = false))
        assertFalse(ReadinessFixes.resetsAirframe(warn.copy(id = "supervised_liftoff"), armed = false))
        assertEquals(PilotAction("airframe_reset"), ReadinessFixes.AIRFRAME_RESET)
        val strings = testSpec.setupStrings
        assertEquals(ReadinessFixes.AIRFRAME_LABEL_KEY, ReadinessFixes.labelKey(warn, armed = false, strings))
        assertNull(ReadinessFixes.labelKey(warn, armed = true, strings))
        assertEquals("I rebalanced it", strings.format(ReadinessFixes.AIRFRAME_LABEL_KEY))
    }
}
