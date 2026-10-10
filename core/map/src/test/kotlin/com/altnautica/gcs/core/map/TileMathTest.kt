package com.altnautica.gcs.core.map

import org.junit.Assert.assertEquals
import org.junit.Test

class TileMathTest {

    @Test
    fun `zoom 0 has a single tile`() {
        assertEquals(0, TileMath.lonToTileX(-179.9, 0))
        assertEquals(0, TileMath.lonToTileX(179.9, 0))
        assertEquals(0, TileMath.latToTileY(80.0, 0))
        assertEquals(0, TileMath.latToTileY(-80.0, 0))
    }

    @Test
    fun `zoom 1 splits the world into quadrants`() {
        assertEquals(0, TileMath.lonToTileX(-90.0, 1))
        assertEquals(1, TileMath.lonToTileX(90.0, 1))
        assertEquals(0, TileMath.latToTileY(45.0, 1))
        assertEquals(1, TileMath.latToTileY(-45.0, 1))
    }

    @Test
    fun `zoom 10 matches known tile coordinates`() {
        assertEquals(512, TileMath.lonToTileX(0.0, 10))
        assertEquals(512, TileMath.latToTileY(0.0, 10))
        assertEquals(550, TileMath.lonToTileX(13.4, 10))
        assertEquals(335, TileMath.latToTileY(52.5, 10))
    }

    @Test
    fun `indices are clamped to the grid at the edges`() {
        assertEquals(3, TileMath.lonToTileX(180.0, 2))
        assertEquals(0, TileMath.latToTileY(90.0, 2))
        assertEquals(3, TileMath.latToTileY(-90.0, 2))
    }

    @Test
    fun `whole world across zoom 0 to 2 is 21 tiles`() {
        val world = TileBounds(minLat = -85.0, maxLat = 85.0, minLon = -179.9, maxLon = 179.9)

        val estimate = TileMath.estimate(world, minZoom = 0, maxZoom = 2, avgTileKB = 25)

        assertEquals(21L, estimate.tileCount)
        assertEquals(21L * 25 * 1024, estimate.estimatedBytes)
    }

    @Test
    fun `small area count sums each zoom level`() {
        val area = TileBounds(minLat = 12.0, maxLat = 12.1, minLon = 77.0, maxLon = 77.1)

        // One tile at zoom 10, a 1x2 block at zoom 11, a 2x2 block at zoom 12.
        assertEquals(1L, TileMath.estimate(area, 10, 10, 20).tileCount)
        assertEquals(2L, TileMath.estimate(area, 11, 11, 20).tileCount)
        assertEquals(4L, TileMath.estimate(area, 12, 12, 20).tileCount)
        assertEquals(7L, TileMath.estimate(area, 10, 12, 20).tileCount)
    }

    @Test
    fun `y range is ordered north to south`() {
        assertEquals(477..477, TileMath.tileYRange(12.0, 12.1, 10))
        assertEquals(1909..1910, TileMath.tileYRange(12.0, 12.1, 12))
        assertEquals(listOf(2924..2925), TileMath.tileXRanges(77.0, 77.1, 12))
    }

    @Test
    fun `box across the antimeridian is split in two`() {
        // 170E to 170W at zoom 2: columns 3 (east edge of the world) and 0 (west edge).
        val ranges = TileMath.tileXRanges(170.0, -170.0, 2)

        assertEquals(listOf(0..0, 3..3), ranges)
        val box = TileBounds(minLat = -10.0, maxLat = 10.0, minLon = 170.0, maxLon = -170.0)
        assertEquals(4L, TileMath.tileCount(box, 2))
    }

    @Test
    fun `provider estimate uses its tile size and caps zoom at its maximum`() {
        val area = TileBounds(minLat = 12.0, maxLat = 12.1, minLon = 77.0, maxLon = 77.1)

        val topo = TileMath.estimate(area, 10, 25, MapProvider.OpenTopoMap)
        val expectedCount = TileMath.estimate(area, 10, MapProvider.OpenTopoMap.maxZoom, 1).tileCount

        assertEquals(expectedCount, topo.tileCount)
        assertEquals(expectedCount * MapProvider.OpenTopoMap.avgTileKB * 1024, topo.estimatedBytes)
    }

    @Test
    fun `size in megabytes derives from bytes`() {
        assertEquals(1f, TileEstimate(tileCount = 41, estimatedBytes = 1024L * 1024L).estimatedSizeMb, 1e-6f)
    }
}
