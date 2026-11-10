package com.altnautica.gcs.feature.cockpit.tips

import com.altnautica.gcs.core.skills.tips.HideReason
import com.altnautica.gcs.core.skills.tips.TipDecision
import com.altnautica.gcs.core.skills.tips.TipsEngine
import com.altnautica.gcs.core.skills.tips.TipsState
import com.altnautica.gcs.feature.cockpit.testSpec
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TipsControllerTest {
    private val engine = TipsEngine(testSpec.tips)
    private var now = 0L
    private var state = TipsState()
    private val markedSeen = mutableListOf<String>()
    private val controller = TipsController(
        engine = engine,
        clock = { now },
        markSeen = { id ->
            markedSeen += id
            state = TipsEngine.dismissTip(state, id)
        },
        noteFlightScreenOpen = { state = TipsEngine.onFlightScreenOpen(state) },
    )
    private val ground = TipFacts(
        armed = false,
        airborne = false,
        sheetOrMenuOpen = false,
        tourVisible = false,
        skillsAvailable = false,
        gotoOffered = false,
        gimbalAvailable = false,
    )
    private val allAnchors = engine.tips.map { it.anchor }.associateWith { true }

    private suspend fun tickAt(ms: Long, facts: TipFacts = ground, anchors: Map<String, Boolean> = allAnchors): TipDecision {
        now = ms
        return controller.tick(state, facts, anchors)
    }

    private fun shownId(): String? = controller.shown.value?.tipId

    @Test
    fun `the first tip shows only once 2500 ms have passed since the screen opened`() = runTest {
        controller.open()
        assertEquals(TipDecision.Wait("status_pill", 2_500), tickAt(0))
        assertNull(shownId())
        tickAt(2_499)
        assertNull(shownId())
        assertEquals(TipDecision.Show("status_pill", "tips.status_pill", "status_pill"), tickAt(2_500))
        assertEquals("status_pill", shownId())
    }

    @Test
    fun `no tip shows while an alert banner is up, and the first shows once it clears`() = runTest {
        controller.open()
        tickAt(2_500, ground.copy(alertBannerVisible = true))
        assertNull(shownId())
        tickAt(3_000)
        assertEquals("status_pill", shownId())
    }

    @Test
    fun `the screen open is counted once per controller`() = runTest {
        controller.open()
        controller.open()
        assertEquals(1, state.openCount)
    }

    @Test
    fun `got it marks the tip seen and the next shows 4000 ms later`() = runTest {
        controller.open()
        tickAt(2_500)
        now = 3_000
        assertTrue(controller.dismiss())
        assertEquals(listOf("status_pill"), markedSeen)
        assertNull(shownId())
        assertEquals(TipDecision.Wait("flight_state_chip", 7_000), tickAt(6_999))
        assertNull(shownId())
        tickAt(7_000)
        assertEquals("flight_state_chip", shownId())
        assertFalse(controller.dismissFromAnchor("stop"))
        assertTrue(controller.dismissFromAnchor("flight_state_chip"))
        assertEquals(listOf("status_pill", "flight_state_chip"), markedSeen)
    }

    @Test
    fun `arming hides the tip, marks it seen and nothing shows while armed`() = runTest {
        controller.open()
        tickAt(2_500)
        val armed = ground.copy(armed = true)
        assertEquals(TipDecision.Hide("status_pill", markSeen = true, reason = HideReason.Arm), tickAt(3_000, armed))
        assertNull(shownId())
        assertEquals(listOf("status_pill"), markedSeen)
        assertEquals(TipDecision.None, tickAt(20_000, armed))
        assertEquals(TipDecision.None, tickAt(20_000, armed.copy(airborne = true)))
        assertNull(shownId())
        assertFalse(controller.dismiss())
    }

    @Test
    fun `auto dismiss at 9000 ms hides the tip without marking it seen`() = runTest {
        controller.open()
        tickAt(2_500)
        tickAt(11_499)
        assertEquals("status_pill", shownId())
        assertEquals(TipDecision.Hide("status_pill", markSeen = false, reason = HideReason.AutoDismiss), tickAt(11_500))
        assertNull(shownId())
        assertTrue(markedSeen.isEmpty())
        assertFalse("status_pill" in state.seen)
        // Not offered again on this open: the queue moves on after the gap.
        tickAt(15_500)
        assertEquals("flight_state_chip", shownId())
    }

    @Test
    fun `a missing anchor skips its tip without marking it seen`() = runTest {
        controller.open()
        tickAt(2_500, anchors = allAnchors + ("status_pill" to false))
        assertEquals("flight_state_chip", shownId())
        // The shown tip's anchor goes away: hidden, still unseen.
        val hidden = tickAt(3_000, anchors = allAnchors + mapOf("status_pill" to false, "flight_state_chip" to false))
        assertEquals(TipDecision.Hide("flight_state_chip", markSeen = false, reason = HideReason.NotAllowed), hidden)
        assertNull(shownId())
        assertTrue(markedSeen.isEmpty())
        assertTrue(state.seen.isEmpty())
    }
}
