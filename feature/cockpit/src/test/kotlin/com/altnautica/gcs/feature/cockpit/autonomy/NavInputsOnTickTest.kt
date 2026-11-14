package com.altnautica.gcs.feature.cockpit.autonomy

import com.altnautica.gcs.core.model.EkfState
import com.altnautica.gcs.core.model.PilotOwner
import com.altnautica.gcs.core.model.PilotState
import com.altnautica.gcs.core.model.PositionUnverified
import com.altnautica.gcs.core.model.VehicleTelemetry
import com.altnautica.gcs.core.skills.MonotonicClock
import com.altnautica.gcs.feature.cockpit.FakeTelemetry
import com.altnautica.gcs.feature.cockpit.pilotState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NavInputsOnTickTest {
    // Fails on the base commit: the inputs carried the raw EKF age and re-emitted on every
    // EKF message, so a 10 Hz EKF stream made ten unequal values a second.
    @Test
    fun `equal EKF reports at 10 Hz emit only on a change, and staleness still shows on the tick`() = runTest(UnconfinedTestDispatcher()) {
        val clock = MonotonicClock { testScheduler.currentTime }
        val t = FakeTelemetry()
        val tick = MutableStateFlow(0L)
        val inputs = navInputsOnTick(
            backgroundScope, clock, MutableStateFlow<VehicleTelemetry?>(t), MutableStateFlow(null), tick,
            MutableStateFlow(null), MutableStateFlow(emptyMap()), MutableStateFlow(null),
        )
        val seen = mutableListOf<NavInputs?>()
        backgroundScope.launch { inputs.collect { seen += it } }
        runCurrent()
        repeat(20) { i ->
            t.ekf.value = EkfState(flags = 0x1F, velVar = 0.1f, posHorizVar = 0.1f, posVertVar = 0.1f, compassVar = 0.1f, tMs = clock.nowMs())
            advanceTimeBy(100)
            if (i % 5 == 4) tick.value = clock.nowMs()
        }
        // null, the reading before any report, then the first tick's fresh one; later ticks are equal.
        assertEquals(3, seen.size)
        assertEquals(0L, seen.last()?.ekfAgeMs)
        advanceTimeBy(NavSource.EKF_STALE_MS + 100)
        tick.value = clock.nowMs()
        assertEquals(NavSource.EKF_STALE_MS, inputs.value?.ekfAgeMs)
        assertEquals(NavLabel.Stale, NavSource.resolve(inputs.value!!).label)
    }

    @Test
    fun `does not take position_unverified from a stale pilot`() = runTest(UnconfinedTestDispatcher()) {
        val clock = MonotonicClock { testScheduler.currentTime }
        val tick = MutableStateFlow(0L)
        val unverified = PositionUnverified("extnav_lost", "corridor_unmeasured", null, null)
        val pilot = MutableStateFlow<PilotState?>(null)
        val inputs = navInputsOnTick(
            backgroundScope, clock, MutableStateFlow<VehicleTelemetry?>(FakeTelemetry()), MutableStateFlow(null), tick,
            MutableStateFlow(null), MutableStateFlow(emptyMap()), pilot,
        )
        backgroundScope.launch { inputs.collect {} }
        advanceTimeBy(10_000)
        pilot.value = pilotState(PilotOwner.Pilot, receivedAtMs = clock.nowMs()).copy(positionUnverified = unverified)
        runCurrent()
        assertEquals(unverified, inputs.value?.positionUnverified)
        // The link drops: no new frame, and the next tick finds the last one stale.
        advanceTimeBy(PilotState.STALE_MS + 100)
        tick.value = clock.nowMs()
        assertNull(inputs.value?.positionUnverified)
    }
}
