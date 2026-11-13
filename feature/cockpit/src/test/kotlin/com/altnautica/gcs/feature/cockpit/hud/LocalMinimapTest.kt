package com.altnautica.gcs.feature.cockpit.hud

import androidx.compose.ui.geometry.Offset
import com.altnautica.gcs.core.agent.pilot.PilotWire
import com.altnautica.gcs.core.skills.hud.freshPilot
import com.altnautica.gcs.feature.cockpit.autonomy.LocalFrame
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The local minimap's planned path: what a pilot frame plans, and where it draws. */
class LocalMinimapTest {
    private fun pilot(json: String, receivedAtMs: Long = 0L) =
        PilotWire.parseState(Json.parseToJsonElement(json).jsonObject, receivedAtMs)

    /** The drone at the origin, drawn at the centre of a 200 px square at 10 px a metre. */
    private fun pt(n: Float, e: Float): Offset =
        LocalFrame.toScreen(n, e, 0f, 0f, 10f, 100f, 100f).let { Offset(it.first, it.second) }

    @Test
    fun `projects the pilot's planned path and goal with north up the screen`() {
        val plan = LocalPlan.of(pilot("""{"owner":"goto","plan_ned":[[0,0],[5,0],[5,2]],"goal_ned":[5,2,-5],"avoid_state":"clear"}"""))!!
        val s = screenPlan(plan, ::pt)
        assertEquals(listOf(Offset(100f, 100f), Offset(100f, 50f), Offset(120f, 50f)), s.points)
        assertEquals(s.points.last(), s.goal)
        assertTrue(s.points[1].y < s.points[0].y)
        assertFalse(s.blocked)
    }

    @Test
    fun `a blocked goal draws without a path, and no plan draws nothing`() {
        val blocked = LocalPlan.of(pilot("""{"owner":"goto","goal_ned":[1,0,-5],"avoid_state":"blocked"}"""))!!
        assertEquals(ScreenPlan(emptyList(), Offset(100f, 90f), true), screenPlan(blocked, ::pt))
        assertNull(LocalPlan.of(pilot("""{"owner":"pilot","plan_ned":[]}""")))
        assertNull(LocalPlan.of(null))
    }

    @Test
    fun `a stale pilot frame plans nothing`() {
        val p = pilot("""{"owner":"goto","plan_ned":[[0,0],[5,0]],"goal_ned":[5,0,-5]}""", receivedAtMs = 1_000L)
        assertEquals(LocalPlan(listOf(0f to 0f, 5f to 0f), 5f to 0f, false), LocalPlan.of(freshPilot(p, 1_500L)))
        assertNull(LocalPlan.of(freshPilot(p, 10_000L)))
    }
}
