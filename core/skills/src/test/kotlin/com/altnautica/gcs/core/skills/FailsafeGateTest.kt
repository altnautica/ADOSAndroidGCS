package com.altnautica.gcs.core.skills

import com.altnautica.gcs.core.model.CommandResult
import com.altnautica.gcs.core.model.FlightMode
import com.altnautica.gcs.core.model.PilotLink
import com.altnautica.gcs.core.model.PilotState
import com.altnautica.gcs.core.model.SkillContext
import com.altnautica.gcs.core.skills.builtin.BuiltinSkills
import com.altnautica.gcs.core.skills.builtin.OperatorRecovery
import com.altnautica.gcs.core.skills.builtin.SetModeSkill
import com.altnautica.gcs.core.skills.builtin.SkillArgs
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FailsafeGateTest {
    private val vehicle = FakeVehicle("failsafe-gate-${System.nanoTime()}")
    private val builtins = BuiltinSkills.from(testSpec.builtins, testSpec.ds4)
    private fun builtin(id: String) = builtins.first { it.id == id }
    private val rtl = FlightMode(6, "RTL")

    private fun ctx(
        mode: FlightMode? = rtl,
        link: PilotLink? = null,
        pilot: PilotState? = null,
        systemStatus: Int? = 4,
        armed: Boolean = true,
        fcLanded: Boolean? = null,
    ) = SkillContext(
        vehicle, armed = armed, mode = mode, linkFresh = true, checklistReady = true, autonomousNav = true,
        pilot = pilot, pilotLink = link, systemStatus = systemStatus, fcLanded = fcLanded,
    )

    @Test
    fun `pause is refused while the FC flies a return no operator commanded, with no pilot`() = runTest {
        val c = ctx()
        assertEquals(SkillReason.FAILSAFE, builtin("pause").state(c).reasonKey)
        assertEquals(CommandResult.Denied(SkillReason.FAILSAFE), builtin("pause").activate(c, emptyMap()))
        assertTrue("nothing reaches the FC", vehicle.control.sent.isEmpty())
        // Return, land and kill still act.
        for (id in listOf("rth", "land", "kill")) assertTrue(id, builtin(id).state(c).enabled)
    }

    @Test
    fun `pause is refused on the stale-pilot FC fallback too`() = runTest {
        val link = FakePilotLink(connected = true).apply { fresh = false }
        val c = ctx(link = link, pilot = adosPilot())
        assertEquals(CommandResult.Denied(SkillReason.FAILSAFE), builtin("pause").activate(c, emptyMap()))
        assertTrue(link.actions.isEmpty())
        assertTrue(vehicle.control.sent.isEmpty())
    }

    @Test
    fun `heartbeat critical or emergency is a failsafe whatever the mode`() {
        val loiter = FlightMode(5, "LOITER")
        assertFalse(builtin("pause").state(ctx(mode = loiter, systemStatus = 6)).enabled)
        assertFalse(builtin("pause").state(ctx(mode = loiter, systemStatus = 5)).enabled)
        assertTrue(builtin("pause").state(ctx(mode = loiter, systemStatus = 4)).enabled)
    }

    @Test
    fun `a return the operator commanded here can be paused`() = runTest {
        builtin("rth").activate(ctx(mode = FlightMode(5, "LOITER")), emptyMap())
        OperatorRecovery.observeMode(vehicle.id, "RTL", 0L)
        assertTrue(builtin("pause").state(ctx()).enabled)
        // Once the vehicle leaves the return, a later RTL is the FC's own again.
        OperatorRecovery.observeMode(vehicle.id, "LOITER", 0L)
        OperatorRecovery.observeMode(vehicle.id, "RTL", 0L)
        assertFalse(builtin("pause").state(ctx()).enabled)
    }

    @Test
    fun `a return picked on the Mode sheet counts as the operator's`() = runTest {
        vehicle.control.availableModes.value = vehicle.control.availableModes.value + rtl
        SetModeSkill.activate(ctx(mode = FlightMode(5, "LOITER")), mapOf(SkillArgs.TARGET_MODE to "RTL"))
        OperatorRecovery.observeMode(vehicle.id, "RTL", 0L)
        assertTrue(builtin("pause").state(ctx()).enabled)
    }

    @Test
    fun `disarm needs landed known from the pilot or the FC`() {
        val loiter = FlightMode(5, "LOITER")
        val disarm = builtin("disarm")
        assertEquals(SkillReason.LANDED_UNKNOWN, disarm.state(ctx(mode = loiter)).reasonKey)
        assertEquals(SkillReason.NOT_LANDED, disarm.state(ctx(mode = loiter, fcLanded = false)).reasonKey)
        assertTrue(disarm.state(ctx(mode = loiter, fcLanded = true)).enabled)
        assertEquals(SkillReason.NOT_LANDED, disarm.state(ctx(mode = loiter, pilot = adosPilot().copy(landed = false), link = FakePilotLink())).reasonKey)
        assertEquals(SkillReason.ALREADY_DISARMED, disarm.state(ctx(mode = loiter, armed = false)).reasonKey)
    }

    @Test
    fun `disarm skips the landed check on firmware with no landed state`() {
        val disarm = builtin("disarm")
        vehicle.telemetry.reportsLandedState = false
        try {
            assertTrue(disarm.state(ctx(mode = FlightMode(5, "LOITER"))).enabled)
        } finally {
            vehicle.telemetry.reportsLandedState = true
        }
    }

    @Test
    fun `a refused return is not noted as the operator's`() = runTest {
        vehicle.control.result = CommandResult.Rejected(4, "denied")
        builtin("rth").activate(ctx(mode = FlightMode(5, "LOITER")), emptyMap())
        OperatorRecovery.observeMode(vehicle.id, "RTL", 0L)
        assertFalse(builtin("pause").state(ctx()).enabled)
    }

    @Test
    fun `a note expires after the entry grace on the session clock`() = runTest {
        builtin("rth").activate(ctx(mode = FlightMode(5, "LOITER")), emptyMap())
        OperatorRecovery.observeMode(vehicle.id, "LOITER", 1_000L)
        OperatorRecovery.observeMode(vehicle.id, "LOITER", 1_000L + OperatorRecovery.ENTRY_GRACE_MS)
        assertTrue(OperatorRecovery.commanded(vehicle.id))
        OperatorRecovery.observeMode(vehicle.id, "LOITER", 1_001L + OperatorRecovery.ENTRY_GRACE_MS)
        OperatorRecovery.observeMode(vehicle.id, "RTL", 1_002L + OperatorRecovery.ENTRY_GRACE_MS)
        assertFalse(builtin("pause").state(ctx()).enabled)
    }

    @Test
    fun `a landing after an operator return is the FC's own`() = runTest {
        builtin("rth").activate(ctx(mode = FlightMode(5, "LOITER")), emptyMap())
        OperatorRecovery.observeMode(vehicle.id, "RTL", 0L)
        assertTrue(builtin("pause").state(ctx()).enabled)
        OperatorRecovery.observeMode(vehicle.id, "LAND", 0L)
        assertFalse(builtin("pause").state(ctx(mode = FlightMode(9, "LAND"))).enabled)
    }
}
