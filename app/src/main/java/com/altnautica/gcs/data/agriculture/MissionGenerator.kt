package com.altnautica.gcs.data.agriculture

import com.altnautica.gcs.ui.agriculture.LatLon
import com.altnautica.gcs.ui.agriculture.SprayConfig
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

data class Waypoint(val lat: Double, val lon: Double, val alt: Float, val speed: Float)

/**
 * Generates a lawn-mower spray pattern from a field boundary polygon
 * and spray configuration. The pattern consists of parallel passes
 * spaced at swathWidth intervals, alternating direction on each row.
 */
object MissionGenerator {

    private const val METERS_PER_DEG_LAT = 111_320.0

    fun generateSprayMission(
        boundary: List<LatLon>,
        config: SprayConfig,
    ): List<Waypoint> {
        if (boundary.size < 3) return emptyList()

        val minLat = boundary.minOf { it.lat }
        val maxLat = boundary.maxOf { it.lat }
        val minLon = boundary.minOf { it.lon }
        val maxLon = boundary.maxOf { it.lon }

        val refLat = (minLat + maxLat) / 2.0
        val metersPerDegLon = METERS_PER_DEG_LAT * cos(Math.toRadians(refLat))

        val latSpanM = (maxLat - minLat) * METERS_PER_DEG_LAT
        val lonSpanM = (maxLon - minLon) * metersPerDegLon

        val waypoints = mutableListOf<Waypoint>()

        // Takeoff: first boundary point at configured altitude
        waypoints.add(
            Waypoint(
                lat = boundary[0].lat,
                lon = boundary[0].lon,
                alt = config.altitude,
                speed = config.speed,
            )
        )

        // Determine sweep axis: sweep along the longer dimension
        val sweepAlongLon = lonSpanM >= latSpanM
        val swathDeg: Double
        val sweepLines: Int

        if (sweepAlongLon) {
            // Lines run east-west, step north-south
            swathDeg = config.swathWidth / METERS_PER_DEG_LAT
            sweepLines = max(1, (latSpanM / config.swathWidth).toInt())

            for (i in 0..sweepLines) {
                val lat = minLat + i * swathDeg
                if (lat > maxLat) break

                // Find lon intersection with bounding box (simplified)
                val startLon: Double
                val endLon: Double
                if (i % 2 == 0) {
                    startLon = minLon
                    endLon = maxLon
                } else {
                    startLon = maxLon
                    endLon = minLon
                }

                // Clip to polygon using ray-cast intersection with boundary edges
                val clipped = clipLineToBoundary(lat, minLon, maxLon, boundary)
                if (clipped != null) {
                    val (cMinLon, cMaxLon) = clipped
                    val wp1Lon = if (i % 2 == 0) cMinLon else cMaxLon
                    val wp2Lon = if (i % 2 == 0) cMaxLon else cMinLon
                    waypoints.add(Waypoint(lat, wp1Lon, config.altitude, config.speed))
                    waypoints.add(Waypoint(lat, wp2Lon, config.altitude, config.speed))
                }
            }
        } else {
            // Lines run north-south, step east-west
            swathDeg = config.swathWidth / metersPerDegLon
            sweepLines = max(1, (lonSpanM / config.swathWidth).toInt())

            for (i in 0..sweepLines) {
                val lon = minLon + i * swathDeg
                if (lon > maxLon) break

                val clipped = clipVerticalLineToBoundary(lon, minLat, maxLat, boundary)
                if (clipped != null) {
                    val (cMinLat, cMaxLat) = clipped
                    val wp1Lat = if (i % 2 == 0) cMinLat else cMaxLat
                    val wp2Lat = if (i % 2 == 0) cMaxLat else cMinLat
                    waypoints.add(Waypoint(wp1Lat, lon, config.altitude, config.speed))
                    waypoints.add(Waypoint(wp2Lat, lon, config.altitude, config.speed))
                }
            }
        }

        // RTL: return to first boundary point
        waypoints.add(
            Waypoint(
                lat = boundary[0].lat,
                lon = boundary[0].lon,
                alt = config.altitude,
                speed = config.speed,
            )
        )

        return waypoints
    }

    /**
     * Clip a horizontal line (constant lat) to the boundary polygon.
     * Returns the min/max lon range where the line is inside the polygon,
     * or null if the line doesn't intersect.
     */
    private fun clipLineToBoundary(
        lat: Double,
        rangeLonMin: Double,
        rangeLonMax: Double,
        boundary: List<LatLon>,
    ): Pair<Double, Double>? {
        val intersections = mutableListOf<Double>()
        val n = boundary.size
        for (i in 0 until n) {
            val a = boundary[i]
            val b = boundary[(i + 1) % n]
            if ((a.lat <= lat && b.lat > lat) || (b.lat <= lat && a.lat > lat)) {
                val t = (lat - a.lat) / (b.lat - a.lat)
                val lon = a.lon + t * (b.lon - a.lon)
                if (lon in rangeLonMin..rangeLonMax) {
                    intersections.add(lon)
                }
            }
        }
        if (intersections.size < 2) return null
        return Pair(intersections.min(), intersections.max())
    }

    /**
     * Clip a vertical line (constant lon) to the boundary polygon.
     * Returns the min/max lat range where the line is inside the polygon,
     * or null if the line doesn't intersect.
     */
    private fun clipVerticalLineToBoundary(
        lon: Double,
        rangeLatMin: Double,
        rangeLatMax: Double,
        boundary: List<LatLon>,
    ): Pair<Double, Double>? {
        val intersections = mutableListOf<Double>()
        val n = boundary.size
        for (i in 0 until n) {
            val a = boundary[i]
            val b = boundary[(i + 1) % n]
            if ((a.lon <= lon && b.lon > lon) || (b.lon <= lon && a.lon > lon)) {
                val t = (lon - a.lon) / (b.lon - a.lon)
                val lat = a.lat + t * (b.lat - a.lat)
                if (lat in rangeLatMin..rangeLatMax) {
                    intersections.add(lat)
                }
            }
        }
        if (intersections.size < 2) return null
        return Pair(intersections.min(), intersections.max())
    }
}
