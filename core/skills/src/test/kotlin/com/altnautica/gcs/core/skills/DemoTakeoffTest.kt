package com.altnautica.gcs.core.skills

import com.altnautica.gcs.core.link.command.CommandService
import com.altnautica.gcs.core.link.mavlink.ElapsedClock
import com.altnautica.gcs.core.link.mavlink.TransportMavlinkLink
import com.altnautica.gcs.core.link.transport.DemoTransport
import com.altnautica.gcs.core.link.vehicle.MavlinkVehicleTelemetry
import com.altnautica.gcs.core.model.CommandResult
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.Reach
import com.altnautica.gcs.core.model.Vehicle
import com.altnautica.gcs.core.model.VehicleCommand
import com.altnautica.gcs.core.skills.builtin.SkillArgs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Take off from disarmed through the whole pipeline: registry, activator and the
 * MAVLink [CommandService] talking to the simulated ArduCopter of the demo node.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DemoTakeoffTest {

    private class DemoVehicle(scope: CoroutineScope, clock: ElapsedClock) : Vehicle {
        override val id = DeviceId("demo-copter")
        override val reach = MutableStateFlow<Reach>(Reach.DirectFc)
        val link = TransportMavlinkLink(scope, clock)
        override val telemetry = MavlinkVehicleTelemetry(scope, link, clock)
        override val control = CommandService(scope, link, telemetry.heartbeat, telemetry.home)
    }

    /** What the hold sheet returns when the operator holds Take off with the stepper at 10 m. */
    private val holdAtTenMetres = SkillConfirmer { request ->
        assertEquals("takeoff", request.skill.id)
        ConfirmResult.Confirmed(mapOf(SkillArgs.ALTITUDE_M to 10f))
    }

    private suspend fun TestScope.demoSession(): Pair<DemoVehicle, SkillSession> {
        val vehicle = DemoVehicle(backgroundScope, ElapsedClock { testScheduler.currentTime })
        vehicle.link.attach(DemoTransport(backgroundScope.coroutineContext))
        vehicle.telemetry.heartbeat.filterNotNull().first()
        vehicle.control.availableModes.first { it.isNotEmpty() }
        val clock = MonotonicClock { testScheduler.currentTime }
        val session = SkillSession(
            MutableStateFlow(vehicle),
            MutableStateFlow(true),
            SkillRegistry(emptySet()),
            SkillActivator(clock),
            clock,
            backgroundScope,
        )
        runCurrent()
        return vehicle to session
    }

    private suspend fun TestScope.takeOffAndClimb(vehicle: DemoVehicle, session: SkillSession) {
        assertFalse(vehicle.telemetry.heartbeat.value!!.armed)
        assertTrue(session.states.value.getValue("takeoff").enabled)
        val outcome = session.activate("takeoff", emptyMap(), holdAtTenMetres)
        assertEquals(ActivationOutcome.Completed(CommandResult.Accepted, checklistOverridden = false), outcome)
        advanceTimeBy(12_000)
        val hb = vehicle.telemetry.heartbeat.value!!
        assertTrue(hb.armed)
        assertEquals("GUIDED", hb.mode.name)
        assertEquals(10f, vehicle.telemetry.position.value!!.altRelM, 0.5f)
    }

    @Test
    fun `take off from disarmed arms, enters GUIDED and climbs to the stepper altitude`() = runTest {
        val (vehicle, session) = demoSession()
        assertEquals("STABILIZE", vehicle.telemetry.heartbeat.value!!.mode.name)
        takeOffAndClimb(vehicle, session)
    }

    @Test
    fun `take off again after a landing that left the copter in LAND`() = runTest {
        val (vehicle, session) = demoSession()
        takeOffAndClimb(vehicle, session)

        assertEquals(CommandResult.Accepted, vehicle.control.run(VehicleCommand.Land))
        advanceTimeBy(20_000)
        val landed = vehicle.telemetry.heartbeat.value!!
        assertFalse(landed.armed)
        // ArduCopter refuses to arm in LAND; the takeoff must leave it first.
        assertEquals("LAND", landed.mode.name)

        takeOffAndClimb(vehicle, session)
    }
}
