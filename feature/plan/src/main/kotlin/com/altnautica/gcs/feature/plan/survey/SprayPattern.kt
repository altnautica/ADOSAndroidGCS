package com.altnautica.gcs.feature.plan.survey

import kotlin.math.cos

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
 * Passes run along the longer side of the field's bounding box, one swath apart and centred
 * across the field so the outer passes sit half a swath inside the edge. Each pass is clipped
 * to every inside span of the boundary (a concave field is not sprayed across its notch), and
 * passes alternate direction line by line. The mission starts and ends over the first vertex.
 */
object Spray {

    private const val METERS_PER_DEG_LAT = 111_320.0

    fun generate(config: SprayConfig): List<Waypoint> {
        val boundary = config.boundary
        if (boundary.size < 3 || config.swathWidth <= 0f) return emptyList()

        val refLat = (boundary.minOf { it.lat } + boundary.maxOf { it.lat }) / 2.0
        val refLon = (boundary.minOf { it.lon } + boundary.maxOf { it.lon }) / 2.0
        val metersPerDegLon = METERS_PER_DEG_LAT * cos(Math.toRadians(refLat))
        val east = boundary.map { (it.lon - refLon) * metersPerDegLon }
        val north = boundary.map { (it.lat - refLat) * METERS_PER_DEG_LAT }
        // Lines run along the longer extent: east-west when the field is wider than tall.
        val eastWest = (east.max() - east.min()) >= (north.max() - north.min())
        // Sweep frame: x along the lines, y across them.
        val poly = boundary.indices.map { i -> if (eastWest) doubleArrayOf(east[i], north[i]) else doubleArrayOf(north[i], east[i]) }

        val ys = SweepLines.offsets(poly.minOf { it[1] }, poly.maxOf { it[1] }, config.swathWidth.toDouble())
        val waypoints = mutableListOf<Waypoint>()
        fun add(alongM: Double, acrossM: Double) {
            val (eM, nM) = if (eastWest) alongM to acrossM else acrossM to alongM
            waypoints += Waypoint(refLat + nM / METERS_PER_DEG_LAT, refLon + eM / metersPerDegLon, config.altitude, config.speed)
        }

        waypoints += Waypoint(boundary[0].lat, boundary[0].lon, config.altitude, config.speed)
        SweepLines.passes(poly, ys).forEach { pass ->
            add(pass.fromX, pass.y)
            add(pass.toX, pass.y)
        }
        waypoints += Waypoint(boundary[0].lat, boundary[0].lon, config.altitude, config.speed)
        return waypoints
    }
}
