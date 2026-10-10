package com.altnautica.gcs.core.map

import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * Geographic bounding box in degrees. A box that crosses the antimeridian has
 * [maxLon] < [minLon] (west edge east of the east edge) or [maxLon] > 180.
 */
data class TileBounds(
    val minLat: Double,
    val maxLat: Double,
    val minLon: Double,
    val maxLon: Double,
)

/** Number of slippy-map tiles covering an area and their approximate download size. */
data class TileEstimate(
    val tileCount: Long,
    val estimatedBytes: Long,
) {
    val estimatedSizeMb: Float get() = estimatedBytes / (1024f * 1024f)
}

/** Web-Mercator slippy-map tile arithmetic (z/x/y, origin at the north-west corner). */
object TileMath {

    /** Latitude limit of the Web-Mercator tile grid. */
    const val MAX_MERCATOR_LAT = 85.0511287798066

    /** Tile column for [lon] at [zoom], clamped to the grid. */
    fun lonToTileX(lon: Double, zoom: Int): Int {
        val n = 1 shl zoom
        return floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
    }

    /** Tile row for [lat] at [zoom], clamped to the Web-Mercator grid. */
    fun latToTileY(lat: Double, zoom: Int): Int {
        val n = 1 shl zoom
        val latRad = Math.toRadians(lat.coerceIn(-MAX_MERCATOR_LAT, MAX_MERCATOR_LAT))
        val y = floor((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / Math.PI) / 2.0 * n).toInt()
        return y.coerceIn(0, n - 1)
    }

    /**
     * Tile column ranges covering [minLon]..[maxLon]. A box crossing the antimeridian
     * is wrapped back onto the world and split in two at 180 degrees.
     */
    fun tileXRanges(minLon: Double, maxLon: Double, zoom: Int): List<IntRange> {
        val n = 1 shl zoom
        val east = if (maxLon < minLon) maxLon + 360.0 else maxLon
        val span = east - minLon
        if (span >= 360.0) return listOf(0..n - 1)
        val west = ((minLon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
        val wrappedEast = west + span
        if (wrappedEast <= 180.0) return listOf(lonToTileX(west, zoom)..lonToTileX(wrappedEast, zoom))
        val first = lonToTileX(west, zoom)..n - 1
        val second = 0..lonToTileX(wrappedEast - 360.0, zoom)
        // Near-world spans can make the two pieces meet; merge rather than overlap.
        return if (second.last >= first.first - 1) listOf(0..n - 1) else listOf(second, first)
    }

    fun tileYRange(minLat: Double, maxLat: Double, zoom: Int): IntRange {
        // Tile y grows southwards, so the northern edge gives the smaller y.
        val y1 = latToTileY(maxLat, zoom)
        val y2 = latToTileY(minLat, zoom)
        return minOf(y1, y2)..maxOf(y1, y2)
    }

    /** Tiles covering [bounds] at one [zoom]. */
    fun tileCount(bounds: TileBounds, zoom: Int): Long {
        val columns = tileXRanges(bounds.minLon, bounds.maxLon, zoom).sumOf { (it.last - it.first + 1).toLong() }
        val y = tileYRange(bounds.minLat, bounds.maxLat, zoom)
        return columns * (y.last - y.first + 1)
    }

    /** Tile count and estimated size for [bounds] over every zoom in [minZoom]..[maxZoom]. */
    fun estimate(bounds: TileBounds, minZoom: Int, maxZoom: Int, avgTileKB: Int): TileEstimate {
        var count = 0L
        for (z in minZoom..maxZoom) count += tileCount(bounds, z)
        return TileEstimate(tileCount = count, estimatedBytes = count * avgTileKB * 1024L)
    }

    /** Estimate for downloading [bounds] from [provider]. */
    fun estimate(bounds: TileBounds, minZoom: Int, maxZoom: Int, provider: MapProvider): TileEstimate =
        estimate(bounds, minZoom, maxZoom.coerceAtMost(provider.maxZoom), provider.avgTileKB)
}
