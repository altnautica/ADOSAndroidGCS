package com.altnautica.gcs.feature.plan

import com.altnautica.gcs.core.map.AreaKind
import com.altnautica.gcs.core.map.GeoPoint
import com.altnautica.gcs.core.map.LineKind
import com.altnautica.gcs.core.map.MapCircle
import com.altnautica.gcs.core.map.MapFeatureRef
import com.altnautica.gcs.core.map.MapFeatureType
import com.altnautica.gcs.core.map.MapHandle
import com.altnautica.gcs.core.map.MapLine
import com.altnautica.gcs.core.map.MapOverlays
import com.altnautica.gcs.core.map.MapPolygon
import com.altnautica.gcs.core.map.MapRallyPoint
import com.altnautica.gcs.core.map.MapWaypoint
import com.altnautica.gcs.core.map.WaypointKind
import com.altnautica.gcs.feature.plan.model.FenceZone
import com.altnautica.gcs.feature.plan.model.MavCmd

/** What a map handle edits. */
sealed interface HandleTarget {
    data class SurveyVertex(val index: Int) : HandleTarget

    data class DraftVertex(val index: Int) : HandleTarget

    data class FenceVertex(val zoneId: Long, val index: Int) : HandleTarget

    data class FenceCenter(val zoneId: Long) : HandleTarget

    companion object {
        fun id(target: HandleTarget): String = when (target) {
            is SurveyVertex -> "survey:${target.index}"
            is DraftVertex -> "draft:${target.index}"
            is FenceVertex -> "fz:${target.zoneId}:${target.index}"
            is FenceCenter -> "fc:${target.zoneId}"
        }

        fun parse(id: String): HandleTarget? {
            val parts = id.split(':')
            return when (parts.firstOrNull()) {
                "survey" -> parts.getOrNull(1)?.toIntOrNull()?.let { SurveyVertex(it) }
                "draft" -> parts.getOrNull(1)?.toIntOrNull()?.let { DraftVertex(it) }
                "fz" -> {
                    val zone = parts.getOrNull(1)?.toLongOrNull()
                    val index = parts.getOrNull(2)?.toIntOrNull()
                    if (zone != null && index != null) FenceVertex(zone, index) else null
                }
                "fc" -> parts.getOrNull(1)?.toLongOrNull()?.let { FenceCenter(it) }
                else -> null
            }
        }
    }
}

/** Map overlays for the planner state. */
fun PlannerUiState.toOverlays(): MapOverlays {
    val plan = plan
    val selectedItem = (selection as? Selection.Item)?.id
    val selectedRally = (selection as? Selection.Rally)?.id
    val selectedFence = (selection as? Selection.Fence)?.id

    val waypoints = plan.items.mapIndexedNotNull { index, item ->
        val position = item.position ?: return@mapIndexedNotNull null
        MapWaypoint(
            id = item.id.toString(),
            position = position,
            label = (index + 1).toString(),
            kind = when (item.command) {
                MavCmd.NAV_TAKEOFF, MavCmd.NAV_VTOL_TAKEOFF -> WaypointKind.Takeoff
                MavCmd.NAV_LAND, MavCmd.NAV_VTOL_LAND -> WaypointKind.Land
                MavCmd.NAV_LOITER_TIME, MavCmd.NAV_LOITER_TURNS, MavCmd.NAV_LOITER_UNLIM, MavCmd.NAV_LOITER_TO_ALT -> WaypointKind.Loiter
                MavCmd.DO_SET_ROI_LOCATION, MavCmd.DO_SET_ROI -> WaypointKind.RegionOfInterest
                else -> WaypointKind.Nav
            },
            selected = item.id == selectedItem,
        )
    }

    val homePoint = plan.home?.position ?: (link as? VehicleLink.Connected)?.home
    val path = buildList {
        homePoint?.let { add(it) }
        plan.items.filter { MavCmd.isNav(it.command) }.forEach { item -> item.position?.let { add(it) } }
    }

    val lines = buildList {
        if (path.size >= 2) add(MapLine("mission", path, LineKind.MissionPath))
        surveyPreview?.path?.takeIf { it.size >= 2 }?.let { add(MapLine("survey-preview", it, LineKind.SurveyPreview)) }
        if (fenceDraft.size == 2) add(MapLine("fence-draft", fenceDraft, LineKind.SurveyPreview))
    }

    val polygons = buildList {
        plan.fence.filterIsInstance<FenceZone.Polygon>().forEach { zone ->
            add(MapPolygon("fz-${zone.id}", zone.points, if (zone.inclusion) AreaKind.FenceInclusion else AreaKind.FenceExclusion))
        }
        if (survey.size >= 3) add(MapPolygon("survey", survey, AreaKind.Survey))
        if (fenceDraft.size >= 3) add(MapPolygon("fence-draft", fenceDraft, AreaKind.Draft))
    }

    val circles = plan.fence.filterIsInstance<FenceZone.Circle>().map { zone ->
        MapCircle("fc-${zone.id}", zone.center, zone.radiusM, if (zone.inclusion) AreaKind.FenceInclusion else AreaKind.FenceExclusion)
    }

    val handles = buildList {
        survey.forEachIndexed { i, p -> add(MapHandle(HandleTarget.id(HandleTarget.SurveyVertex(i)), p)) }
        fenceDraft.forEachIndexed { i, p -> add(MapHandle(HandleTarget.id(HandleTarget.DraftVertex(i)), p)) }
        when (val zone = plan.fence.firstOrNull { it.id == selectedFence }) {
            is FenceZone.Polygon -> zone.points.forEachIndexed { i, p -> add(MapHandle(HandleTarget.id(HandleTarget.FenceVertex(zone.id, i)), p)) }
            is FenceZone.Circle -> add(MapHandle(HandleTarget.id(HandleTarget.FenceCenter(zone.id)), zone.center))
            null -> Unit
        }
    }

    return MapOverlays(
        waypoints = waypoints,
        lines = lines,
        polygons = polygons,
        circles = circles,
        rally = plan.rally.map { MapRallyPoint(it.id.toString(), it.position, selected = it.id == selectedRally) },
        handles = handles,
    )
}

/** Home marker position: the plan home, else the vehicle's. */
fun PlannerUiState.homeMarker(): GeoPoint? = plan.home?.position ?: (link as? VehicleLink.Connected)?.home

/** Routes a map feature event to the planner. */
internal fun PlannerViewModel.onFeatureMoved(ref: MapFeatureRef, point: GeoPoint) {
    when (ref.type) {
        MapFeatureType.Waypoint -> ref.id.toLongOrNull()?.let { moveItem(it, point) }
        MapFeatureType.Rally -> ref.id.toLongOrNull()?.let { moveRally(it, point) }
        MapFeatureType.Handle -> when (val target = HandleTarget.parse(ref.id)) {
            is HandleTarget.SurveyVertex -> moveSurveyVertex(target.index, point)
            is HandleTarget.DraftVertex -> moveFenceDraftVertex(target.index, point)
            is HandleTarget.FenceVertex -> moveFenceVertex(target.zoneId, target.index, point)
            is HandleTarget.FenceCenter -> moveFenceVertex(target.zoneId, 0, point)
            null -> Unit
        }
    }
}
