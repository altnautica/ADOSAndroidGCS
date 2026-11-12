package com.altnautica.gcs.feature.plan.survey

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Coordinate pair for survey and spray polygon vertices. */
data class SurveyLatLng(val lat: Double, val lon: Double)

/**
 * @property cameraFovDeg field of view across track (the sensor's long side); sets line spacing.
 * @property cameraVFovDeg field of view along track (the short side); sets the photo interval.
 * @property angle compass bearing of the survey lines: 0° runs them north–south, 90° east–west.
 */
data class SurveyConfig(
    val polygon: List<SurveyLatLng>,
    val altitude: Float = 50f,
    val overlapPercent: Float = 70f,
    val sidelapPercent: Float = 60f,
    val cameraFovDeg: Float = 73f,
    val cameraVFovDeg: Float = 58f,
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
 * 1. Footprint across track from the horizontal FOV, along track from the vertical FOV.
 * 2. Line spacing from the side overlap, photo interval from the forward overlap.
 * 3. Rotate the polygon so lines at the requested bearing lie along the local x axis.
 * 4. Centre the lines across the rotated height and clip each to every inside span.
 * 5. Alternate direction per line, then rotate the passes back to latitude/longitude.
 */
fun generateSurveyGrid(config: SurveyConfig): SurveyResult {
    require(config.polygon.size >= 3) { "Polygon must have at least 3 vertices" }

    val footprintAcross = footprintM(config.altitude, config.cameraFovDeg)
    val footprintAlong = footprintM(config.altitude, config.cameraVFovDeg)
    val lineSpacing = footprintAcross * (1f - config.sidelapPercent / 100f)
    val photoInterval = footprintAlong * (1f - config.overlapPercent / 100f)

    // Local metre frame around the centroid.
    val refLat = config.polygon.map { it.lat }.average()
    val refLon = config.polygon.map { it.lon }.average()
    val metersPerDegLon = METERS_PER_DEG_LAT * cos(Math.toRadians(refLat))
    val polyM = config.polygon.map { pt ->
        doubleArrayOf((pt.lon - refLon) * metersPerDegLon, (pt.lat - refLat) * METERS_PER_DEG_LAT)
    }

    // Lines at compass bearing B run (90 - B)° counter-clockwise from east; rotating by -(90 - B) lays them along x.
    val angleRad = -(90.0 - config.angle) * PI / 180.0
    val cosA = cos(angleRad)
    val sinA = sin(angleRad)
    val rotated = polyM.map { pt -> doubleArrayOf(pt[0] * cosA - pt[1] * sinA, pt[0] * sinA + pt[1] * cosA) }

    val ys = SweepLines.offsets(rotated.minOf { it[1] }, rotated.maxOf { it[1] }, lineSpacing.toDouble())
    val passes = SweepLines.passes(rotated, ys)
    val surveyWaypoints = passes.flatMap { pass ->
        listOf(pass.fromX, pass.toX).map { x ->
            val (lat, lon) = rotateBack(x, pass.y, cosA, sinA, refLat, refLon, metersPerDegLon)
            SurveyWaypoint(lat, lon, config.altitude, config.speed, isCameraTrigger = true)
        }
    }

    val totalDistanceM = computeTotalDistance(surveyWaypoints)
    val estimatedTimeSec = if (config.speed > 0f) (totalDistanceM / config.speed).toInt() else 0
    return SurveyResult(
        waypoints = surveyWaypoints,
        photoIntervalM = photoInterval,
        estimatedFlightTimeSec = estimatedTimeSec,
        totalDistanceM = totalDistanceM,
        lineCount = passes.map { it.y }.distinct().size,
        coverageHectares = polygonAreaHectares(config.polygon),
    )
}

/** Ground footprint of a [fovDeg] field of view from [altitude] metres. */
private fun footprintM(altitude: Float, fovDeg: Float): Float = (2.0 * altitude * tan(fovDeg * PI / 360.0)).toFloat()

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

/** Convert the survey result into plain waypoints for mission upload. */
fun SurveyResult.toMissionWaypoints(): List<Waypoint> =
    waypoints.map { Waypoint(it.lat, it.lon, it.alt, it.speed) }
