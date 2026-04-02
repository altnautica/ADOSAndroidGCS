package com.altnautica.gcs.data.mission

import com.altnautica.gcs.data.agriculture.Waypoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Coordinate pair for survey polygon vertices.
 * Separate from agriculture LatLon to avoid coupling the mission package
 * to the agriculture UI package.
 */
data class SurveyLatLng(val lat: Double, val lon: Double)

data class SurveyConfig(
    val polygon: List<SurveyLatLng>,
    val altitude: Float = 50f,
    val overlapPercent: Float = 70f,
    val sidelapPercent: Float = 60f,
    val cameraFovDeg: Float = 73f,
    val speed: Float = 5f,
    val angle: Float = 0f,
)

data class SurveyResult(
    val waypoints: List<SurveyWaypoint>,
    val photoIntervalM: Float,
    val estimatedFlightTimeSec: Int,
    val totalDistanceM: Float,
    val lineCount: Int,
    val coverageHectares: Float,
)

data class SurveyWaypoint(
    val lat: Double,
    val lon: Double,
    val alt: Float,
    val speed: Float,
    val isCameraTrigger: Boolean = false,
)

private const val METERS_PER_DEG_LAT = 111_320.0
private const val EARTH_RADIUS_M = 6_371_000.0

/**
 * Generates an aerial survey grid (boustrophedon pattern) for a given polygon.
 *
 * Algorithm:
 * 1. Compute sensor footprint from altitude and camera FOV.
 * 2. Derive line spacing (sidelap) and photo interval (forward overlap).
 * 3. Rotate polygon so grid lines align with the requested angle.
 * 4. Generate horizontal sweep lines across the rotated bounding box.
 * 5. Clip each line to the rotated polygon boundary (ray-cast).
 * 6. Alternate line direction for efficient boustrophedon traversal.
 * 7. Rotate waypoints back to real-world coordinates.
 * 8. Compute stats: total distance, flight time, coverage area.
 */
fun generateSurveyGrid(config: SurveyConfig): SurveyResult {
    require(config.polygon.size >= 3) { "Polygon must have at least 3 vertices" }

    // Sensor footprint (assume square sensor)
    val fovRad = config.cameraFovDeg * PI / 180.0
    val footprintWidth = (2.0 * config.altitude * tan(fovRad / 2.0)).toFloat()

    // Spacing and photo interval
    val lineSpacing = footprintWidth * (1f - config.sidelapPercent / 100f)
    val photoInterval = footprintWidth * (1f - config.overlapPercent / 100f)

    // Reference point for local meter projection (centroid)
    val refLat = config.polygon.map { it.lat }.average()
    val refLon = config.polygon.map { it.lon }.average()
    val metersPerDegLon = METERS_PER_DEG_LAT * cos(Math.toRadians(refLat))

    // Convert polygon to local meter coordinates relative to centroid
    val polyM = config.polygon.map { pt ->
        doubleArrayOf(
            (pt.lon - refLon) * metersPerDegLon,
            (pt.lat - refLat) * METERS_PER_DEG_LAT,
        )
    }

    // Rotate polygon so grid lines run horizontally (angle=0 in rotated frame)
    val angleRad = -config.angle * PI / 180.0
    val cosA = cos(angleRad)
    val sinA = sin(angleRad)

    val rotated = polyM.map { pt ->
        doubleArrayOf(
            pt[0] * cosA - pt[1] * sinA,
            pt[0] * sinA + pt[1] * cosA,
        )
    }

    // Bounding box in rotated frame
    val minX = rotated.minOf { it[0] }
    val maxX = rotated.maxOf { it[0] }
    val minY = rotated.minOf { it[1] }
    val maxY = rotated.maxOf { it[1] }

    // Generate horizontal sweep lines
    val surveyWaypoints = mutableListOf<SurveyWaypoint>()
    var lineIndex = 0
    var y = minY + lineSpacing / 2.0

    while (y <= maxY) {
        val intersections = clipHorizontalLine(y, minX, maxX, rotated)
        if (intersections != null) {
            val (xStart, xEnd) = intersections
            // Alternate direction for boustrophedon
            val x1 = if (lineIndex % 2 == 0) xStart else xEnd
            val x2 = if (lineIndex % 2 == 0) xEnd else xStart

            // Rotate back to meter coords, then to lat/lon
            val wp1 = rotateBack(x1, y, cosA, sinA, refLat, refLon, metersPerDegLon)
            val wp2 = rotateBack(x2, y, cosA, sinA, refLat, refLon, metersPerDegLon)

            surveyWaypoints.add(
                SurveyWaypoint(wp1.first, wp1.second, config.altitude, config.speed, isCameraTrigger = true)
            )
            surveyWaypoints.add(
                SurveyWaypoint(wp2.first, wp2.second, config.altitude, config.speed, isCameraTrigger = true)
            )
            lineIndex++
        }
        y += lineSpacing
    }

    // Compute stats
    val totalDistanceM = computeTotalDistance(surveyWaypoints)
    val estimatedTimeSec = if (config.speed > 0f) (totalDistanceM / config.speed).toInt() else 0
    val coverageHa = polygonAreaHectares(config.polygon)

    return SurveyResult(
        waypoints = surveyWaypoints,
        photoIntervalM = photoInterval,
        estimatedFlightTimeSec = estimatedTimeSec,
        totalDistanceM = totalDistanceM,
        lineCount = lineIndex,
        coverageHectares = coverageHa,
    )
}

/**
 * Clip a horizontal line (constant y) to a polygon in meter coordinates.
 * Returns the min/max x range inside the polygon, or null if no intersection.
 */
private fun clipHorizontalLine(
    y: Double,
    rangeXMin: Double,
    rangeXMax: Double,
    polygon: List<DoubleArray>,
): Pair<Double, Double>? {
    val intersections = mutableListOf<Double>()
    val n = polygon.size
    for (i in 0 until n) {
        val a = polygon[i]
        val b = polygon[(i + 1) % n]
        val ay = a[1]
        val by = b[1]
        if ((ay <= y && by > y) || (by <= y && ay > y)) {
            val t = (y - ay) / (by - ay)
            val x = a[0] + t * (b[0] - a[0])
            if (x in rangeXMin..rangeXMax) {
                intersections.add(x)
            }
        }
    }
    if (intersections.size < 2) return null
    return Pair(intersections.min(), intersections.max())
}

/** Rotate point back from grid-aligned frame to meter frame, then convert to lat/lon. */
private fun rotateBack(
    x: Double,
    y: Double,
    cosA: Double,
    sinA: Double,
    refLat: Double,
    refLon: Double,
    metersPerDegLon: Double,
): Pair<Double, Double> {
    // Inverse rotation (negate angle, so swap sin sign)
    val mx = x * cosA + y * sinA
    val my = -x * sinA + y * cosA
    val lat = refLat + my / METERS_PER_DEG_LAT
    val lon = refLon + mx / metersPerDegLon
    return Pair(lat, lon)
}

/** Haversine distance between two points in meters. */
private fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
        sin(dLon / 2) * sin(dLon / 2)
    return EARTH_RADIUS_M * 2.0 * atan2(sqrt(a), sqrt(1 - a))
}

/** Sum of haversine segments between consecutive waypoints. */
private fun computeTotalDistance(waypoints: List<SurveyWaypoint>): Float {
    if (waypoints.size < 2) return 0f
    var total = 0.0
    for (i in 0 until waypoints.size - 1) {
        total += haversineM(
            waypoints[i].lat, waypoints[i].lon,
            waypoints[i + 1].lat, waypoints[i + 1].lon,
        )
    }
    return total.toFloat()
}

/** Shoelace formula for polygon area, converted to hectares. */
private fun polygonAreaHectares(polygon: List<SurveyLatLng>): Float {
    val refLat = polygon.map { it.lat }.average()
    val metersPerDegLon = METERS_PER_DEG_LAT * cos(Math.toRadians(refLat))
    val n = polygon.size
    var area = 0.0
    for (i in 0 until n) {
        val j = (i + 1) % n
        val xi = (polygon[i].lon - polygon[0].lon) * metersPerDegLon
        val yi = (polygon[i].lat - polygon[0].lat) * METERS_PER_DEG_LAT
        val xj = (polygon[j].lon - polygon[0].lon) * metersPerDegLon
        val yj = (polygon[j].lat - polygon[0].lat) * METERS_PER_DEG_LAT
        area += xi * yj - xj * yi
    }
    return (abs(area) / 2.0 / 10_000.0).toFloat()
}

/** Convert SurveyWaypoint list to the Waypoint type used by MissionUploader. */
fun SurveyResult.toMissionWaypoints(): List<Waypoint> =
    waypoints.map { Waypoint(it.lat, it.lon, it.alt, it.speed) }
