package com.altnautica.gcs.feature.cockpit

import com.altnautica.gcs.core.map.GeoPoint
import com.altnautica.gcs.core.model.LocalPosition
import com.altnautica.gcs.core.model.VehicleTelemetry
import kotlin.math.cos
import kotlin.math.hypot

/**
 * The FC's local-frame origin on the globe: the position fix less the local offset when
 * both report, else the EKF origin the FC reported (`GPS_GLOBAL_ORIGIN`); null when
 * neither. Home is not the origin: it moves to wherever the drone was armed.
 */
internal fun localOrigin(t: VehicleTelemetry?): GeoPoint? {
    if (t == null) return null
    val fix = t.position.value
    val local = t.localPosition.value
    if (fix != null && local != null) return localToGlobal(GeoPoint(fix.latDeg, fix.lonDeg), -local.northM, -local.eastM)
    return t.ekfOrigin.value?.let { GeoPoint(it.latDeg, it.lonDeg) }
}

/** [origin] moved [northM] north and [eastM] east (flat earth; a minimap spans metres). */
internal fun localToGlobal(origin: GeoPoint, northM: Float, eastM: Float): GeoPoint = GeoPoint(
    origin.latDeg + northM / METRES_PER_DEG,
    origin.lonDeg + eastM / (METRES_PER_DEG * cos(Math.toRadians(origin.latDeg))),
)

private const val METRES_PER_DEG = 111_319.49

private const val EARTH_RADIUS_M = 6_371_000.0

/** Ground metres between two points (flat earth at the second's latitude; the cockpit's distances are short). */
internal fun flatDistanceM(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Float {
    val dLat = Math.toRadians(aLat - bLat)
    val dLon = Math.toRadians(aLon - bLon) * cos(Math.toRadians(bLat))
    return (hypot(dLat, dLon) * EARTH_RADIUS_M).toFloat()
}

internal fun flatDistanceM(a: GeoPoint, b: GeoPoint): Float = flatDistanceM(a.latDeg, a.lonDeg, b.latDeg, b.lonDeg)

/** The drone on the globe: its position fix, else its local position placed through the FC origin; null when neither. */
internal fun vehicleGeo(t: VehicleTelemetry?): GeoPoint? {
    if (t == null) return null
    t.position.value?.let { return GeoPoint(it.latDeg, it.lonDeg) }
    val local = t.localPosition.value ?: return null
    return localOrigin(t)?.let { localToGlobal(it, local.northM, local.eastM) }
}

/** Horizontal metres from the vehicle's local position to [goal] (NED); null when either is unknown. */
internal fun goalDistanceM(goal: List<Float>, local: LocalPosition?): Float? {
    if (local == null || goal.size < 2) return null
    return hypot(goal[0] - local.northM, goal[1] - local.eastM)
}

/** Metres from the minimap's vehicle to its home; null when either is unknown. */
internal fun homeDistanceM(map: MinimapState): Float? {
    val v = map.vehicle?.position ?: return null
    val h = map.home ?: return null
    return flatDistanceM(v.latDeg, v.lonDeg, h.latDeg, h.lonDeg)
}
