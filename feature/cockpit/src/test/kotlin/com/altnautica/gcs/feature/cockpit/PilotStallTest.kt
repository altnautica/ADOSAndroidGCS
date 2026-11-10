package com.altnautica.gcs.feature.cockpit

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A pilot that stops sending frames leaves no frozen countdown, banner or handover on screen. */
@OptIn(ExperimentalCoroutinesApi::class)
class PilotStallTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a stalled pilot hides the countdowns and reads as not fresh`() = runTest(dispatcher) {
        val vehicle = FakeVehicle()
        vehicle.heartbeatAt(testScheduler.currentTime, armed = true)
        // One frame, then silence: the socket stays open but the pilot has stalled.
        val data = """{"owner":"ados","armed":true,"landed":false,"rth_countdown_s":8.0,"link_loss_in_s":4.0,""" +
            """"input_session":{"src":"web","owner":"web"}}"""
        val vm = testCockpitVm(vehicle, FakeAutonomyAgent(this, data).apis)
        backgroundScope.launch { vm.flow.collect {} }
        backgroundScope.launch { vm.pilotFresh.collect {} }
        advanceTimeBy(600)
        runCurrent()

        assertTrue("a frame just arrived", vm.pilotFresh.value)
        assertNotNull("the countdown shows while the pilot is live", vm.flow.value.rthCountdownS)
        assertNotNull("the link-loss countdown shows while the pilot is live", vm.flow.value.linkLossInS)

        vehicle.heartbeatAt(testScheduler.currentTime + 3_000, armed = true)
        advanceTimeBy(3_000)
        runCurrent()

        assertFalse("no frame for 3 s", vm.pilotFresh.value)
        assertEquals("nothing derived from the stalled frame stays", com.altnautica.gcs.feature.cockpit.flow.FlowUi(), vm.flow.value)
        vm.viewModelScope.cancel()
    }
}
