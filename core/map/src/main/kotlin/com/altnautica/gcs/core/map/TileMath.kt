package com.altnautica.gcs.core.map

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/** Geographic bounding box in degrees. */
data class TileBounds(
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
)

/** Number of slippy-map tiles covering an area and their approximate download size. */
data class TileEstimate(
    val tileCount: Int,
    val estimatedSizeMb: Float,
)

/** Web-Mercator slippy-map tile arithmetic (z/x/y, origin at the north-west corner). */
object TileMath {

    /** Average raster tile size used for download size estimates. */
    const val AVG_TILE_SIZE_KB = 15

    fun lonToTileX(lon: Double, zoom: Int): Int =
        floor((lon + 180.0) / 360.0 * (1 shl zoom).toDouble()).toInt()

    fun latToTileY(lat: Double, zoom: Int): Int {
        val latRad = Math.toRadians(lat)
        val n = (1 shl zoom).toDouble()
        return floor((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / Math.PI) / 2.0 * n).toInt()
    }

    fun tileXRange(minLon: Double, maxLon: Double, zoom: Int): IntRange {
        val x1 = lonToTileX(minLon, zoom)
        val x2 = lonToTileX(maxLon, zoom)
        return minOf(x1, x2)..maxOf(x1, x2)
    }

    fun tileYRange(minLat: Double, maxLat: Double, zoom: Int): IntRange {
        // Tile y grows southwards, so the northern edge gives the smaller y.
        val y1 = latToTileY(maxLat, zoom)
        val y2 = latToTileY(minLat, zoom)
        return minOf(y1, y2)..maxOf(y1, y2)
    }

    /** Tile count and estimated size for [bounds] over every zoom in [minZoom]..[maxZoom]. */
    fun estimate(bounds: TileBounds, minZoom: Int, maxZoom: Int): TileEstimate {
        var count = 0
        for (z in minZoom..maxZoom) {
            val xRange = tileXRange(bounds.minLon, bounds.maxLon, z)
            val yRange = tileYRange(bounds.minLat, bounds.maxLat, z)
            count += (xRange.last - xRange.first + 1) * (yRange.last - yRange.first + 1)
        }
        val sizeMb = count * AVG_TILE_SIZE_KB / 1024f
        return TileEstimate(count, sizeMb)
    }
}
