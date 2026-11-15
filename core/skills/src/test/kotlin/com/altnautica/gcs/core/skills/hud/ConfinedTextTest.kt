package com.altnautica.gcs.core.skills.hud

import com.altnautica.gcs.core.model.PlaceClass
import com.altnautica.gcs.core.model.RouteCheck
import com.altnautica.gcs.core.model.RouteCheckState
import com.altnautica.gcs.core.model.RoutePinch
import com.altnautica.gcs.core.model.SpeedCap
import com.altnautica.gcs.core.skills.adosPilot
import com.altnautica.gcs.core.skills.testSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The confined profile's chip, route-check report, speed-cap line and picker text, as the shared web module words them. */
class ConfinedTextTest {
    private val text = ConfinedText(SpecText(testSpec.setupStrings))
    private val pinch = RoutePinch(3, 4, listOf(41.25, -17.5, -2.0), 0.52, 0.577)

    @Test
    fun `the Confined chip shows only in the confined profile`() {
        assertEquals("Confined", text.chip(adosPilot().copy(placeClass = PlaceClass.Confined)))
        assertNull(text.chip(adosPilot()))
        assertNull(text.chip(null))
    }

    @Test
    fun `the infeasible report names the pinch leg, its clearance and what it needs, two decimals, and where it is`() {
        val r = text.report(RouteCheck(RouteCheckState.Infeasible, 0.577, 0.477, 0.02, pinch, listOf(5, 6)))!!
        assertEquals(RouteCheckSeverity.Critical, r.severity)
        assertEquals("Route check", r.title)
        assertEquals("Route too tight: leg 3→4 has 0.52 m, needs 0.58 m", r.line)
        assertEquals("At N 41.3 m, E -17.5 m, height 2.0 m", r.pinchAt)
    }

    @Test
    fun `feasible and unverified reports read their own lines`() {
        val ok = text.report(RouteCheck(RouteCheckState.Feasible, 0.577, 0.477, 0.02, null, emptyList()))!!
        assertEquals(RouteCheckSeverity.Ok, ok.severity)
        assertEquals("Route clear · keeps 0.58 m", ok.line)
        assertNull(ok.pinchAt)
        val unseen = text.report(RouteCheck(RouteCheckState.Unverified, 0.577, 0.477, null, null, listOf(5)))!!
        assertEquals(RouteCheckSeverity.Warn, unseen.severity)
        assertEquals("Route not seen yet · slow near walls", unseen.line)
        assertNull(text.report(null))
    }

    @Test
    fun `an unverified report names the waypoints it could not place, then a mission too long, and a pinch outranks both`() {
        val base = RouteCheck(RouteCheckState.Unverified, 0.577, 0.477, 0.02, null, emptyList())
        assertEquals(
            "Route not fully checked: no position for waypoint 3, 5 · slow near walls",
            text.report(base.copy(unchecked = listOf(3, 5), truncated = true))!!.line,
        )
        assertEquals("Route not fully checked: the mission is too long · slow near walls", text.report(base.copy(truncated = true))!!.line)
        assertEquals(
            "Route too tight: leg — has — m, needs 0.58 m",
            text.report(base.copy(state = RouteCheckState.Infeasible, unchecked = listOf(3)))!!.line,
        )
    }

    @Test
    fun `clearances round half up from the wire's double, 0_525 to 0_53 as the web and the agent print it`() {
        val r = text.report(RouteCheck(RouteCheckState.Infeasible, 0.577, 0.477, 0.02, pinch.copy(clearanceM = 0.525), emptyList()))!!
        assertEquals("Route too tight: leg 3→4 has 0.53 m, needs 0.58 m", r.line)
    }

    @Test
    fun `the speed cap reads its reason's line, none for a reason the spec does not name`() {
        assertEquals("Near the clearance limit: 0.5 m/s max", text.speedCapLine(SpeedCap("confined-near", 0.5f)))
        assertEquals("GNSS-denied: 2 m/s max", text.speedCapLine(SpeedCap("gnss-denied", 2f)))
        assertNull(text.speedCapLine(SpeedCap("wind", 3f)))
    }

    @Test
    fun `the picker labels each class and explains it with the margin it flies`() {
        assertEquals("Open", text.label(PlaceClass.Open))
        assertEquals("Confined space", text.label(PlaceClass.Confined))
        assertEquals("Open sites: 1.5 m from obstacles.", text.explain(PlaceClass.Open, ConfinedText.effectiveMarginM(PlaceClass.Open, null, null)))
        assertEquals(0.477, ConfinedText.effectiveMarginM(PlaceClass.Confined, 0.3, 0.377)!!, 1e-9)
        // The confined margin is the prop-tip radius plus 0.1 m, whatever the configured open margin.
        assertEquals(0.477, ConfinedText.effectiveMarginM(PlaceClass.Confined, 1.5, 0.377)!!, 1e-9)
        assertEquals(0.477, ConfinedText.effectiveMarginM(PlaceClass.Confined, null, 0.377)!!, 1e-9)
        assertEquals(0.5, ConfinedText.effectiveMarginM(PlaceClass.Open, 0.3, 0.377)!!, 1e-9)
        assertNull(ConfinedText.effectiveMarginM(PlaceClass.Confined, null, null))
        assertEquals(
            "Mines, tunnels and rooms: 0.48 m from walls, 0.5 m/s near the limit. Choose it only for a confined place.",
            text.explain(PlaceClass.Confined, 0.477),
        )
        assertEquals(
            "Mines, tunnels and rooms: — m from walls, 0.5 m/s near the limit. Choose it only for a confined place.",
            text.explain(PlaceClass.Confined, null),
        )
    }
}
