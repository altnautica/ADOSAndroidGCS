package com.altnautica.gcs.feature.cockpit.hud.layers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LayerRulesTest {
    private val spec = LayerSpec.DEFAULT
    private val full = spec.defaults()
    private val route = full.withLevel(LayerLevel.Route, spec)
    private val off = full.withLevel(LayerLevel.Off, spec)

    private fun item(t: String, role: String? = null, of: String? = null) = ArItem(t = t, role = role, of = of)

    @Test
    fun `mission state picks the route style`() {
        assertEquals(RouteStyle.Normal, ArRules.routeStyle(null, false))
        assertEquals(RouteStyle.Normal, ArRules.routeStyle(MissionState("active"), false))
        assertEquals(RouteStyle.Paused, ArRules.routeStyle(MissionState("paused"), false))
        assertEquals(RouteStyle.Preview, ArRules.routeStyle(MissionState("loaded"), true))
        assertNull(ArRules.routeStyle(MissionState("loaded"), false))
        assertEquals(RouteStyle.Returning, ArRules.routeStyle(MissionState("returning"), false))
        assertNull(ArRules.routeStyle(MissionState("complete"), true))
        assertNull(ArRules.routeStyle(MissionState("none"), true))
    }

    @Test
    fun `levels filter route roles`() {
        assertTrue(ArRules.include(item("wp", "other"), full, RouteStyle.Normal))
        assertFalse(ArRules.include(item("wp", "other"), route, RouteStyle.Normal))
        assertTrue(ArRules.include(item("wp", "after"), route, RouteStyle.Normal))
        assertFalse(ArRules.include(item("leg", "future"), route, RouteStyle.Normal))
        assertTrue(ArRules.include(item("leg", "active"), route, RouteStyle.Normal))
        assertTrue(ArRules.include(item("home"), route, RouteStyle.Normal))
        assertTrue(ArRules.include(item("fpath"), route, RouteStyle.Normal))
        assertFalse(ArRules.include(item("home"), off, RouteStyle.Normal))
        assertFalse(ArRules.include(item("fpv"), off, RouteStyle.Normal))
    }

    @Test
    fun `returning hides the route but keeps home and its edge`() {
        assertFalse(ArRules.include(item("wp", "next"), full, RouteStyle.Returning))
        assertFalse(ArRules.include(item("leg", "active"), full, RouteStyle.Returning))
        assertTrue(ArRules.include(item("home"), full, RouteStyle.Returning))
        assertTrue(ArRules.include(item("edge", of = "home"), full, RouteStyle.Returning))
        assertFalse(ArRules.include(item("edge", of = "wp"), full, RouteStyle.Returning))
        assertFalse(ArRules.include(item("wp", "next"), full, null))
    }

    @Test
    fun `wp chip formats distance and eta`() {
        assertEquals("WP4 · 120 m · 0:42", ArRules.wpChip(4, 120f, 42f))
        assertEquals("WP4 · 120 m", ArRules.wpChip(4, 120f, null))
        assertEquals(24f + 40f, ArRules.markerDp(1f))
        assertEquals(34f, ArRules.markerDp(0.25f))
    }

    @Test
    fun `levels leave leased layers alone and views force theirs`() {
        val withDepth = full.toggled(LayerSpec.DEPTH)
        assertTrue(withDepth.withLevel(LayerLevel.Off, spec).isOn(LayerSpec.DEPTH))
        assertFalse(full.withLevel(LayerLevel.Route, spec).isOn(LayerSpec.DEPTH))
        assertEquals(setOf(LayerSpec.HUD), off.on)
        val vision = off.forcing(spec.forced(LayerSpec.VIEW_VISION))
        assertTrue(vision.isOn(LayerSpec.DEPTH) && vision.isOn(LayerSpec.SURFACES) && vision.isOn(LayerSpec.TARGETS))
        assertEquals(LayerLevel.Off, vision.level)
        assertEquals(LayerLevel.Route, LayerLevel.Full.cycle(1))
        assertEquals(LayerLevel.Off, LayerLevel.Full.cycle(-1))
    }

    @Test
    fun `the shipped cockpit spec parses and matches the built-in copy`() {
        val dir = listOf("src/main/assets/cockpit-spec", "feature/cockpit/src/main/assets/cockpit-spec").map(::File).first { it.isDirectory }
        val parsed = LayerSpec.parse(File(dir, "layers.json").readText(), File(dir, "views.json").readText())
        assertNotNull(parsed)
        assertEquals(LayerSpec.DEFAULT, parsed)
        assertEquals(listOf("depth", "surfaces", "obstacles", "targets", "ar_route", "ar_motion"), parsed!!.canvasOrder)
    }

    @Test
    fun `persisted state round trips`() {
        val f = route.toggled(LayerSpec.DEPTH)
        val d = DepthOptions(CellStyle.Outline, null)
        val (f2, d2) = LayerController.decode(LayerController.encode(f, d))!!
        assertEquals(f, f2)
        assertEquals(d, d2)
        assertNull(LayerController.decode("{bad"))
        assertNull(LayerController.decode(null))
    }

    @Test
    fun `lease messages carry the contract fields`() {
        assertEquals("""{"type":"view","camera_id":"main","layers":["ar_route","obstacles"]}""", LayerController.viewLease("main", listOf("ar_route", "obstacles")))
        assertEquals("""{"kind":"depth","camera_id":"main","hold_s":6.0,"owner":"android"}""", LayerController.demandBody("depth", "main", 6.0).toString())
        assertEquals("""{"camera_id":"main","hold_s":0.0,"owner":"android"}""", LayerController.demandBody(LayerController.DETECTION, "main", 0.0).toString())
    }

    @Test
    fun `slot interpolates over the measured interval and fades`() {
        val s = LayerSlot<String>()
        s.push("a", 1, 1_000)
        assertEquals(1f, s.fraction(1_000))
        s.push("b", 2, 1_100)
        assertEquals(100L, s.intervalMs())
        assertEquals(0f, s.fraction(1_100))
        assertEquals(0.5f, s.fraction(1_150))
        assertEquals(1f, s.fraction(1_300))
        assertTrue(s.animating(1_250))
        assertFalse(s.animating(1_400))
        assertEquals(Freshness.Fresh, LayerStore.freshness(s.ageMs(1_500)))
        assertEquals(Freshness.Dim, LayerStore.freshness(s.ageMs(1_700)))
        assertEquals(Freshness.Hidden, LayerStore.freshness(s.ageMs(3_200)))
    }

    @Test
    fun `a keep-alive refreshes staleness without restarting interpolation`() {
        val s = LayerSlot<String>()
        s.push("a", 1, 1_000)
        s.push("b", 2, 1_100)
        s.push("b", 2, 1_900) // same stamp: the agent's 1 Hz keep-alive of an unchanged frame
        assertEquals("a", s.prev)
        assertEquals(1f, s.fraction(1_900))
        assertEquals(Freshness.Fresh, LayerStore.freshness(s.ageMs(2_000)))
        assertEquals(Freshness.Fresh, LayerStore.freshness(s.ageMs(2_300)))
    }

    @Test
    fun `a long gap snaps instead of interpolating`() {
        val s = LayerSlot<String>()
        s.push("a", 1, 1_000)
        s.push("b", 2, 1_600)
        assertEquals(1f, s.fraction(1_600))
    }
}
