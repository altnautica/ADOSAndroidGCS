package com.altnautica.gcs

import com.altnautica.gcs.data.agriculture.MissionGenerator
import com.altnautica.gcs.ui.agriculture.LatLon
import com.altnautica.gcs.ui.agriculture.SprayConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MissionGeneratorTest {

    private val defaultConfig = SprayConfig(
        cropType = "Rice",
        chemical = "Pesticide",
        ratePerAcre = 10f,
        altitude = 5f,
        speed = 3f,
        swathWidth = 4f,
    )

    // ~100m x 100m square near Bangalore
    private val squareBoundary = listOf(
        LatLon(12.9716, 77.5946),
        LatLon(12.9716, 77.5956),
        LatLon(12.9725, 77.5956),
        LatLon(12.9725, 77.5946),
    )

    // Triangle
    private val triangleBoundary = listOf(
        LatLon(12.9716, 77.5946),
        LatLon(12.9716, 77.5956),
        LatLon(12.9725, 77.5951),
    )

    @Test
    fun `square boundary generates parallel lines with takeoff and RTL`() {
        val waypoints = MissionGenerator.generateSprayMission(squareBoundary, defaultConfig)

        assertTrue("Should have waypoints", waypoints.isNotEmpty())
        // At least takeoff + some lines + RTL
        assertTrue("Should have at least 4 waypoints", waypoints.size >= 4)
    }

    @Test
    fun `first waypoint is takeoff at first boundary point`() {
        val waypoints = MissionGenerator.generateSprayMission(squareBoundary, defaultConfig)
        val first = waypoints.first()

        assertEquals(squareBoundary[0].lat, first.lat, 0.0001)
        assertEquals(squareBoundary[0].lon, first.lon, 0.0001)
        assertEquals(defaultConfig.altitude, first.alt, 0.01f)
    }

    @Test
    fun `last waypoint is RTL at first boundary point`() {
        val waypoints = MissionGenerator.generateSprayMission(squareBoundary, defaultConfig)
        val last = waypoints.last()

        assertEquals(squareBoundary[0].lat, last.lat, 0.0001)
        assertEquals(squareBoundary[0].lon, last.lon, 0.0001)
        assertEquals(defaultConfig.altitude, last.alt, 0.01f)
    }

    @Test
    fun `all waypoints use configured altitude`() {
        val config = defaultConfig.copy(altitude = 8f)
        val waypoints = MissionGenerator.generateSprayMission(squareBoundary, config)

        for (wp in waypoints) {
            assertEquals("All waypoints should be at configured altitude", 8f, wp.alt, 0.01f)
        }
    }

    @Test
    fun `all waypoints use configured speed`() {
        val config = defaultConfig.copy(speed = 5f)
        val waypoints = MissionGenerator.generateSprayMission(squareBoundary, config)

        for (wp in waypoints) {
            assertEquals("All waypoints should use configured speed", 5f, wp.speed, 0.01f)
        }
    }

    @Test
    fun `triangle boundary generates clipped lines`() {
        val waypoints = MissionGenerator.generateSprayMission(triangleBoundary, defaultConfig)

        assertTrue("Triangle should generate waypoints", waypoints.isNotEmpty())
        // Triangle is smaller, so fewer lines than square
        assertTrue("Triangle should have at least takeoff + line + RTL", waypoints.size >= 3)
    }

    @Test
    fun `empty boundary returns empty list`() {
        val waypoints = MissionGenerator.generateSprayMission(emptyList(), defaultConfig)
        assertTrue("Empty boundary should return empty", waypoints.isEmpty())
    }

    @Test
    fun `single point boundary returns empty list`() {
        val single = listOf(LatLon(12.9716, 77.5946))
        val waypoints = MissionGenerator.generateSprayMission(single, defaultConfig)
        assertTrue("Single point boundary should return empty", waypoints.isEmpty())
    }

    @Test
    fun `two point boundary returns empty list`() {
        val two = listOf(LatLon(12.9716, 77.5946), LatLon(12.9725, 77.5956))
        val waypoints = MissionGenerator.generateSprayMission(two, defaultConfig)
        assertTrue("Two point boundary should return empty", waypoints.isEmpty())
    }

    @Test
    fun `wider swath produces fewer waypoints`() {
        val narrow = defaultConfig.copy(swathWidth = 2f)
        val wide = defaultConfig.copy(swathWidth = 8f)

        val narrowWps = MissionGenerator.generateSprayMission(squareBoundary, narrow)
        val wideWps = MissionGenerator.generateSprayMission(squareBoundary, wide)

        assertTrue(
            "Narrow swath (${ narrowWps.size}) should produce more waypoints than wide (${wideWps.size})",
            narrowWps.size > wideWps.size,
        )
    }
}
