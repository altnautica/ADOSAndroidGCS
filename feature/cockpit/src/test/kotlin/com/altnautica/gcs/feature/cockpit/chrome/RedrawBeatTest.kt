package com.altnautica.gcs.feature.cockpit.chrome

import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.core.model.PositionFix
import com.altnautica.gcs.core.model.Vfr
import com.altnautica.gcs.feature.cockpit.FakeVehicle
import com.altnautica.gcs.feature.cockpit.heartbeatAt
import com.altnautica.gcs.feature.cockpit.testCockpitVm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RedrawBeatTest {
    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the beat numbers its ticks every 100 ms`() = runTest(dispatcher) {
        val beat = RedrawBeat()
        val seen = ArrayList<Pair<Long, Long>>()
        backgroundScope.launch { beat.run() }
        backgroundScope.launch { beat.ticks.collect { seen += it to testScheduler.currentTime } }
        advanceTimeBy(1_050)
        assertEquals((1L..10L).map { it to it * RedrawBeat.BEAT_MS }, seen)
    }

    @Test
    fun `the view model's 2 Hz readouts change only on every fifth beat, in the HUD's frame`() = runTest(dispatcher) {
        val vehicle = FakeVehicle()
        vehicle.heartbeatAt(testScheduler.currentTime, armed = true)
        val vm = testCockpitVm(vehicle)
        vm.setFlightViewShown(true)
        val beats = HashMap<Long, Long>()
        backgroundScope.launch { vm.redrawBeat.ticks.collect { beats[testScheduler.currentTime] = it } }
        val changes = ArrayList<Long>()
        backgroundScope.launch { vm.flightValues.collect { changes += testScheduler.currentTime } }
        runCurrent()
        // Telemetry at the video rate for 3 s.
        repeat(90) { i ->
            val t = testScheduler.currentTime
            vehicle.telemetry.position.value = PositionFix(47.0, 8.0, 430f, 10f + i, 3f, 1f, 0f, t)
            vehicle.telemetry.vfr.value = Vfr(3f, 3f + i * 0.1f, 90f, 40, 0.1f * i, t)
            advanceTimeBy(33)
            runCurrent()
        }
        val moved = changes.drop(1)
        assertTrue("the readouts follow the telemetry: $moved", moved.size >= 5)
        for (t in moved) {
            val n = beats[t]
            assertTrue("a readout changed at $t ms, off the beat (beat $n)", n != null && n % 5 == 0L)
        }
        vm.viewModelScope.cancel()
    }

    @Test
    fun `a hidden beat is silent and steps its cadence 500 ms at a time, then resumes when shown`() = runTest(dispatcher) {
        val beat = RedrawBeat(shown = false)
        val ticks = ArrayList<Long>()
        val cadence = ArrayList<Pair<Long, Long>>()
        backgroundScope.launch { beat.run() }
        backgroundScope.launch { beat.ticks.collect { ticks += it } }
        backgroundScope.launch { beat.cadence.collect { cadence += it to testScheduler.currentTime } }
        advanceTimeBy(1_550)
        assertEquals("no redraw beat while hidden", emptyList<Long>(), ticks)
        assertEquals(listOf(5L to 500L, 10L to 1_000L, 15L to 1_500L), cadence)
        beat.setShown(true)
        runCurrent()
        advanceTimeBy(250)
        assertEquals("beats resume at once when shown", listOf(16L, 17L), ticks)
        beat.setShown(false)
        advanceTimeBy(1_000)
        assertEquals(listOf(16L, 17L), ticks)
        assertEquals("the hidden steps land on every fifth beat", listOf(18L, 20L), cadence.drop(5).map { it.first })
    }

    @Test
    fun `the hidden flight screen runs no beat, while the view model's 2 Hz readouts keep up`() = runTest(dispatcher) {
        val vehicle = FakeVehicle()
        vehicle.heartbeatAt(testScheduler.currentTime, armed = true)
        val vm = testCockpitVm(vehicle)
        val beats = ArrayList<Long>()
        backgroundScope.launch { vm.redrawBeat.ticks.collect { beats += it } }
        val changes = ArrayList<Long>()
        backgroundScope.launch { vm.flightValues.collect { changes += testScheduler.currentTime } }
        runCurrent()
        repeat(90) { i ->
            val t = testScheduler.currentTime
            vehicle.telemetry.position.value = PositionFix(47.0, 8.0, 430f, 10f + i, 3f, 1f, 0f, t)
            vehicle.telemetry.vfr.value = Vfr(3f, 3f + i * 0.1f, 90f, 40, 0.1f * i, t)
            advanceTimeBy(33)
            runCurrent()
        }
        assertEquals("no redraw beat (HUD, layers, minimap, pill flash) while hidden", emptyList<Long>(), beats)
        val moved = changes.drop(1)
        assertTrue("the 2 Hz readouts still follow the telemetry: $moved", moved.size >= 5)
        assertTrue("readouts change only on 500 ms steps: $moved", moved.all { it % 500 == 0L })
        vm.viewModelScope.cancel()
    }
}
