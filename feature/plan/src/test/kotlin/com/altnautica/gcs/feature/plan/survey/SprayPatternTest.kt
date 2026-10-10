package com.altnautica.gcs.feature.plan.survey

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SprayPatternTest {

    // ~100m x 100m square
    private val squareBoundary = listOf(
        SurveyLatLng(12.9716, 77.5946),
        SurveyLatLng(12.9716, 77.5956),
        SurveyLatLng(12.9725, 77.5956),
        SurveyLatLng(12.9725, 77.5946),
    )

    // Triangle
    private val triangleBoundary = listOf(
        SurveyLatLng(12.9716, 77.5946),
        SurveyLatLng(12.9716, 77.5956),
        SurveyLatLng(12.9725, 77.5951),
    )

    private val defaultConfig = SprayConfig(
        boundary = squareBoundary,
        altitude = 5f,
        speed = 3f,
        swathWidth = 4f,
    )

    @Test
    fun `square boundary generates parallel lines with takeoff and RTL`() {
        val waypoints = Spray.generate(defaultConfig)

        assertTrue("Should have waypoints", waypoints.isNotEmpty())
        // At least takeoff + some lines + RTL
        assertTrue("Should have at least 4 waypoints", waypoints.size >= 4)
    }

    @Test
    fun `first waypoint is takeoff at first boundary point`() {
        val waypoints = Spray.generate(defaultConfig)
        val first = waypoints.first()

        assertEquals(squareBoundary[0].lat, first.lat, 0.0001)
        assertEquals(squareBoundary[0].lon, first.lon, 0.0001)
        assertEquals(defaultConfig.altitude, first.alt, 0.01f)
    }

    @Test
    fun `last waypoint is RTL at first boundary point`() {
        val waypoints = Spray.generate(defaultConfig)
        val last = waypoints.last()

        assertEquals(squareBoundary[0].lat, last.lat, 0.0001)
        assertEquals(squareBoundary[0].lon, last.lon, 0.0001)
        assertEquals(defaultConfig.altitude, last.alt, 0.01f)
    }

    @Test
    fun `all waypoints use configured altitude`() {
        val waypoints = Spray.generate(defaultConfig.copy(altitude = 8f))

        for (wp in waypoints) {
            assertEquals("All waypoints should be at configured altitude", 8f, wp.alt, 0.01f)
        }
    }

    @Test
    fun `all waypoints use configured speed`() {
        val waypoints = Spray.generate(defaultConfig.copy(speed = 5f))

        for (wp in waypoints) {
            assertEquals("All waypoints should use configured speed", 5f, wp.speed, 0.01f)
        }
    }

    @Test
    fun `triangle boundary generates clipped lines`() {
        val waypoints = Spray.generate(defaultConfig.copy(boundary = triangleBoundary))

        assertTrue("Triangle should generate waypoints", waypoints.isNotEmpty())
        assertTrue("Triangle should have at least takeoff + line + RTL", waypoints.size >= 3)
    }

    @Test
    fun `empty boundary returns empty list`() {
        val waypoints = Spray.generate(defaultConfig.copy(boundary = emptyList()))
        assertTrue("Empty boundary should return empty", waypoints.isEmpty())
    }

    @Test
    fun `single point boundary returns empty list`() {
        val single = listOf(SurveyLatLng(12.9716, 77.5946))
        val waypoints = Spray.generate(defaultConfig.copy(boundary = single))
        assertTrue("Single point boundary should return empty", waypoints.isEmpty())
    }

    @Test
    fun `two point boundary returns empty list`() {
        val two = listOf(SurveyLatLng(12.9716, 77.5946), SurveyLatLng(12.9725, 77.5956))
        val waypoints = Spray.generate(defaultConfig.copy(boundary = two))
        assertTrue("Two point boundary should return empty", waypoints.isEmpty())
    }

    @Test
    fun `wider swath produces fewer waypoints`() {
        val narrowWps = Spray.generate(defaultConfig.copy(swathWidth = 2f))
        val wideWps = Spray.generate(defaultConfig.copy(swathWidth = 8f))

        assertTrue(
            "Narrow swath (${narrowWps.size}) should produce more waypoints than wide (${wideWps.size})",
            narrowWps.size > wideWps.size,
        )
    }
}
