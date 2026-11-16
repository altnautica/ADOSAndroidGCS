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
        assertEquals("Lifting off.", liftoffLine(seq(LiftoffPhase.Lift, "Lifting off")))
        assertNull(liftoffLine(seq(LiftoffPhase.Done, "Airborne", "Climbing to 3 m")))
        assertNull(liftoffLine(LaunchSequence(LaunchStep.Takeoff, "hold", null)))
        assertNull(liftoffStopText(seq(LiftoffPhase.Lift, "Lifting off")))
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
