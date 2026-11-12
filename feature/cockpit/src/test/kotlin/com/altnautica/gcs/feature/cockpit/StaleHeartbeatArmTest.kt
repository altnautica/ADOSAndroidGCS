package com.altnautica.gcs.feature.cockpit

import com.altnautica.gcs.core.map.GeoPoint
import com.altnautica.gcs.core.skills.SkillNotice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The last heartbeat stays in telemetry after the flight controller goes quiet. Once it is
 * stale it no longer tells the arm state: the cockpit reads that as possibly armed and in
 * the air, never as disarmed on the ground.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StaleHeartbeatArmTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun CockpitViewModel.preflightItems() =
        statusItems.value.filter { it.text?.startsWith("Pre-flight") == true || it.specKey == "alert.preflight_failing" }

    private fun TestScope.settle(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun `a fresh pilot without an arm state and a stale disarmed heartbeat show no pre-flight items`() = runTest(dispatcher) {
        val vehicle = FakeVehicle()
        vehicle.heartbeatAt(testScheduler.currentTime, armed = false)
        val agent = FakeAutonomyAgent(this, """{"owner":"idle","landed":false}""")
        val vm = testCockpitVm(vehicle, agent.apis)
        settle(600)
        assertTrue("the vehicle has failing checks to show", vm.preflight.value.failing.isNotEmpty())
        // The flight controller goes quiet; the pilot keeps answering without an arm state.
        settle(3_000)
        assertNotNull("the pilot frame is still fresh", com.altnautica.gcs.core.skills.hud.freshPilot(vm.pilotState.value, testScheduler.currentTime))
        assertTrue("an unknown arm state is not on the ground: ${vm.statusItems.value}", vm.preflightItems().isEmpty())
        assertTrue("the detector switch treats unknown as armed", vm.autonomy.layers.armed())
    }

    @Test
    fun `without a pilot a stale disarmed heartbeat reads as possibly airborne`() = runTest(dispatcher) {
        val vehicle = FakeVehicle()
        vehicle.heartbeatAt(testScheduler.currentTime, armed = false)
        val vm = testCockpitVm(vehicle)
        backgroundScope.launch { vm.launch.collect {} }
        settle(600)
        assertFalse("a fresh disarmed heartbeat is on the ground", vm.autonomy.layers.armed())
        assertTrue("a fresh disarmed heartbeat allows the route preview", vm.autonomy.layers.previewAllowed())

        settle(3_000)
        assertTrue("a stale heartbeat does not say disarmed", vm.autonomy.layers.armed())
        assertFalse("no route preview while possibly airborne", vm.autonomy.layers.previewAllowed())
        val notices = mutableListOf<SkillNotice>()
        backgroundScope.launch { vm.notices.collect { notices += it } }
        runCurrent()
        vm.pickMapPoint(GeoPoint(47.001, 8.002))
        runCurrent()
        assertNull("a map long-press does not open Launch while possibly airborne", vm.launch.value)
        val why = testSpec.setupStrings.format("refusal.landed_unknown").replaceFirstChar(Char::lowercase)
        val unknown = testSpec.setupStrings.format("refusal.fly_here_prefix", mapOf("reason" to why))
        assertEquals("the refusal says the arm state is unknown, not on the ground", listOf(unknown), notices.map { it.message })
    }

    @Test
    fun `a fresh disarmed heartbeat without a pilot opens Launch on a map long-press`() = runTest(dispatcher) {
        val vehicle = FakeVehicle()
        vehicle.heartbeatAt(testScheduler.currentTime, armed = false)
        val vm = testCockpitVm(vehicle)
        backgroundScope.launch { vm.launch.collect {} }
        settle(600)
        vm.pickMapPoint(GeoPoint(47.001, 8.002))
        runCurrent()
        assertNotNull(vm.launch.value)
    }
}
