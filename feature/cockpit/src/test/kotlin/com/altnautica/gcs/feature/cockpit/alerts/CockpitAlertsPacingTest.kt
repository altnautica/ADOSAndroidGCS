package com.altnautica.gcs.feature.cockpit.alerts

import com.altnautica.gcs.core.data.cockpit.CockpitSettings
import com.altnautica.gcs.core.model.BatteryState
import com.altnautica.gcs.core.model.CockpitAlert
import com.altnautica.gcs.core.model.VehicleTelemetry
import com.altnautica.gcs.core.model.VideoState
import com.altnautica.gcs.core.skills.MonotonicClock
import com.altnautica.gcs.feature.cockpit.FakeTelemetry
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CockpitAlertsPacingTest {
    // Fails on the base commit: every battery message evaluated the engine, so the battery
    // alert appeared between ticks instead of on the next one.
    @Test
    fun `telemetry readings raise alerts on the tick, discrete events at once`() = runTest(UnconfinedTestDispatcher()) {
        val clock = MonotonicClock { testScheduler.currentTime }
        val t = FakeTelemetry()
        val tick = MutableStateFlow(0L)
        val video = MutableStateFlow<VideoState>(VideoState.Idle)
        val ctl = CockpitAlertsController(
            backgroundScope, clock, MutableStateFlow<VehicleTelemetry?>(t), video,
            MutableStateFlow(null), MutableStateFlow(null), MutableStateFlow(CockpitSettings()), tick,
            mockk(relaxed = true), MutableStateFlow(null), MutableStateFlow(false),
        )
        val seen = mutableListOf<List<CockpitAlert>>()
        backgroundScope.launch { ctl.alerts.collect { seen += it } }
        runCurrent()
        fun has(id: String) = ctl.alerts.value.any { it.id == id }

        // A 10 Hz battery stream falling to critical between ticks: nothing until the tick.
        repeat(4) { i ->
            t.battery.value = BatteryState(0, 14f, 10f, 2, null, clock.nowMs())
            advanceTimeBy(100)
            assertFalse("battery sampled at $i before the tick", has(AlertId.BATT_CRIT))
        }
        tick.value = clock.nowMs()
        assertTrue(has(AlertId.BATT_CRIT))

        // Video freezing does not wait for the tick.
        video.value = VideoState.Frozen(0L)
        assertTrue(has(AlertId.VIDEO_FROZEN))
        assertEquals(seen.last(), ctl.alerts.value)
    }
}
