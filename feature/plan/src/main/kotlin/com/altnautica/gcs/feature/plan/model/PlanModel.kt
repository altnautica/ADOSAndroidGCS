package com.altnautica.gcs.feature.plan.model

import com.altnautica.gcs.core.map.GeoPoint

/** MAV_CMD ids the planner understands; any other id is preserved verbatim. */
object MavCmd {
    const val NAV_WAYPOINT = 16
    const val NAV_LOITER_UNLIM = 17
    const val NAV_LOITER_TURNS = 18
    const val NAV_LOITER_TIME = 19
    const val NAV_RETURN_TO_LAUNCH = 20
    const val NAV_LAND = 21
    const val NAV_TAKEOFF = 22
    const val NAV_LOITER_TO_ALT = 31
    const val NAV_SPLINE_WAYPOINT = 82
    const val NAV_VTOL_TAKEOFF = 84
    const val NAV_VTOL_LAND = 85
    const val NAV_PAYLOAD_PLACE = 94
    const val DO_JUMP = 177
    const val DO_CHANGE_SPEED = 178
    const val DO_LAND_START = 189
    const val DO_SET_ROI_LOCATION = 195
    const val DO_SET_ROI = 201
    const val DO_DIGICAM_CONTROL = 203
    const val DO_SET_CAM_TRIGG_DIST = 206
    const val NAV_FENCE_POLYGON_VERTEX_INCLUSION = 5001
    const val NAV_FENCE_POLYGON_VERTEX_EXCLUSION = 5002
    const val NAV_FENCE_CIRCLE_INCLUSION = 5003
    const val NAV_FENCE_CIRCLE_EXCLUSION = 5004
    const val NAV_RALLY_POINT = 5100

    private val LOCATION = setOf(
        NAV_WAYPOINT, NAV_LOITER_UNLIM, NAV_LOITER_TURNS, NAV_LOITER_TIME, NAV_LAND, NAV_TAKEOFF,
        NAV_LOITER_TO_ALT, NAV_SPLINE_WAYPOINT, NAV_VTOL_TAKEOFF, NAV_VTOL_LAND, NAV_PAYLOAD_PLACE,
        DO_LAND_START, DO_SET_ROI_LOCATION, DO_SET_ROI,
        NAV_FENCE_POLYGON_VERTEX_INCLUSION, NAV_FENCE_POLYGON_VERTEX_EXCLUSION,
        NAV_FENCE_CIRCLE_INCLUSION, NAV_FENCE_CIRCLE_EXCLUSION, NAV_RALLY_POINT,
    )

    /** Navigation commands: each one is a leg of the flight path. */
    private val NAV = setOf(
        NAV_WAYPOINT, NAV_LOITER_UNLIM, NAV_LOITER_TURNS, NAV_LOITER_TIME, NAV_RETURN_TO_LAUNCH, NAV_LAND,
        NAV_TAKEOFF, NAV_LOITER_TO_ALT, NAV_SPLINE_WAYPOINT, NAV_VTOL_TAKEOFF, NAV_VTOL_LAND, NAV_PAYLOAD_PLACE,
    )

    /** True when param5/param6 hold a latitude/longitude (scaled by 1e7 on the wire). */
    fun carriesLocation(command: Int) = command in LOCATION

    fun isNav(command: Int) = command in NAV
}

/** MAV_FRAME ids used by mission items. */
object MavFrame {
    const val GLOBAL = 0
    const val MISSION = 2
    const val GLOBAL_RELATIVE_ALT = 3
    const val GLOBAL_INT = 5
    const val GLOBAL_RELATIVE_ALT_INT = 6
    const val GLOBAL_TERRAIN_ALT = 10
    const val GLOBAL_TERRAIN_ALT_INT = 11
}

/** Altitude reference of a mission item: above home (REL), above mean sea level (MSL) or above terrain. */
enum class AltitudeRef(val mavFrame: Int) {
    Relative(MavFrame.GLOBAL_RELATIVE_ALT),
    Msl(MavFrame.GLOBAL),
    Terrain(MavFrame.GLOBAL_TERRAIN_ALT),
    ;

    companion object {
        fun fromMavFrame(frame: Int): AltitudeRef? = when (frame) {
            MavFrame.GLOBAL_RELATIVE_ALT, MavFrame.GLOBAL_RELATIVE_ALT_INT -> Relative
            MavFrame.GLOBAL, MavFrame.GLOBAL_INT -> Msl
            MavFrame.GLOBAL_TERRAIN_ALT, MavFrame.GLOBAL_TERRAIN_ALT_INT -> Terrain
            else -> null
        }
    }
}

/** Commands the planner can insert from its menus. */
enum class PlanCommand(val mavCmd: Int) {
    Waypoint(MavCmd.NAV_WAYPOINT),
    Takeoff(MavCmd.NAV_TAKEOFF),
    Land(MavCmd.NAV_LAND),
    ReturnToLaunch(MavCmd.NAV_RETURN_TO_LAUNCH),
    LoiterTime(MavCmd.NAV_LOITER_TIME),
    LoiterTurns(MavCmd.NAV_LOITER_TURNS),
    ChangeSpeed(MavCmd.DO_CHANGE_SPEED),
    SetRoiLocation(MavCmd.DO_SET_ROI_LOCATION),
    DigicamControl(MavCmd.DO_DIGICAM_CONTROL),
    ;

    companion object {
        fun fromMavCmd(cmd: Int) = entries.firstOrNull { it.mavCmd == cmd }
    }
}

/**
 * One mission item in file/wire terms: MAVLink `param1..4`, `x`/`y` (degrees when
 * [MavCmd.carriesLocation], else raw param5/param6) and `z`.
 *
 * [speedMs] is a cruise-speed change flown on the leg into this item; it is
 * written as a `DO_CHANGE_SPEED` immediately before the item. [jumpTarget] is
 * the id of the item a `DO_JUMP` returns to; its param1 is renumbered on export.
 */
data class PlanItem(
    val id: Long,
    val command: Int,
    val frame: Int,
    val param1: Float = 0f,
    val param2: Float = 0f,
    val param3: Float = 0f,
    val param4: Float = 0f,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val z: Float = 0f,
    val autoContinue: Boolean = true,
    val speedMs: Float? = null,
    val jumpTarget: Long? = null,
) {
    /** Map position, for items whose x/y are a real location. */
    val position: GeoPoint?
        get() = if (MavCmd.carriesLocation(command) && !(x == 0.0 && y == 0.0)) GeoPoint(x, y) else null

    val altitudeRef: AltitudeRef? get() = AltitudeRef.fromMavFrame(frame)

    val planCommand: PlanCommand? get() = PlanCommand.fromMavCmd(command)

    /** Seconds held at a waypoint (`NAV_WAYPOINT` param1) or loiter time (`NAV_LOITER_TIME` param1). */
    val holdS: Float? get() = if (command == MavCmd.NAV_WAYPOINT || command == MavCmd.NAV_LOITER_TIME) param1 else null
}

/** Geofence zone. Polygon vertices are listed in order; the ring closes implicitly. */
sealed interface FenceZone {
    val id: Long
    val inclusion: Boolean

    data class Polygon(override val id: Long, override val inclusion: Boolean, val points: List<GeoPoint>) : FenceZone

    data class Circle(override val id: Long, override val inclusion: Boolean, val center: GeoPoint, val radiusM: Double) : FenceZone
}

/** Rally (alternate return) point; [altM] is above home. */
data class RallyPoint(val id: Long, val position: GeoPoint, val altM: Float)

/** Planned home: where the mission is flown from. [altMslM] is null when unknown. */
data class PlanHome(val position: GeoPoint, val altMslM: Float?)

/** A whole mission plan: path, fence and rally points. */
data class MissionPlan(
    val items: List<PlanItem> = emptyList(),
    val fence: List<FenceZone> = emptyList(),
    val rally: List<RallyPoint> = emptyList(),
    val home: PlanHome? = null,
    val cruiseSpeedMs: Float? = null,
) {
    val isEmpty: Boolean get() = items.isEmpty() && fence.isEmpty() && rally.isEmpty()
}
