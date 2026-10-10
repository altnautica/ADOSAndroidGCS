package com.altnautica.gcs.core.map

import androidx.compose.runtime.Immutable

/** Kind of a mission marker; selects its badge colour on the map. */
enum class WaypointKind { Nav, Takeoff, Land, ReturnHome, Loiter, RegionOfInterest }

/** Mission item marker. [label] is drawn inside the badge (usually the item number). */
@Immutable
data class MapWaypoint(
    val id: String,
    val position: GeoPoint,
    val label: String,
    val kind: WaypointKind = WaypointKind.Nav,
    val selected: Boolean = false,
    val draggable: Boolean = true,
)

enum class AreaKind { FenceInclusion, FenceExclusion, Survey, Draft }

/** Closed polygon; the ring is closed by the renderer. */
@Immutable
data class MapPolygon(val id: String, val points: List<GeoPoint>, val kind: AreaKind)

@Immutable
data class MapCircle(val id: String, val center: GeoPoint, val radiusM: Double, val kind: AreaKind)

enum class LineKind { MissionPath, Trail, Track, SurveyPreview }

@Immutable
data class MapLine(val id: String, val points: List<GeoPoint>, val kind: LineKind)

/** Rally point marker. */
@Immutable
data class MapRallyPoint(val id: String, val position: GeoPoint, val selected: Boolean = false, val draggable: Boolean = true)

/** Small draggable vertex handle, used to edit polygon and circle shapes. */
@Immutable
data class MapHandle(val id: String, val position: GeoPoint)

/** Vehicle marker. [headingDeg] null draws a heading-less dot; [stale] dims it. */
@Immutable
data class MapVehicle(val position: GeoPoint, val headingDeg: Float?, val stale: Boolean = false)

/** Everything drawn above the basemap except the vehicle, home and live trail. */
@Immutable
data class MapOverlays(
    val waypoints: List<MapWaypoint> = emptyList(),
    val lines: List<MapLine> = emptyList(),
    val polygons: List<MapPolygon> = emptyList(),
    val circles: List<MapCircle> = emptyList(),
    val rally: List<MapRallyPoint> = emptyList(),
    val handles: List<MapHandle> = emptyList(),
) {
    companion object {
        val Empty = MapOverlays()
    }
}

/** Kinds of map features that report taps, long presses and drags. */
enum class MapFeatureType { Waypoint, Rally, Handle }

/** Identity of an interactive feature, as passed back to feature callbacks. */
@Immutable
data class MapFeatureRef(val type: MapFeatureType, val id: String)

enum class DragPhase { Start, Move, End }

/** Interactive point currently on the map, in draw order (later wins hit tests). */
internal data class InteractivePoint(val ref: MapFeatureRef, val position: GeoPoint, val draggable: Boolean)

internal fun interactivePoints(overlays: MapOverlays): List<InteractivePoint> = buildList {
    overlays.waypoints.forEach { add(InteractivePoint(MapFeatureRef(MapFeatureType.Waypoint, it.id), it.position, it.draggable)) }
    overlays.rally.forEach { add(InteractivePoint(MapFeatureRef(MapFeatureType.Rally, it.id), it.position, it.draggable)) }
    overlays.handles.forEach { add(InteractivePoint(MapFeatureRef(MapFeatureType.Handle, it.id), it.position, true)) }
}
