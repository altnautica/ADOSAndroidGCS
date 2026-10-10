package com.altnautica.gcs.core.map

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** WGS-84 position in degrees. */
data class GeoPoint(val latDeg: Double, val lonDeg: Double)

/** Great-circle helpers on a spherical Earth. */
object GeoMath {
    const val EARTH_RADIUS_M = 6_371_000.0

    /** Haversine distance in metres. */
    fun distanceM(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.latDeg - a.latDeg)
        val dLon = Math.toRadians(b.lonDeg - a.lonDeg)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.latDeg)) * cos(Math.toRadians(b.latDeg)) * sin(dLon / 2) * sin(dLon / 2)
        return EARTH_RADIUS_M * 2.0 * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Initial bearing from [a] to [b], degrees clockwise from true north in 0..360. */
    fun bearingDeg(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.latDeg)
        val lat2 = Math.toRadians(b.latDeg)
        val dLon = Math.toRadians(b.lonDeg - a.lonDeg)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /** Point reached from [origin] travelling [distanceM] along [bearingDeg]. */
    fun destination(origin: GeoPoint, bearingDeg: Double, distanceM: Double): GeoPoint {
        val d = distanceM / EARTH_RADIUS_M
        val brg = Math.toRadians(bearingDeg)
        val lat1 = Math.toRadians(origin.latDeg)
        val lon1 = Math.toRadians(origin.lonDeg)
        val lat2 = kotlin.math.asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(brg))
        val lon2 = lon1 + atan2(sin(brg) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))
        val lonDeg = (Math.toDegrees(lon2) + 540.0) % 360.0 - 180.0
        return GeoPoint(Math.toDegrees(lat2), lonDeg)
    }

    /** Closed ring approximating a circle, first point repeated at the end. */
    fun circleRing(center: GeoPoint, radiusM: Double, segments: Int = 64): List<GeoPoint> {
        val ring = (0 until segments).map { i -> destination(center, i * 360.0 / segments, radiusM) }
        return ring + ring.first()
    }

    /** Smallest box containing every point, or null for an empty list. */
    fun bounds(points: List<GeoPoint>): TileBounds? {
        if (points.isEmpty()) return null
        return TileBounds(
            minLat = points.minOf { it.latDeg },
            maxLat = points.maxOf { it.latDeg },
            minLon = points.minOf { it.lonDeg },
            maxLon = points.maxOf { it.lonDeg },
        )
    }
}
