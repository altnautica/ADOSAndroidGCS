package com.altnautica.gcs.feature.plan.survey

import kotlin.math.cos
import kotlin.math.max

/**
 * Input for the spray preset.
 *
 * @property boundary field boundary polygon; fewer than three vertices yields no mission.
 * @property altitude spray altitude in metres above takeoff.
 * @property speed ground speed in metres per second.
 * @property swathWidth effective spray width of one pass, in metres.
 */
data class SprayConfig(
    val boundary: List<SurveyLatLng>,
    val altitude: Float,
    val speed: Float,
    val swathWidth: Float,
)

/**
 * Lawn-mower spray pattern over a field boundary.
 *
 * Passes are spaced one swath apart along the longer side of the field's
 * bounding box, clipped to the boundary polygon, and alternate direction on
 * each row. The mission starts and ends over the first boundary vertex.
 */
object Spray {

    private const val METERS_PER_DEG_LAT = 111_320.0

    fun generate(config: SprayConfig): List<Waypoint> {
        val boundary = config.boundary
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
        waypoints.add(Waypoint(boundary[0].lat, boundary[0].lon, config.altitude, config.speed))

        // Sweep along the longer dimension
        if (lonSpanM >= latSpanM) {
            // Lines run east-west, step north-south
            val swathDeg = config.swathWidth / METERS_PER_DEG_LAT
            val sweepLines = max(1, (latSpanM / config.swathWidth).toInt())

            for (i in 0..sweepLines) {
                val lat = minLat + i * swathDeg
                if (lat > maxLat) break

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
            val swathDeg = config.swathWidth / metersPerDegLon
            val sweepLines = max(1, (lonSpanM / config.swathWidth).toInt())

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

        // Return over the first boundary point
        waypoints.add(Waypoint(boundary[0].lat, boundary[0].lon, config.altitude, config.speed))

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
        boundary: List<SurveyLatLng>,
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
        boundary: List<SurveyLatLng>,
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
