package com.altnautica.gcs.feature.plan.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanHistoryTest {

    private fun plan(n: Int) = MissionPlan(items = List(n) { PlanItem(id = it.toLong(), command = MavCmd.NAV_WAYPOINT, frame = MavFrame.GLOBAL_RELATIVE_ALT) })

    @Test
    fun `undo and redo swap through the stacks`() {
        var history = PlanHistory().record(plan(0)).record(plan(1))
        assertTrue(history.canUndo)
        assertFalse(history.canRedo)

        val (h1, back1) = history.undo(plan(2))!!
        assertEquals(plan(1), back1)
        val (h2, back0) = h1.undo(back1)!!
        assertEquals(plan(0), back0)
        assertNull(h2.undo(back0))
        assertTrue(h2.canRedo)

        val (h3, forward) = h2.redo(back0)!!
        assertEquals(plan(1), forward)
        history = h3.record(forward)
        assertFalse(history.canRedo)
    }

    @Test
    fun `depth is capped at the oldest end`() {
        var history = PlanHistory(maxDepth = 3)
        (0 until 5).forEach { history = history.record(plan(it)) }
        assertEquals(listOf(plan(2), plan(3), plan(4)), history.past)
    }

    @Test
    fun `cleared drops both stacks and keeps the depth`() {
        val history = PlanHistory(maxDepth = 7).record(plan(0)).cleared()
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
        assertEquals(7, history.maxDepth)
    }
}
