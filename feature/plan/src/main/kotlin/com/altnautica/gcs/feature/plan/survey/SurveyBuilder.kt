package com.altnautica.gcs.feature.plan.survey

import com.altnautica.gcs.core.map.GeoPoint
import com.altnautica.gcs.feature.plan.model.AltitudeRef
import com.altnautica.gcs.feature.plan.model.MavCmd
import com.altnautica.gcs.feature.plan.model.MavFrame
import com.altnautica.gcs.feature.plan.model.PlanItem

/** How lane spacing is derived: from camera footprint and overlap, or from a spray swath. */
enum class SurveyMode { Camera, Spray }

/** Survey settings edited in the planner; the polygon is drawn on the map. */
data class SurveySettings(
    val mode: SurveyMode = SurveyMode.Camera,
    val altitudeM: Float = 50f,
    val speedMs: Float = 5f,
    val overlapPct: Float = 70f,
    val sidelapPct: Float = 60f,
    val cameraFovDeg: Float = 73f,
    val angleDeg: Float = 0f,
    val swathM: Float = 5f,
)

/** Result of laying a survey over a polygon: plan items plus preview stats. */
data class SurveyPlan(
    val items: List<PlanItem>,
    val path: List<GeoPoint>,
    val lineCount: Int,
    val photoIntervalM: Float?,
    val distanceM: Float?,
    val flightTimeS: Int?,
    val areaHa: Float?,
)

/** Turns a polygon and [SurveySettings] into mission items with the salvaged pattern generators. */
object SurveyBuilder {

    fun build(polygon: List<GeoPoint>, settings: SurveySettings, ref: AltitudeRef, nextId: () -> Long): SurveyPlan? {
        if (polygon.size < 3) return null
        val vertices = polygon.map { SurveyLatLng(it.latDeg, it.lonDeg) }
        return when (settings.mode) {
            SurveyMode.Camera -> camera(vertices, settings, ref, nextId)
            SurveyMode.Spray -> spray(vertices, settings, ref, nextId)
        }
    }

    private fun camera(vertices: List<SurveyLatLng>, s: SurveySettings, ref: AltitudeRef, nextId: () -> Long): SurveyPlan? {
        val result = generateSurveyGrid(
            SurveyConfig(
                polygon = vertices,
                altitude = s.altitudeM,
                overlapPercent = s.overlapPct,
                sidelapPercent = s.sidelapPct,
                cameraFovDeg = s.cameraFovDeg,
                speed = s.speedMs,
                angle = s.angleDeg,
            ),
        )
        if (result.waypoints.isEmpty()) return null
        val waypoints = result.toMissionWaypoints()
        val items = ArrayList<PlanItem>(waypoints.size + 2)
        waypoints.forEachIndexed { i, wp ->
            items += navItem(nextId(), wp, ref, speed = if (i == 0) s.speedMs else null)
            if (i == 0) items += triggerDistance(nextId(), result.photoIntervalM)
        }
        items += triggerDistance(nextId(), 0f)
        return SurveyPlan(
            items = items,
            path = waypoints.map { GeoPoint(it.lat, it.lon) },
            lineCount = result.lineCount,
            photoIntervalM = result.photoIntervalM,
            distanceM = result.totalDistanceM,
            flightTimeS = result.estimatedFlightTimeSec,
            areaHa = result.coverageHectares,
        )
    }

    private fun spray(vertices: List<SurveyLatLng>, s: SurveySettings, ref: AltitudeRef, nextId: () -> Long): SurveyPlan? {
        val waypoints = Spray.generate(SprayConfig(vertices, s.altitudeM, s.speedMs, s.swathM))
        if (waypoints.isEmpty()) return null
        val items = waypoints.mapIndexed { i, wp -> navItem(nextId(), wp, ref, speed = if (i == 0) s.speedMs else null) }
        // First and last points are the entry/exit over the boundary; the rest come in pairs per pass.
        return SurveyPlan(
            items = items,
            path = waypoints.map { GeoPoint(it.lat, it.lon) },
            lineCount = ((waypoints.size - 2) / 2).coerceAtLeast(0),
            photoIntervalM = null,
            distanceM = null,
            flightTimeS = null,
            areaHa = null,
        )
    }

    private fun navItem(id: Long, wp: Waypoint, ref: AltitudeRef, speed: Float?) = PlanItem(
        id = id,
        command = MavCmd.NAV_WAYPOINT,
        frame = ref.mavFrame,
        x = wp.lat,
        y = wp.lon,
        z = wp.alt,
        speedMs = speed,
    )

    /** `DO_SET_CAM_TRIGG_DIST`: param1 distance in metres (0 stops), param3 = 1 triggers once now. */
    private fun triggerDistance(id: Long, distanceM: Float) = PlanItem(
        id = id,
        command = MavCmd.DO_SET_CAM_TRIGG_DIST,
        frame = MavFrame.MISSION,
        param1 = distanceM,
        param3 = if (distanceM > 0f) 1f else 0f,
    )
}
