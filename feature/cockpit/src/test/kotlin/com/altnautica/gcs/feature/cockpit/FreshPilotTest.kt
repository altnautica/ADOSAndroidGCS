package com.altnautica.gcs.feature.cockpit

import com.altnautica.gcs.core.model.PilotOwner
import com.altnautica.gcs.core.skills.hud.freshPilot
import com.altnautica.gcs.feature.cockpit.pilot.ControllerContext
import com.altnautica.gcs.feature.cockpit.pilot.LightbarState
import com.altnautica.gcs.feature.cockpit.pilot.lightbarState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Only a fresh pilot frame decides armed and landed; an old one falls back to the flight controller's heartbeat. */
@OptIn(ExperimentalCoroutinesApi::class)
class FreshPilotTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `an old armed pilot frame gives way to the disarmed heartbeat and the pre-flight items return`() = runTest(dispatcher) {
        val vehicle = FakeVehicle()
        val agent = FakeAutonomyAgent(this, """{"owner":"pilot","armed":true,"landed":false}""")
        val vm = testCockpitVm(vehicle, agent.apis)
        fun beatFor(ms: Long) {
            var left = ms
            while (left > 0) {
                vehicle.heartbeatAt(testScheduler.currentTime, armed = false)
                advanceTimeBy(250)
                runCurrent()
                left -= 250
            }
        }
        fun preflightItems() = vm.statusItems.value.filter { it.text?.startsWith("Pre-flight") == true || it.specKey == "alert.preflight_failing" }
        beatFor(600)
        assertTrue("a fresh armed pilot hides the pre-flight items: ${vm.statusItems.value}", preflightItems().isEmpty())
        assertTrue("the vehicle has failing checks to show", vm.preflight.value.failing.isNotEmpty())
        // The pilot stalls: its channel reconnects at the stale age but no frame comes.
        agent.pilotAnswers = false
        beatFor(2_500)
        assertTrue("an old pilot frame no longer reads armed: ${vm.statusItems.value}", preflightItems().isNotEmpty())
    }

    @Test
    fun `the controller context takes armed and landed from the heartbeat once the pilot frame is old`() {
        val pilot = pilotState(PilotOwner.Pilot, armed = true, landed = false, receivedAtMs = 1_000L)
        val fresh = ControllerContext.of(freshPilot(pilot, 2_000L), armed = false, modeName = null, pickerOpen = false, lowBattery = false, failsafe = false)
        assertEquals(true, fresh.armed)
        assertEquals(false, fresh.landed)
        val old = ControllerContext.of(freshPilot(pilot, 3_000L), armed = false, modeName = null, pickerOpen = false, lowBattery = false, failsafe = false)
        assertNull(old.pilot)
        assertEquals(false, old.armed)
        assertEquals(true, old.landed)
        assertEquals(LightbarState.Disarmed, lightbarState(old))
    }
}
