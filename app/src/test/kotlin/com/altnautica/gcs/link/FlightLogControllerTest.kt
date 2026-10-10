package com.altnautica.gcs.link

import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.Vehicle
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FlightLogControllerTest {

    private fun vehicle(id: String) = mockk<Vehicle> { every { this@mockk.id } returns DeviceId(id) }

    private val started = mutableListOf<Pair<Vehicle, Job>>()
    private val open = MutableStateFlow<Map<DeviceId, Vehicle>>(emptyMap())

    private fun controller() = FlightLogController(open, { v, _ -> Job().also { started += v to it } }, closeGraceMs = 15_000)

    @Test
    fun everyOpenedVehicleIsTrackedOnce() = runTest(StandardTestDispatcher()) {
        controller().start(backgroundScope)
        val a = vehicle("a")
        val b = vehicle("b")

        open.value = mapOf(a.id to a)
        runCurrent()
        open.value = mapOf(a.id to a, b.id to b)
        runCurrent()

        assertEquals(listOf(a, b), started.map { it.first })
        assertTrue(started.all { it.second.isActive })
    }

    @Test
    fun aReachFallbackGapKeepsTheSameFlightLog() = runTest(StandardTestDispatcher()) {
        controller().start(backgroundScope)
        val a = vehicle("a")
        open.value = mapOf(a.id to a)
        runCurrent()

        open.value = emptyMap()
        runCurrent()
        advanceTimeBy(5_000)
        open.value = mapOf(a.id to a)
        runCurrent()
        advanceTimeBy(20_000)

        assertEquals(1, started.size)
        assertTrue(started.single().second.isActive)
    }

    @Test
    fun aLinkClosedPastTheGraceEndsItsFlightLog() = runTest(StandardTestDispatcher()) {
        controller().start(backgroundScope)
        val a = vehicle("a")
        open.value = mapOf(a.id to a)
        runCurrent()

        open.value = emptyMap()
        runCurrent()
        advanceTimeBy(15_001)

        assertFalse(started.single().second.isActive)

        // Reopened later: a fresh log.
        open.value = mapOf(a.id to a)
        runCurrent()
        assertEquals(2, started.size)
        assertTrue(started[1].second.isActive)
    }

    @Test
    fun aNewVehicleInstanceForTheSameIdReplacesTheOldLog() = runTest(StandardTestDispatcher()) {
        controller().start(backgroundScope)
        val first = vehicle("a")
        val second = vehicle("a")
        open.value = mapOf(first.id to first)
        runCurrent()
        open.value = mapOf(second.id to second)
        runCurrent()

        assertEquals(2, started.size)
        assertFalse(started[0].second.isActive)
        assertTrue(started[1].second.isActive)
    }
}
