package com.altnautica.gcs.feature.cockpit

import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.feature.cockpit.alerts.AlertId
import com.altnautica.gcs.feature.cockpit.chrome.StatusModel
import com.altnautica.gcs.feature.cockpit.chrome.StatusSource
import com.altnautica.gcs.feature.cockpit.preflight.PreflightItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
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

/** A `PreArm:` status text the onboard pilot relays is a pre-arm reason, as one from the link would be. */
@OptIn(ExperimentalCoroutinesApi::class)
class PrearmRelayTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a PreArm text the pilot relays fails the pre-arm check and reaches the status pill`() = runTest(dispatcher) {
        val vehicle = FakeVehicle()
        vehicle.heartbeatAt(testScheduler.currentTime, armed = false)
        // The flight controller sends PreArm lines at CRITICAL severity; they are reasons, not alerts.
        val data = """{"owner":"idle","armed":false,"landed":true,""" +
            """"statustext":[{"severity":2,"text":"PreArm: Throttle below failsafe","age_ms":200}]}"""
        val vm = testCockpitVm(vehicle, FakeAutonomyAgent(this, data).apis)
        advanceTimeBy(600)
        runCurrent()

        val check = vm.preflight.value.checks.single { it.item == PreflightItem.NoPrearm }
        assertFalse("the relayed reason fails the pre-arm check", check.passed)

        val alerts = vm.alerts.value
        assertEquals(alerts.toString(), "Throttle below failsafe", alerts.single { it.id == AlertId.PREARM }.detail)
        assertTrue("a PreArm line never raises the critical text alert: $alerts", alerts.none { it.id == AlertId.STATUS_TEXT })

        val items = vm.statusItems.value
        val item = items.singleOrNull { it.source == StatusSource.FcPrearm }
        assertNotNull("the pill lists the pre-arm reason: $items", item)
        assertEquals("fc:Throttle below failsafe", item!!.key)
        assertEquals("Flight controller: Throttle below failsafe", item.text)
        assertTrue("a new pre-arm reason flashes the pill", StatusModel.pill(items, emptySet()).flashing)
        vm.viewModelScope.cancel()
    }
}
