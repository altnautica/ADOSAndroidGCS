package com.altnautica.gcs.feature.cockpit

import com.altnautica.gcs.core.model.CockpitAlert
import com.altnautica.gcs.core.model.PilotOwner
import com.altnautica.gcs.core.model.PilotState
import com.altnautica.gcs.feature.cockpit.flow.FlowUi
import com.altnautica.gcs.feature.cockpit.preflight.PreflightContext
import com.altnautica.gcs.feature.cockpit.preflight.STORAGE_POLL_MS
import com.altnautica.gcs.feature.cockpit.preflight.storageUsedPct
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.coroutines.EmptyCoroutineContext

/** View-model state that changes on its own clock reaches the screen only on the tick or on change. */
@OptIn(ExperimentalCoroutinesApi::class)
class VmPacingTest {
    // Fails on the base commit: there is no tick sampling; the battery reserve poll emitted
    // on its own 2 s clock, off the beat.
    @Test
    fun `a polled value is published only on the tick`() = runTest(UnconfinedTestDispatcher()) {
        val source = MutableStateFlow(1)
        val tick = MutableStateFlow(0L)
        val paced = onTick(backgroundScope, source, tick, 0)
        backgroundScope.launch { paced.collect {} }
        runCurrent()
        assertEquals(1, paced.value)
        source.value = 2
        advanceTimeBy(300)
        assertEquals("between ticks the old value stays", 1, paced.value)
        tick.value = 500
        assertEquals(2, paced.value)
    }

    // Fails on the base commit: the context held the whole pilot state, so every frame (a new
    // receive time) was a new context and re-evaluated the checklist.
    @Test
    fun `pilot frames differing only in receive time or input age leave the checklist context equal`() {
        val a = pilotState(PilotOwner.Pilot, receivedAtMs = 100).copy(inputAgeMs = 10)
        val b = pilotState(PilotOwner.Pilot, receivedAtMs = 200).copy(inputAgeMs = 40)
        assertEquals(PreflightContext.of(a, true, null), PreflightContext.of(b, true, null))
    }

    // Fails on the base commit: storage was read (StatFs) on every checklist evaluation.
    @Test
    fun `storage is read once a period, in whole percent`() = runTest(UnconfinedTestDispatcher()) {
        var reads = 0
        val pct = storageUsedPct(backgroundScope, EmptyCoroutineContext) { reads++; 41.6 }
        runCurrent()
        assertEquals(42.0, pct.value!!, 0.0)
        advanceTimeBy(STORAGE_POLL_MS * 2 + 500)
        assertEquals(3, reads)
    }

    // Fails on the base commit: the tip host read the raw pilot state, so each frame recomposed it.
    @Test
    fun `tip facts change only when a fact does`() = runTest(UnconfinedTestDispatcher()) {
        val pilot = MutableStateFlow<PilotState?>(null)
        val facts = tipFlightFacts(
            backgroundScope, pilot, MutableStateFlow(BandState.Empty), MutableStateFlow(emptyList<CockpitAlert>()), MutableStateFlow(FlowUi()),
        )
        val seen = mutableListOf<TipFlightFacts>()
        backgroundScope.launch { facts.collect { seen += it } }
        runCurrent()
        repeat(10) { i -> pilot.value = pilotState(PilotOwner.Idle, armed = false, receivedAtMs = i * 100L) }
        runCurrent()
        // The initial facts, then one change when the fresh pilot arrives.
        assertEquals(2, seen.size)
        assertEquals(false, seen.last().flight.armed)
    }
}
