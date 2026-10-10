package com.altnautica.gcs.feature.cockpit

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Great-circle distance in metres and initial bearing in degrees (0..360) from point 1 to point 2. */
    fun distanceBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Pair<Float, Float> {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = p2 - p1
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        val distance = 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        val bearing = (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
        return distance.toFloat() to bearing.toFloat()
    }
}
