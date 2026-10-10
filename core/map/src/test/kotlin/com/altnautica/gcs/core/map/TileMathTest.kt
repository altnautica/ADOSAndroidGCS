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
    fun `whole world across zoom 0 to 2 is 21 tiles`() {
        val world = TileBounds(minLat = -85.0, maxLat = 85.0, minLon = -179.9, maxLon = 179.9)

        val estimate = TileMath.estimate(world, minZoom = 0, maxZoom = 2)

        assertEquals(1 + 4 + 16, estimate.tileCount)
        assertEquals(21 * TileMath.AVG_TILE_SIZE_KB / 1024f, estimate.estimatedSizeMb, 1e-6f)
    }

    @Test
    fun `small area count sums each zoom level`() {
        val area = TileBounds(minLat = 12.0, maxLat = 12.1, minLon = 77.0, maxLon = 77.1)

        // One tile at zoom 10, a 1x2 block at zoom 11, a 2x2 block at zoom 12.
        assertEquals(1, TileMath.estimate(area, 10, 10).tileCount)
        assertEquals(2, TileMath.estimate(area, 11, 11).tileCount)
        assertEquals(4, TileMath.estimate(area, 12, 12).tileCount)
        assertEquals(7, TileMath.estimate(area, 10, 12).tileCount)
    }

    @Test
    fun `y range is ordered north to south`() {
        assertEquals(477..477, TileMath.tileYRange(12.0, 12.1, 10))
        assertEquals(1909..1910, TileMath.tileYRange(12.0, 12.1, 12))
        assertEquals(2924..2925, TileMath.tileXRange(77.0, 77.1, 12))
    }
}
