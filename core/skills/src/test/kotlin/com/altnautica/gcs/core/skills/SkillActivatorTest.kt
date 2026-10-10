package com.altnautica.gcs.core.skills

import com.altnautica.gcs.core.model.ArmRequirement
import com.altnautica.gcs.core.model.Binding
import com.altnautica.gcs.core.model.CommandResult
import com.altnautica.gcs.core.model.ConfirmGesture
import com.altnautica.gcs.core.model.ConfirmPolicy
import com.altnautica.gcs.core.model.FlightMode
import com.altnautica.gcs.core.model.Skill
import com.altnautica.gcs.core.model.SkillCategory
import com.altnautica.gcs.core.model.SkillContext
import com.altnautica.gcs.core.model.SkillSource
import com.altnautica.gcs.core.model.SkillState
import com.altnautica.gcs.core.model.VehicleCommand
import com.altnautica.gcs.core.skills.builtin.ArmSkill
import com.altnautica.gcs.core.skills.builtin.KillSkill
import com.altnautica.gcs.core.skills.builtin.PauseSkill
import com.altnautica.gcs.core.skills.builtin.RthSkill
import com.altnautica.gcs.core.skills.builtin.SkillArgs
import com.altnautica.gcs.core.skills.builtin.TakeoffSkill
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillActivatorTest {
    private var now = 10_000L
    private val activator = SkillActivator { now }
    private val vehicle = FakeVehicle()

    private fun ctx(armed: Boolean? = false, fresh: Boolean = true, checklist: Boolean = true) =
        SkillContext(vehicle, armed, null, fresh, checklist, true)

    private val calls = mutableListOf<String>()

    /** Records each gate the pipeline consults, so the order is observable. */
    private inner class Probe(
        override val id: String = "probe",
        override val toggle: Boolean = false,
        override val confirm: ConfirmPolicy? = ConfirmPolicy("t", "m", ConfirmGesture.Hold, 800, false, false),
        override val armRequirement: ArmRequirement = ArmRequirement.Any,
        private val stateValue: SkillState = SkillState(true, false, null, null, null),
        override val category: SkillCategory = SkillCategory.Flight,
    ) : Skill {
        override val labelKey = id
        override val icon = "x"
        override val source = SkillSource.Extension("p")
        override val cooldownMs = 0L
        override val defaultBinding: Binding? = null
        override fun state(ctx: SkillContext): SkillState {
            calls += "state:$id"
            return stateValue
        }
        override suspend fun activate(ctx: SkillContext, args: Map<String, Any?>): CommandResult {
            calls += "activate:$id"
            return CommandResult.Accepted
        }
        override suspend fun deactivate(ctx: SkillContext): CommandResult {
            calls += "deactivate:$id"
            return CommandResult.Accepted
        }
    }

    private val confirmAll = SkillConfirmer { calls += "confirm"; ConfirmResult.Confirmed() }
    private val cancelAll = SkillConfirmer { calls += "confirm"; ConfirmResult.Cancelled }

    @Test
    fun `presence gate rejects a missing vehicle or skill`() = runTest {
        assertEquals(ActivationOutcome.Missing, activator.activate(null, ctx(), emptyMap(), confirmAll))
        val noVehicle = SkillContext(null, null, null, false, true, null)
        assertEquals(ActivationOutcome.Missing, activator.activate(Probe(), noVehicle, emptyMap(), confirmAll))
        assertTrue(calls.isEmpty())
    }

    @Test
    fun `disabled gate reports the reason and never opens a confirm`() = runTest {
        val probe = Probe(stateValue = SkillState(false, false, "why", null, null))
        assertEquals(ActivationOutcome.Disabled("why"), activator.activate(probe, ctx(), emptyMap(), confirmAll))
        assertEquals(listOf("state:probe"), calls)
    }

    @Test
    fun `active toggle is stopped before any later gate`() = runTest {
        val probe = Probe(toggle = true, armRequirement = ArmRequirement.Armed, stateValue = SkillState(true, true, null, null, null))
        val outcome = activator.activate(probe, ctx(armed = false), emptyMap(), confirmAll)
        assertEquals(ActivationOutcome.Deactivated(CommandResult.Accepted), outcome)
        assertEquals(listOf("state:probe", "deactivate:probe"), calls)
    }

    @Test
    fun `arm requirement is checked before charges and confirm`() = runTest {
        val probe = Probe(armRequirement = ArmRequirement.Armed, stateValue = SkillState(true, false, null, null, 0))
        assertEquals(ActivationOutcome.Refused(SkillReason.NOT_ARMED), activator.activate(probe, ctx(armed = false), emptyMap(), confirmAll))
        assertEquals(ActivationOutcome.Refused(SkillReason.NO_FC_LINK), activator.activate(probe, ctx(armed = null), emptyMap(), confirmAll))
        assertFalse("confirm" in calls)
    }

    @Test
    fun `empty charge budget refuses before the confirm`() = runTest {
        val probe = Probe(stateValue = SkillState(true, false, null, null, 0))
        assertEquals(ActivationOutcome.Refused(SkillReason.NO_CHARGES), activator.activate(probe, ctx(), emptyMap(), confirmAll))
        assertFalse("confirm" in calls)
    }

    @Test
    fun `confirm runs before activate and a cancel stops the pipeline`() = runTest {
        assertEquals(ActivationOutcome.Cancelled, activator.activate(Probe(), ctx(), emptyMap(), cancelAll))
        assertEquals(listOf("state:probe", "confirm"), calls)
        calls.clear()
        val outcome = activator.activate(Probe(), ctx(), emptyMap(), confirmAll)
        assertEquals(ActivationOutcome.Completed(CommandResult.Accepted, false), outcome)
        assertEquals(listOf("state:probe", "confirm", "activate:probe"), calls)
    }

    @Test
    fun `tap tier activates without a confirm`() = runTest {
        vehicle.heartbeat(armed = true, tMs = now, mode = "AUTO")
        val outcome = activator.activate(PauseSkill, ctx(armed = true).copy(mode = vehicle.telemetry.heartbeat.value!!.mode), emptyMap(), cancelAll)
        assertTrue(outcome is ActivationOutcome.Completed)
        assertEquals(listOf(VehicleCommand.PauseMission), vehicle.control.sent)
    }

    @Test
    fun `a one-shot repeat inside 750 ms is debounced`() = runTest {
        val probe = Probe(confirm = null)
        assertTrue(activator.activate(probe, ctx(), emptyMap(), confirmAll) is ActivationOutcome.Completed)
        now += 700
        assertEquals(ActivationOutcome.Debounced, activator.activate(probe, ctx(), emptyMap(), confirmAll))
        now += 60
        assertTrue(activator.activate(probe, ctx(), emptyMap(), confirmAll) is ActivationOutcome.Completed)
    }

    @Test
    fun `a press while the confirm is open is dropped as busy`() = runTest {
        val gate = CompletableDeferred<ConfirmResult>()
        val first = async { activator.activate(Probe(), ctx(), emptyMap(), SkillConfirmer { gate.await() }) }
        runCurrent()
        assertEquals(ActivationOutcome.Busy, activator.activate(Probe(), ctx(), emptyMap(), confirmAll))
        gate.complete(ConfirmResult.Confirmed())
        assertTrue(first.await() is ActivationOutcome.Completed)
    }

    @Test
    fun `an active behaviour is pre-empted before a mode-changing skill runs`() = runTest {
        val follow = Probe(id = "follow", toggle = true, category = SkillCategory.Behavior, stateValue = SkillState(true, true, null, null, null))
        val rth = Probe(id = "rth", confirm = null)
        activator.activate(rth, ctx(), emptyMap(), confirmAll, peers = listOf(follow, rth))
        assertEquals(listOf("state:rth", "state:follow", "deactivate:follow", "activate:rth"), calls)
    }

    @Test
    fun `confirm arguments reach the skill and a takeoff enters GUIDED then arms first`() = runTest {
        vehicle.heartbeat(armed = false, tMs = now)
        val confirmer = SkillConfirmer { ConfirmResult.Confirmed(mapOf(SkillArgs.ALTITUDE_M to 25f)) }
        activator.activate(TakeoffSkill, ctx(), emptyMap(), confirmer)
        assertEquals(
            listOf(VehicleCommand.SetMode(FlightMode(4, "GUIDED")), VehicleCommand.Arm(), VehicleCommand.Takeoff(25f)),
            vehicle.control.sent,
        )
    }

    @Test
    fun `a takeoff already in GUIDED does not switch mode and stops when the switch is refused`() = runTest {
        val guided = SkillContext(vehicle, false, FlightMode(4, "GUIDED"), true, true, true)
        activator.activate(TakeoffSkill, guided, emptyMap(), confirmAll)
        assertEquals(listOf(VehicleCommand.Arm(), VehicleCommand.Takeoff(10f)), vehicle.control.sent)

        vehicle.control.sent.clear()
        vehicle.control.result = CommandResult.Rejected(4, "failed")
        now += 1_000
        val outcome = activator.activate(TakeoffSkill, ctx(), emptyMap(), confirmAll)
        assertEquals(ActivationOutcome.Completed(CommandResult.Rejected(4, "failed"), false), outcome)
        assertEquals(listOf<VehicleCommand>(VehicleCommand.SetMode(FlightMode(4, "GUIDED"))), vehicle.control.sent)
    }

    @Test
    fun `an accepted rth starts a cooldown window merged into its state`() = runTest {
        vehicle.heartbeat(armed = true, tMs = now)
        activator.activate(RthSkill, ctx(armed = true), emptyMap(), confirmAll)
        assertEquals(now + 5_000, activator.mergedState(vehicle.id, RthSkill, ctx(armed = true)).cooldownEndsAtMs)
        now += 5_001
        assertEquals(null, activator.mergedState(vehicle.id, RthSkill, ctx(armed = true)).cooldownEndsAtMs)
    }

    @Test
    fun `confirm tiers follow the cockpit standard`() {
        assertEquals(ConfirmGesture.Slide, ArmSkill.confirm!!.gesture)
        assertEquals(1_500, ArmSkill.confirm!!.holdMs)
        assertTrue(ArmSkill.confirm!!.checklistAware)
        assertEquals(ConfirmGesture.Hold, TakeoffSkill.confirm!!.gesture)
        assertEquals(800, TakeoffSkill.confirm!!.holdMs)
        assertEquals(ConfirmGesture.Guarded, KillSkill.confirm!!.gesture)
        assertEquals(1_500, KillSkill.confirm!!.holdMs)
        assertEquals(null, PauseSkill.confirm)
    }
}
