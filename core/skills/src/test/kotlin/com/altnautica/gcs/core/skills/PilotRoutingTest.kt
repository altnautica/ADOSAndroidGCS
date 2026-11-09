package com.altnautica.gcs.core.skills

import com.altnautica.gcs.core.model.ArmRequirement
import com.altnautica.gcs.core.model.Binding
import com.altnautica.gcs.core.model.CommandResult
import com.altnautica.gcs.core.model.ConfirmPolicy
import com.altnautica.gcs.core.model.ControlMode
import com.altnautica.gcs.core.model.PilotAction
import com.altnautica.gcs.core.model.PilotActionResult
import com.altnautica.gcs.core.model.PilotInput
import com.altnautica.gcs.core.model.PilotLink
import com.altnautica.gcs.core.model.PilotOwner
import com.altnautica.gcs.core.model.PilotProfile
import com.altnautica.gcs.core.model.PilotState
import com.altnautica.gcs.core.model.PilotVehicleClass
import com.altnautica.gcs.core.model.Skill
import com.altnautica.gcs.core.model.SkillCategory
import com.altnautica.gcs.core.model.SkillContext
import com.altnautica.gcs.core.model.SkillSource
import com.altnautica.gcs.core.model.SkillState
import com.altnautica.gcs.core.model.VehicleCommand
import com.altnautica.gcs.core.skills.builtin.BuiltinSkills
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A pilot link whose connection and answers the test sets. */
class FakePilotLink(connected: Boolean = true, var answer: PilotActionResult = PilotActionResult.Accepted) : PilotLink {
    override val src = "android"
    override val connected = MutableStateFlow(connected)
    override val state = MutableStateFlow<PilotState?>(null)
    val actions = mutableListOf<PilotAction>()

    override fun input(frame: PilotInput) = Unit
    override fun release() = Unit
    override suspend fun action(action: PilotAction): PilotActionResult {
        if (!connected.value) return PilotActionResult.Unavailable
        actions += action
        return answer
    }
    override fun takeSession() = connected.value
}

/** A pilot state in ADOS Pilot control with the fields routing reads. */
fun adosPilot(armed: Boolean = true, vehicleClass: PilotVehicleClass? = null) = PilotState(
    owner = PilotOwner.Idle, ownerLabel = null, skill = null, controlMode = ControlMode.Ados, profile = PilotProfile.Normal,
    avoid = null, fcMode = null, fcModeName = "GUIDED", armed = armed, landed = !armed, inputSrc = null, inputAgeMs = null,
    holdReason = null, avoidState = null, minClearanceM = null, goalNed = null, planNed = emptyList(), setpointNed = null,
    readiness = emptyList(), linkLossInS = null, mission = null, vehicleClass = vehicleClass, inputSession = null, gate = null,
    sequence = null, banner = null, rthCountdownS = null, receivedAtMs = 0L,
)

class PilotRoutingTest {
    private val vehicle = FakeVehicle()
    private val builtins = BuiltinSkills.from(testSpec.builtins, testSpec.ds4)
    private fun builtin(id: String) = builtins.first { it.id == id }

    private fun ctx(link: PilotLink, fcFresh: Boolean = true, pilot: PilotState = adosPilot()) =
        SkillContext(vehicle, armed = true, mode = null, linkFresh = fcFresh, checklistReady = true, autonomousNav = true, pilot = pilot, pilotLink = link)

    @Test
    fun `with the pilot channel down, pause, return, land and kill go to the flight controller while its link is live`() = runTest {
        val link = FakePilotLink(connected = false)
        val c = ctx(link)
        for (id in listOf("pause", "rth", "land", "kill")) {
            assertTrue("$id stays enabled", builtin(id).state(c).enabled)
            assertEquals(id, CommandResult.Accepted, builtin(id).activate(c, emptyMap()))
        }
        assertTrue("nothing is written into the dead channel", link.actions.isEmpty())
        val sent = vehicle.control.sent
        assertEquals(VehicleCommand.ReturnToLaunch, sent[1])
        assertEquals(VehicleCommand.Land, sent[2])
        assertEquals(VehicleCommand.Kill, sent[3])
        assertEquals(4, sent.size)
    }

    @Test
    fun `with the pilot channel up, the same actions go to the pilot`() = runTest {
        val link = FakePilotLink(connected = true)
        val c = ctx(link)
        for (id in listOf("pause", "rth", "land", "kill")) builtin(id).activate(c, emptyMap())
        assertEquals(listOf("pause", "rth", "land", "kill"), link.actions.map { it.name })
        assertTrue(vehicle.control.sent.isEmpty())
    }

    @Test
    fun `only the recovery actions fall back - arm stays with the pilot`() {
        val c = ctx(FakePilotLink(connected = false), pilot = adosPilot(armed = false))
        val arm = builtin("arm").state(c.copy(armed = false))
        assertEquals(SkillReason.NO_FC_LINK, arm.reasonKey)
    }

    @Test
    fun `return and land need a live link on one path or the other`() {
        val c = ctx(FakePilotLink(connected = false), fcFresh = false)
        assertEquals(SkillReason.NO_FC_LINK, builtin("rth").state(c).reasonKey)
        assertEquals(SkillReason.NO_FC_LINK, builtin("land").state(c).reasonKey)
    }

    @Test
    fun `a refusal from the pilot is reported with its reason, a missing answer as a timeout`() = runTest {
        val link = FakePilotLink(answer = PilotActionResult.Refused("readiness red"))
        assertEquals(CommandResult.Rejected(-1, "readiness red"), builtin("takeoff").activate(ctx(link, pilot = adosPilot(armed = false)), emptyMap()))
        link.answer = PilotActionResult.NoAnswer
        assertEquals(CommandResult.Timeout, builtin("rth").activate(ctx(link), emptyMap()))
        link.answer = PilotActionResult.Accepted
        assertEquals(CommandResult.Accepted, builtin("rth").activate(ctx(link), emptyMap()))
    }

    @Test
    fun `a refused action starts no cooldown`() = runTest {
        val activator = SkillActivator { 10_000L }
        val link = FakePilotLink(answer = PilotActionResult.Refused("no"))
        val outcome = activator.activate(builtin("rth"), ctx(link), emptyMap(), SkillConfirmer { ConfirmResult.Confirmed() })
        assertEquals(ActivationOutcome.Completed(CommandResult.Rejected(-1, "no"), false), outcome)
        assertNull(activator.cooldownEndsAt(vehicle.id, "rth"))
    }

    private class MultirotorOnly : Skill {
        var fired = false
        override val id = "follow-me"
        override val labelKey = "Follow me"
        override val icon = "x"
        override val toggle = false
        override val category = SkillCategory.Navigation
        override val armRequirement = ArmRequirement.Any
        override val confirm: ConfirmPolicy? = null
        override val cooldownMs = 0L
        override val defaultBinding: Binding? = null
        override val source = SkillSource.Extension("follow")
        override val vehicleClasses = setOf(PilotVehicleClass.Multirotor)
        override fun state(ctx: SkillContext) = SkillState(true, false, null, null, null)
        override suspend fun activate(ctx: SkillContext, args: Map<String, Any?>): CommandResult {
            fired = true
            return CommandResult.Accepted
        }
    }

    @Test
    fun `a skill that excludes the vehicle class cannot fire from any path`() = runTest {
        val activator = SkillActivator { 10_000L }
        val skill = MultirotorOnly()
        val plane = ctx(FakePilotLink(), pilot = adosPilot(vehicleClass = PilotVehicleClass.FixedWing))
        assertEquals(SkillReason.VEHICLE_CLASS, activator.mergedState(vehicle.id, skill, plane).reasonKey)
        val outcome = activator.activate(skill, plane, emptyMap(), SkillConfirmer { ConfirmResult.Confirmed() })
        assertEquals(ActivationOutcome.Disabled(SkillReason.VEHICLE_CLASS), outcome)
        assertTrue(!skill.fired)

        val copter = ctx(FakePilotLink(), pilot = adosPilot(vehicleClass = PilotVehicleClass.Multirotor))
        activator.activate(skill, copter, emptyMap(), SkillConfirmer { ConfirmResult.Confirmed() })
        assertTrue(skill.fired)
    }
}
