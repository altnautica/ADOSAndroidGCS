package com.altnautica.gcs.core.skills

import com.altnautica.gcs.core.model.CommandResult
import com.altnautica.gcs.core.model.ControlMode
import com.altnautica.gcs.core.model.FlightMode
import com.altnautica.gcs.core.model.PilotAction
import com.altnautica.gcs.core.model.PilotLink
import com.altnautica.gcs.core.model.PilotState
import com.altnautica.gcs.core.model.PositionFix
import com.altnautica.gcs.core.model.SkillContext
import com.altnautica.gcs.core.skills.builtin.BuiltinSkills
import com.altnautica.gcs.core.skills.builtin.FlyHereSkill
import com.altnautica.gcs.core.skills.hud.SpecText
import com.altnautica.gcs.core.skills.hud.knowsRefusal
import com.altnautica.gcs.core.skills.hud.refusalText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pause, Disarm and Fly Here read a fresh pilot's own state before the flight controller's guess. */
class PilotFailsafeGateTest {
    private val vehicle = FakeVehicle("pilot-failsafe-gate")
    private val builtins = BuiltinSkills.from(testSpec.builtins, testSpec.ds4)
    private fun builtin(id: String) = builtins.first { it.id == id }
    private val rtl = FlightMode(6, "RTL")
    private val loiter = FlightMode(5, "LOITER")

    private fun ctx(
        mode: FlightMode? = rtl,
        link: PilotLink? = null,
        pilot: PilotState? = null,
        armed: Boolean? = true,
        fcLanded: Boolean? = null,
    ) = SkillContext(
        vehicle, armed = armed, mode = mode, linkFresh = true, checklistReady = true, autonomousNav = true,
        pilot = pilot, pilotLink = link, systemStatus = 4, fcLanded = fcLanded,
    )

    @Test
    fun `a fresh pilot without a failsafe lets pause brake a return commanded elsewhere`() = runTest {
        val link = FakePilotLink(connected = true)
        val c = ctx(link = link, pilot = adosPilot().copy(fcFailsafe = false, landed = false))
        assertTrue(builtin("pause").state(c).enabled)
        assertEquals(CommandResult.Accepted, builtin("pause").activate(c, emptyMap()))
        assertEquals(1, link.actions.size)
    }

    @Test
    fun `a fresh pilot reporting a failsafe refuses pause on the fc route`() = runTest {
        val link = FakePilotLink(connected = true)
        val pilot = adosPilot().copy(controlMode = ControlMode.FcManual, fcFailsafe = true, landed = false)
        val c = ctx(mode = loiter, link = link, pilot = pilot)
        assertEquals(SkillReason.FAILSAFE, builtin("pause").state(c).reasonKey)
        assertEquals(CommandResult.Denied(SkillReason.FAILSAFE), builtin("pause").activate(c, emptyMap()))
        assertTrue(vehicle.control.sent.isEmpty())
        assertTrue(link.actions.isEmpty())
    }

    @Test
    fun `a stale pilot's landed does not allow disarm`() {
        val link = FakePilotLink(connected = true).apply { fresh = false }
        val c = ctx(mode = loiter, link = link, pilot = adosPilot().copy(landed = true))
        assertEquals(SkillReason.LANDED_UNKNOWN, builtin("disarm").state(c).reasonKey)
    }

    @Test
    fun `the FC's own landed state allows disarm`() {
        assertTrue(builtin("disarm").state(ctx(mode = loiter, fcLanded = true)).enabled)
        assertEquals(SkillReason.LANDED_UNKNOWN, builtin("disarm").state(ctx(mode = loiter, fcLanded = null)).reasonKey)
        assertEquals(SkillReason.NOT_LANDED, builtin("disarm").state(ctx(mode = loiter, fcLanded = false)).reasonKey)
    }

    @Test
    fun `a fresh pilot with no FC link leaves landed to the FC's own state`() {
        val unknown = adosPilot().copy(armed = null, landed = null)
        val link = FakePilotLink(connected = true)
        assertEquals(SkillReason.LANDED_UNKNOWN, builtin("disarm").state(ctx(mode = loiter, link = link, pilot = unknown)).reasonKey)
        assertTrue(builtin("disarm").state(ctx(mode = loiter, link = link, pilot = unknown, fcLanded = true)).enabled)
    }

    @Test
    fun `an armed take-off with the landed state unknown reads landed unknown`() {
        assertEquals(SkillReason.LANDED_UNKNOWN, builtin("takeoff").state(ctx(mode = loiter, fcLanded = null)).reasonKey)
        assertEquals(SkillReason.ALREADY_ARMED, builtin("takeoff").state(ctx(mode = loiter, fcLanded = false)).reasonKey)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `hovering low near home is not landed without the FC's landed state`() = runTest {
        advanceTimeBy(10_000)
        val v = FakeVehicle("low-hover")
        v.heartbeat(armed = true, tMs = testScheduler.currentTime, mode = "LOITER")
        v.telemetry.position.value = PositionFix(0.0, 0.0, 100f, 0.5f, 0f, 0f, 0f, testScheduler.currentTime)
        val clock = MonotonicClock { testScheduler.currentTime }
        val s = SkillSession(
            vehicle = MutableStateFlow(v), checklistReady = MutableStateFlow(true), pilot = MutableStateFlow(null),
            pilotLink = MutableStateFlow(null), registry = SkillRegistry(emptySet(), testSpec),
            activator = SkillActivator(clock), clock = clock, scope = backgroundScope,
        )
        runCurrent()
        assertNull(s.context.value.fcLanded)
        assertEquals(SkillReason.LANDED_UNKNOWN, s.states.value.getValue("disarm").reasonKey)
    }

    @Test
    fun `fly here during a pilot-reported failsafe refuses with failsafe`() {
        val pilot = adosPilot().copy(surfaceOps = setOf(PilotAction.GOTO), fcFailsafe = true, landed = false)
        val c = ctx(link = FakePilotLink(connected = true), pilot = pilot)
        assertEquals(SkillReason.GOTO_FAILSAFE, FlyHereSkill.state(c).reasonKey)
    }

    @Test
    fun `fly here on the ground in GUIDED is refused as landed`() {
        val c = ctx(mode = FlightMode(4, "GUIDED"), fcLanded = true)
        assertFalse(FlyHereSkill.state(c).enabled)
        assertEquals(SkillReason.GOTO_LANDED, FlyHereSkill.state(c).reasonKey)
    }

    @Test
    fun `not landed and nothing paused read as spec refusal text`() {
        val text = SpecText(testSpec.setupStrings)
        for (code in listOf(SkillReason.NOT_LANDED, SkillReason.LANDED_UNKNOWN, SkillReason.NO_PAUSED_MISSION)) {
            assertTrue(code, text.knowsRefusal(code))
        }
        assertEquals(testSpec.setupStrings.strings.getValue("refusal.not_landed"), text.refusalText(SkillReason.NOT_LANDED))
        assertEquals(testSpec.setupStrings.strings.getValue("refusal.landed_unknown"), text.refusalText(SkillReason.LANDED_UNKNOWN))
        assertEquals(testSpec.setupStrings.strings.getValue("refusal.nothing_paused"), text.refusalText(SkillReason.NO_PAUSED_MISSION))
    }

    @Test
    fun `failsafe modes are the shared return and landing names`() {
        val returns = setOf("RTL", "SMART_RTL", "QRTL", "AUTO_RTL", "AUTO.RTL")
        val lands = setOf("LAND", "QLAND", "LOITER_ALT_QLAND", "AUTO.LAND", "AUTO.PRECLAND")
        // Plus PRECLAND, this app's name for PX4's precision landing.
        assertEquals(returns + lands + "PRECLAND", com.altnautica.gcs.core.skills.builtin.FAILSAFE_MODES)
    }
}
