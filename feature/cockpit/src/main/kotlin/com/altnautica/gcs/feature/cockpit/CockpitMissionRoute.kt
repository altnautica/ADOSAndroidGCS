package com.altnautica.gcs.feature.cockpit

import com.altnautica.gcs.core.agent.mission.MissionMirror
import com.altnautica.gcs.core.map.GeoPoint
import com.altnautica.gcs.core.map.LineKind
import com.altnautica.gcs.core.map.MapLine
import com.altnautica.gcs.core.map.MapOverlays
import com.altnautica.gcs.core.map.MapWaypoint
import com.altnautica.gcs.core.map.WaypointKind

/*
 * The cockpit maps draw the plan the vehicle actually holds, as its agent mirrors it. That
 * is the plan-identity rule from the cockpit's side: this device's plan is drawn exactly
 * when it is the vehicle's plan, and any other client's plan is drawn instead of a stale
 * local copy. The cockpit never edits, so the mirror is the one source.
 */

private const val NAV_WAYPOINT = 16
private const val NAV_LOITER_UNLIM = 17
private const val NAV_LOITER_TURNS = 18
private const val NAV_LOITER_TIME = 19
private const val NAV_LAND = 21
private const val NAV_TAKEOFF = 22
private const val NAV_LOITER_TO_ALT = 31
private const val NAV_SPLINE_WAYPOINT = 82
private const val NAV_VTOL_TAKEOFF = 84
private const val NAV_VTOL_LAND = 85
private const val MISSION_TYPE_MISSION = 0

/** The vehicle's route as map overlays: numbered navigation items and the path from home. */
fun missionRouteOverlays(mirror: MissionMirror?, home: GeoPoint?): MapOverlays {
    if (mirror == null) return MapOverlays.Empty
    val items = mirror.items.filter { it.missionType == MISSION_TYPE_MISSION }.sortedBy { it.seq }
        .let { if (mirror.homeSlot) it.drop(1) else it }
    val waypoints = ArrayList<MapWaypoint>(items.size)
    val path = ArrayList<GeoPoint>(items.size + 1)
    home?.let(path::add)
    items.forEachIndexed { index, item ->
        val kind = when (item.command) {
            NAV_WAYPOINT, NAV_SPLINE_WAYPOINT -> WaypointKind.Nav
            NAV_TAKEOFF, NAV_VTOL_TAKEOFF -> WaypointKind.Takeoff
            NAV_LAND, NAV_VTOL_LAND -> WaypointKind.Land
            NAV_LOITER_UNLIM, NAV_LOITER_TURNS, NAV_LOITER_TIME, NAV_LOITER_TO_ALT -> WaypointKind.Loiter
            else -> return@forEachIndexed
        }
        if (item.x == 0 && item.y == 0) return@forEachIndexed
        val p = GeoPoint(item.x / 1e7, item.y / 1e7)
        waypoints += MapWaypoint("m${item.seq}", p, (index + 1).toString(), kind, draggable = false)
        path += p
    }
    if (waypoints.isEmpty()) return MapOverlays.Empty
    val lines = if (path.size >= 2) listOf(MapLine("mission", path, LineKind.MissionPath)) else emptyList()
    return MapOverlays(waypoints = waypoints, lines = lines)
}
