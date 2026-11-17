package com.altnautica.gcs.feature.cockpit.alerts

import com.altnautica.gcs.core.model.AvoidState
import com.altnautica.gcs.core.model.ControlMode
import com.altnautica.gcs.core.model.InputSession
import com.altnautica.gcs.core.model.PilotOwner
import com.altnautica.gcs.core.model.PilotProfile
import com.altnautica.gcs.core.model.PilotSkillRef
import com.altnautica.gcs.core.model.PilotState
import com.altnautica.gcs.core.model.ReadinessItem
import com.altnautica.gcs.core.model.ReadinessSeverity
import com.altnautica.gcs.core.model.SpeedCap
import com.altnautica.gcs.feature.cockpit.pilotState
import com.altnautica.gcs.feature.cockpit.testSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The spec's `feedback` table over pilot-state changes: one rumble per update, the strongest class. */
class PilotFeedbackRulesTest {
    private val base = pilotState(PilotOwner.Pilot, armed = true, landed = false)

    /** The rumble pattern the change from [from] to [to] plays. */
    private fun rumble(from: PilotState, to: PilotState): String? {
        val rules = PilotFeedbackRules(testSpec.ds4.feedback)
        assertNull("the first state earns nothing", rules.next(from))
        return rules.next(to)
    }

    @Test
    fun `a profile, owner, skill, control mode or stick session change rumbles a mode change`() {
        assertEquals("mode_change", rumble(base, base.copy(profile = PilotProfile.Sport)))
        assertEquals("mode_change", rumble(base, base.copy(owner = PilotOwner.Goto)))
        assertEquals("mode_change", rumble(base, base.copy(controlMode = ControlMode.FcManual)))
        val skill = base.copy(owner = PilotOwner.Skill, skill = PilotSkillRef("p", "orbit", "Orbit"))
        assertEquals("mode_change", rumble(skill, skill.copy(skill = PilotSkillRef("p", "follow", "Follow"))))
        val stick = base.copy(inputSession = InputSession("android", "Phone", 4L))
        assertEquals("mode_change", rumble(stick, stick.copy(inputSession = InputSession("mc", "MC", 5L))))
        assertEquals("mode_change", rumble(stick, stick.copy(inputSession = InputSession("android", "Phone", 5L))))
        assertNull("a session ending is no takeover", rumble(stick, stick.copy(inputSession = null)))
        assertNull("the own sticks resuming are no takeover", rumble(base, stick))
        assertNull("a new label alone is no takeover", rumble(stick, stick.copy(inputSession = InputSession("android", "Pixel", 4L))))
    }

    @Test
    fun `avoiding, a new hold reason, a speed cap or a readiness warning rumbles a warning`() {
        assertEquals("warning", rumble(base, base.copy(avoidState = AvoidState.Detour)))
        assertEquals("warning", rumble(base.copy(avoidState = AvoidState.Detour), base.copy(avoidState = AvoidState.Blocked)))
        assertNull(rumble(base.copy(avoidState = AvoidState.Blocked), base.copy(avoidState = AvoidState.Blocked)))
        assertEquals("warning", rumble(base, base.copy(holdReason = "obstacle")))
        assertEquals("warning", rumble(base, base.copy(speedCap = SpeedCap("gnss-denied", 2f))))
        assertNull("the same cap again is not new", rumble(base.copy(speedCap = SpeedCap("gnss-denied", 2f)), base.copy(speedCap = SpeedCap("gnss-denied", 1f))))
        val warn = ReadinessItem("gps", ok = false, severity = ReadinessSeverity.Warn, msg = "GPS weak")
        assertEquals("warning", rumble(base, base.copy(readiness = listOf(warn))))
        assertNull(rumble(base.copy(readiness = listOf(warn)), base.copy(readiness = listOf(warn.copy(msg = "GPS weaker")))))
    }

    @Test
    fun `controller lost, link loss, failsafe, low battery or a readiness block rumbles critical`() {
        assertEquals("critical", rumble(base, base.copy(holdReason = "controller-lost")))
        assertEquals("critical", rumble(base, base.copy(linkLossInS = 5f)))
        assertEquals("critical", rumble(base, base.copy(fcFailsafe = true)))
        assertEquals("critical", rumble(base, base.copy(rthCountdownS = 10f)))
        val block = ReadinessItem("ekf", ok = false, severity = ReadinessSeverity.Block, msg = "EKF")
        assertEquals("critical", rumble(base, base.copy(readiness = listOf(block))))
        val ground = base.copy(armed = false, landed = true)
        assertNull("a block on the ground is not critical", rumble(ground, ground.copy(readiness = listOf(block))))
    }

    @Test
    fun `only the strongest class plays when several fire at once`() {
        assertEquals("critical", rumble(base, base.copy(profile = PilotProfile.Cine, holdReason = "controller-lost")))
        assertEquals("warning", rumble(base, base.copy(owner = PilotOwner.Goto, avoidState = AvoidState.Blocked)))
    }

    @Test
    fun `losing the state rumbles critical once, and the state after it earns nothing`() {
        val rules = PilotFeedbackRules(testSpec.ds4.feedback)
        assertNull(rules.next(null))
        assertNull(rules.next(base))
        assertEquals("critical", rules.next(null))
        assertNull(rules.next(null))
        assertNull(rules.next(base.copy(profile = PilotProfile.Sport)))
        assertNull("an unchanged state is quiet", rules.next(base.copy(profile = PilotProfile.Sport, receivedAtMs = 5L)))
    }

    @Test
    fun `every spec rule reads a pilot field this app carries`() {
        val f = testSpec.ds4.feedback
        val full = base.copy(
            profile = PilotProfile.Sport,
            controlMode = ControlMode.Ados,
            skill = PilotSkillRef("p", "s", "S"),
            inputSession = InputSession("android", "Phone", 1L),
            avoidState = AvoidState.Blocked,
            holdReason = "x",
            speedCap = SpeedCap("r", 1f),
            linkLossInS = 1f,
            rthCountdownS = 1f,
            fcFailsafe = true,
        )
        for (rule in f.order.flatMap(f::rules)) {
            val field = rule.field ?: continue
            assertEquals(rule.id, true, pilotField(full, field) != kotlinx.serialization.json.JsonNull || field == "readiness")
        }
    }
}
