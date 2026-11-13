package com.altnautica.gcs.feature.cockpit

import com.altnautica.gcs.core.model.CockpitAlert
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.PilotOwner
import com.altnautica.gcs.core.model.PilotState
import com.altnautica.gcs.core.skills.MonotonicClock
import com.altnautica.gcs.core.skills.hud.PillLinkLoss
import com.altnautica.gcs.core.skills.hud.PillNavSource
import com.altnautica.gcs.core.skills.hud.SpecText
import com.altnautica.gcs.core.skills.hud.linkLossLine
import com.altnautica.gcs.feature.cockpit.alerts.ReceivedText
import com.altnautica.gcs.feature.cockpit.chrome.StatusItem
import com.altnautica.gcs.feature.cockpit.chrome.specStatusPill
import com.altnautica.gcs.feature.cockpit.pilot.CockpitPilot
import com.altnautica.gcs.feature.cockpit.pilot.PillFacts
import com.altnautica.gcs.feature.cockpit.preflight.PreflightResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The status pill lists failing pre-flight checks only on a vehicle known to be on the ground, disarmed. */
@OptIn(ExperimentalCoroutinesApi::class)
class CockpitPillTest {
    @Test
    fun `pre-flight items show only when disarmed is known`() {
        assertTrue(CockpitPill.preflightShown(armed = false))
        assertFalse(CockpitPill.preflightShown(armed = true))
        assertFalse("unknown is not on the ground", CockpitPill.preflightShown(armed = null))
    }

    @Test
    fun `the pilot age is bucketed to the pill's stale threshold`() {
        assertEquals(null, CockpitPill.pillPilotAgeMs(null, 1_000))
        assertEquals(CockpitPill.FRESH_AGE_MS, CockpitPill.pillPilotAgeMs(1_000, 1_000 + PilotState.STALE_MS - 1))
        assertEquals(PilotState.STALE_MS, CockpitPill.pillPilotAgeMs(1_000, 1_000 + PilotState.STALE_MS + 500))
    }

    @Test
    fun `the shown link-loss countdown keeps its text`() {
        val text = SpecText(testSpec.setupStrings)
        for (s in listOf(0.2f, 1f, 5.1f, 5.95f, 6f)) {
            val p = pilotState(PilotOwner.Pilot).copy(linkLossInS = s, linkLossAction = "rth")
            val raw = PillLinkLoss.of(p)!!
            val shown = CockpitPill.shownLinkLoss(p)!!
            assertEquals(text.linkLossLine(raw.inS, raw.action), text.linkLossLine(shown.inS, shown.action))
            assertEquals(specStatusPill().linkLossText(raw), specStatusPill().linkLossText(shown))
        }
    }

    // Fails on the base commit: there the facts carry the raw frame age and the raw countdown
    // float, so every 10 Hz frame and every tick made a new emission.
    @Test
    fun `equal pilot frames at 10 Hz change the facts and items only when the shown state does`() = runTest(UnconfinedTestDispatcher()) {
        val clock = MonotonicClock { testScheduler.currentTime }
        val navPilot = MutableStateFlow<PilotState?>(null)
        val pilot = CockpitPilot(backgroundScope, clock, MutableStateFlow<DeviceId?>(null), { null }, navPilot, flowOf(null))
        val tick = MutableStateFlow(0L)
        val pill = CockpitPill(
            backgroundScope, clock, pilot, MutableStateFlow(PreflightResult(emptyList())), MutableStateFlow(emptyList<CockpitAlert>()),
            MutableStateFlow<ReceivedText?>(null), MutableStateFlow(emptyList()), tick, specStatusPill(),
            heartbeatSeen = flowOf(true), navSource = flowOf<PillNavSource?>(null),
        ) { true }
        val facts = mutableListOf<PillFacts>()
        val items = mutableListOf<List<StatusItem>>()
        backgroundScope.launch { pill.facts.collect { facts += it } }
        backgroundScope.launch { pill.items.collect { items += it } }
        runCurrent()
        repeat(10) { i ->
            // Same content each frame; the countdown stays inside one shown second (6 s).
            navPilot.value = pilotState(PilotOwner.Pilot, receivedAtMs = clock.nowMs())
                .copy(linkLossInS = 6f - 0.1f * i, linkLossAction = "rth")
            advanceTimeBy(50)
            if (i % 5 == 0) tick.value = clock.nowMs()
            advanceTimeBy(50)
        }
        // The initial value, then one change when the first frame arrives.
        assertEquals(2, facts.size)
        assertEquals(6f, facts.last().linkLoss!!.inS)
        assertEquals(2, items.size)
    }
}
