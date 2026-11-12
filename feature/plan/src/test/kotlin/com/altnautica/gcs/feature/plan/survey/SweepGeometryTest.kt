package com.altnautica.gcs.feature.plan.survey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.tan

class SweepGeometryTest {

    private val mPerDegLat = 111_320.0
    private val mPerDegLon = mPerDegLat * cos(Math.toRadians(12.0))

    /** Square field ~220 m a side at 12° N. */
    private val square = listOf(
        SurveyLatLng(12.000, 77.000), SurveyLatLng(12.000, 77.002), SurveyLatLng(12.002, 77.002), SurveyLatLng(12.002, 77.000),
    )

    /** U-shaped field open to the north: arms at lon 77.000–77.001 and 77.002–77.003. */
    private val u = listOf(
        SurveyLatLng(12.000, 77.000), SurveyLatLng(12.000, 77.003), SurveyLatLng(12.003, 77.003), SurveyLatLng(12.003, 77.002),
        SurveyLatLng(12.001, 77.002), SurveyLatLng(12.001, 77.001), SurveyLatLng(12.003, 77.001), SurveyLatLng(12.003, 77.000),
    )

    private fun inNotch(lat: Double, lon: Double) = lat > 12.001 + 1e-9 && lon > 77.001 + 1e-9 && lon < 77.002 - 1e-9

    /** A U wider than tall, so spray lines run east-west across its notch (lon 77.0015–77.0025). */
    private val wideU = listOf(
        SurveyLatLng(12.000, 77.000), SurveyLatLng(12.000, 77.004), SurveyLatLng(12.003, 77.004), SurveyLatLng(12.003, 77.0025),
        SurveyLatLng(12.001, 77.0025), SurveyLatLng(12.001, 77.0015), SurveyLatLng(12.003, 77.0015), SurveyLatLng(12.003, 77.000),
    )

    private fun inWideNotch(lat: Double, lon: Double) = lat > 12.001 + 1e-9 && lon > 77.0015 + 1e-9 && lon < 77.0025 - 1e-9

    @Test
    fun `grid angle is a compass bearing - 0 gives north-south lines`() {
        val ns = generateSurveyGrid(SurveyConfig(square, angle = 0f)).waypoints
        val (a, b) = ns[0] to ns[1]
        assertEquals("a 0° line keeps its longitude", a.lon, b.lon, 1e-7)
        assertTrue(abs(a.lat - b.lat) > 0.001)

        val ew = generateSurveyGrid(SurveyConfig(square, angle = 90f)).waypoints
        assertEquals("a 90° line keeps its latitude", ew[0].lat, ew[1].lat, 1e-7)
    }

    @Test
    fun `survey lines are split around a concave notch`() {
        val wps = generateSurveyGrid(SurveyConfig(u, altitude = 30f, angle = 90f)).waypoints
        assertTrue(wps.isNotEmpty())
        wps.zipWithNext().forEachIndexed { i, (p, q) ->
            if (i % 2 != 0) return@forEachIndexed // transits between passes
            val midLat = (p.lat + q.lat) / 2
            val midLon = (p.lon + q.lon) / 2
            assertFalse("pass $i crosses the notch", inNotch(midLat, midLon))
        }
    }

    @Test
    fun `spray passes stay inside the notch of a concave field`() {
        val passes = Spray.generate(SprayConfig(wideU, altitude = 3f, speed = 5f, swathWidth = 20f)).drop(1).dropLast(1)
        assertTrue(passes.size >= 4)
        passes.chunked(2).forEach { (p, q) ->
            assertEquals("spray lines run east-west here", p.lat, q.lat, 1e-9)
            assertFalse("spray pass crosses the notch", inWideNotch((p.lat + q.lat) / 2, (p.lon + q.lon) / 2))
        }
    }

    @Test
    fun `spray passes are centred half a swath inside the edge and never zero length`() {
        val swath = 20f
        val wide = listOf(SurveyLatLng(12.000, 77.000), SurveyLatLng(12.000, 77.004), SurveyLatLng(12.002, 77.004), SurveyLatLng(12.002, 77.000))
        val wps = Spray.generate(SprayConfig(wide, altitude = 3f, speed = 5f, swathWidth = swath))
        val passes = wps.drop(1).dropLast(1)
        // Wider than tall: lines run east-west, stepping north.
        val lats = passes.map { it.lat }.distinct().sorted()
        val firstInsetM = (lats.first() - 12.000) * mPerDegLat
        val lastInsetM = (12.002 - lats.last()) * mPerDegLat
        assertTrue("first pass inset $firstInsetM", firstInsetM in 0.0..swath / 2.0 + 0.01)
        assertTrue(firstInsetM > 1.0)
        assertEquals(firstInsetM, lastInsetM, 0.05)
        passes.chunked(2).forEach { (p, q) -> assertTrue(abs(p.lon - q.lon) * mPerDegLon > 0.01) }
    }

    @Test
    fun `photo interval follows the along-track field of view`() {
        val result = generateSurveyGrid(SurveyConfig(square, altitude = 100f, overlapPercent = 70f, cameraFovDeg = 73f, cameraVFovDeg = 58f))
        val along = 2 * 100 * tan(Math.toRadians(29.0))
        assertEquals((along * 0.3).toFloat(), result.photoIntervalM, 0.01f)
    }
}
