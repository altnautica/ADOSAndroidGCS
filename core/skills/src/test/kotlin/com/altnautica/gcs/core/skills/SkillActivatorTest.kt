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
import com.altnautica.gcs.core.skills.builtin.BuiltinSkills
import com.altnautica.gcs.core.skills.builtin.SkillArgs
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

    private val builtins = BuiltinSkills.from(testSpec.builtins, testSpec.ds4)
    private fun builtin(id: String) = builtins.first { it.id == id }
    private val pause get() = builtin("pause")
    private val takeoff get() = builtin("takeoff")
    private val rth get() = builtin("rth")

    /** Records each gate the pipeline consults, so the order is observable. A deactivate clears its active flag. */
    private inner class Probe(
        override val id: String = "probe",
        override val toggle: Boolean = false,
        override val confirm: ConfirmPolicy? = ConfirmPolicy("t", "m", ConfirmGesture.Hold, 800, false, false),
        override val armRequirement: ArmRequirement = ArmRequirement.Any,
        private var stateValue: SkillState = SkillState(true, false, null, null, null),
        override val category: SkillCategory = SkillCategory.Flight,
        override val motion: Boolean = false,
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
            stateValue = stateValue.copy(active = false)
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
    fun `tap tier pause activates without a confirm and brakes into the hold mode`() = runTest {
        vehicle.heartbeat(armed = true, tMs = now, mode = "GUIDED")
        val outcome = activator.activate(pause, ctx(armed = true).copy(mode = vehicle.telemetry.heartbeat.value!!.mode), emptyMap(), cancelAll)
        assertTrue(outcome is ActivationOutcome.Completed)
        assertEquals(listOf<VehicleCommand>(VehicleCommand.SetMode(FlightMode(5, "LOITER"))), vehicle.control.sent)
        assertFalse("confirm" in calls)
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
    fun `pause stops an active motion skill and leaves navigation and camera skills running`() = runTest {
        vehicle.heartbeat(armed = true, tMs = now)
        val active = SkillState(true, true, null, null, null)
        val follow = Probe(id = "follow", toggle = true, category = SkillCategory.Behavior, stateValue = active, motion = true)
        val visionNav = Probe(id = "vision-nav", toggle = true, category = SkillCategory.Navigation, stateValue = active)
        val tracker = Probe(id = "tracker", toggle = true, category = SkillCategory.Camera, stateValue = active)
        activator.activate(pause, ctx(armed = true), emptyMap(), confirmAll, peers = listOf(visionNav, follow, tracker, pause))
        // Only motion peers are consulted: the estimator and the camera skill are never read or stopped.
        assertEquals(listOf("state:follow", "deactivate:follow"), calls)
    }

    @Test
    fun `rth and land also stop motion skills only`() = runTest {
        vehicle.heartbeat(armed = true, tMs = now)
        val follow = Probe(id = "follow", toggle = true, stateValue = SkillState(true, true, null, null, null), motion = true)
        val visionNav = Probe(id = "vision-nav", toggle = true, stateValue = SkillState(true, true, null, null, null))
        for (id in listOf("rth", "land")) {
            calls.clear()
            now += 1_000
            activator.activate(builtin(id), ctx(armed = true), emptyMap(), confirmAll, peers = listOf(visionNav, follow))
            assertFalse(id, "deactivate:vision-nav" in calls)
        }
        assertFalse(follow.state(ctx()).active)
    }

    @Test
    fun `a second motion skill stops the first and waits for its echo before starting`() = runTest {
        val first = Probe(id = "follow", toggle = true, confirm = null, stateValue = SkillState(true, true, null, null, null), motion = true)
        val second = Probe(id = "orbit", toggle = true, confirm = null, motion = true)
        val outcome = activator.activate(second, ctx(), emptyMap(), confirmAll, peers = listOf(first, second))
        assertTrue(outcome is ActivationOutcome.Completed)
        assertEquals(listOf("state:orbit", "state:follow", "deactivate:follow", "state:follow", "activate:orbit"), calls)
    }

    @Test
    fun `a non-motion toggle never stops the active motion skill`() = runTest {
        val follow = Probe(id = "follow", toggle = true, confirm = null, stateValue = SkillState(true, true, null, null, null), motion = true)
        val recorder = Probe(id = "recorder", toggle = true, confirm = null, category = SkillCategory.Camera)
        activator.activate(recorder, ctx(), emptyMap(), confirmAll, peers = listOf(follow, recorder))
        assertFalse("deactivate:follow" in calls)
    }

    @Test
    fun `confirm arguments reach the skill and a takeoff enters GUIDED then arms first`() = runTest {
        vehicle.heartbeat(armed = false, tMs = now)
        val confirmer = SkillConfirmer { ConfirmResult.Confirmed(mapOf(SkillArgs.ALTITUDE_M to 25f)) }
        activator.activate(takeoff, ctx(), emptyMap(), confirmer)
        assertEquals(
            listOf(VehicleCommand.SetMode(FlightMode(4, "GUIDED")), VehicleCommand.Arm(), VehicleCommand.Takeoff(25f)),
            vehicle.control.sent,
        )
    }

    @Test
    fun `a takeoff already in GUIDED does not switch mode and stops when the switch is refused`() = runTest {
        val guided = SkillContext(vehicle, false, FlightMode(4, "GUIDED"), true, true, true)
        activator.activate(takeoff, guided, emptyMap(), confirmAll)
        assertEquals(listOf(VehicleCommand.Arm(), VehicleCommand.Takeoff(com.altnautica.gcs.core.skills.builtin.TakeoffAltitude.DEFAULT_M)), vehicle.control.sent)

        vehicle.control.sent.clear()
        vehicle.control.result = CommandResult.Rejected(4, "failed")
        now += 1_000
        val outcome = activator.activate(takeoff, ctx(), emptyMap(), confirmAll)
        assertEquals(ActivationOutcome.Completed(CommandResult.Rejected(4, "failed"), false), outcome)
        assertEquals(listOf<VehicleCommand>(VehicleCommand.SetMode(FlightMode(4, "GUIDED"))), vehicle.control.sent)
    }

    @Test
    fun `pause is never dropped as busy or debounced`() = runTest {
        vehicle.heartbeat(armed = true, tMs = now)
        assertTrue(activator.activate(pause, ctx(armed = true), emptyMap(), confirmAll) is ActivationOutcome.Completed)
        now += 100
        assertTrue(activator.activate(pause, ctx(armed = true), emptyMap(), confirmAll) is ActivationOutcome.Completed)
    }

    @Test
    fun `confirm tiers come from the builtins table`() {
        assertEquals(ConfirmGesture.Hold, builtin("arm").confirm!!.gesture)
        assertEquals(800, builtin("arm").confirm!!.holdMs)
        assertTrue(builtin("arm").confirm!!.checklistAware)
        assertEquals(ConfirmGesture.Hold, takeoff.confirm!!.gesture)
        assertEquals(1_500, takeoff.confirm!!.holdMs)
        assertEquals(1_500, rth.confirm!!.holdMs)
        assertEquals(ConfirmGesture.Guarded, builtin("kill").confirm!!.gesture)
        assertEquals(3_000, builtin("kill").confirm!!.holdMs)
        assertEquals(null, pause.confirm)
        assertEquals(null, builtin("resume").confirm)
        assertFalse(builtins.any { it.id == "abort" })
    }
}
