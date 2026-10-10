package com.altnautica.gcs.feature.plan.model

import com.altnautica.gcs.core.link.mission.MissionItem
import com.altnautica.gcs.core.map.GeoPoint
import kotlin.math.roundToInt

/** Plan content of the three vehicle lists. */
data class WireMission(val mission: List<MissionItem>, val fence: List<MissionItem>, val rally: List<MissionItem>)

/**
 * Converts a [MissionPlan] to and from the vehicle's mission, fence and rally lists.
 *
 * `DO_JUMP` targets in the planner use file numbering (first mission item = 1). On a
 * vehicle with a home slot (ArduPilot) that equals the wire sequence; on one without
 * (PX4) targets shift down by one, so [homeSlot] selects the numbering.
 */
object MissionWire {

    fun toWire(plan: MissionPlan, homeSlot: Boolean): WireMission = WireMission(
        mission = MissionFlattener.flatten(plan.items).map { it.toMissionItem(homeSlot) },
        fence = fenceItems(plan.fence),
        rally = plan.rally.map { r ->
            MissionItem.global(MavCmd.NAV_RALLY_POINT, r.position.latDeg, r.position.lonDeg, r.altM)
        },
    )

    /** Rebuilds a plan from downloaded lists; [home] is the vehicle's home slot when it has one. */
    fun fromWire(
        home: MissionItem?,
        mission: List<MissionItem>,
        fence: List<MissionItem>,
        rally: List<MissionItem>,
        homeSlot: Boolean,
        nextId: () -> Long,
    ): CollapsedPlan {
        val rows = mission.mapIndexed { i, item -> item.toFlat(seq = i + 1, homeSlot = homeSlot) }
        val collapsed = MissionFlattener.collapse(rows, nextId)
        val plan = MissionPlan(
            items = collapsed.items,
            fence = parseFence(fence, nextId),
            rally = rally.filter { it.command == MavCmd.NAV_RALLY_POINT }.map {
                RallyPoint(nextId(), GeoPoint(it.latDeg, it.lonDeg), it.z)
            },
            home = home?.takeIf { it.x != 0 || it.y != 0 }?.let {
                PlanHome(GeoPoint(it.latDeg, it.lonDeg), it.z)
            },
        )
        return CollapsedPlan(plan, collapsed.warnings)
    }

    data class CollapsedPlan(val plan: MissionPlan, val warnings: List<PlanWarning>)

    private fun fenceItems(fence: List<FenceZone>): List<MissionItem> = fence.flatMap { zone ->
        when (zone) {
            is FenceZone.Polygon -> {
                val cmd = if (zone.inclusion) MavCmd.NAV_FENCE_POLYGON_VERTEX_INCLUSION else MavCmd.NAV_FENCE_POLYGON_VERTEX_EXCLUSION
                if (zone.points.size < 3) {
                    emptyList()
                } else {
                    zone.points.map { p ->
                        MissionItem.global(cmd, p.latDeg, p.lonDeg, 0f, param1 = zone.points.size.toFloat(), frame = FRAME_GLOBAL_INT)
                    }
                }
            }
            is FenceZone.Circle -> {
                val cmd = if (zone.inclusion) MavCmd.NAV_FENCE_CIRCLE_INCLUSION else MavCmd.NAV_FENCE_CIRCLE_EXCLUSION
                listOf(
                    MissionItem.global(cmd, zone.center.latDeg, zone.center.lonDeg, 0f, param1 = zone.radiusM.toFloat(), frame = FRAME_GLOBAL_INT),
                )
            }
        }
    }

    /** Groups consecutive polygon vertices by their declared vertex count. */
    private fun parseFence(items: List<MissionItem>, nextId: () -> Long): List<FenceZone> {
        val zones = ArrayList<FenceZone>()
        var i = 0
        while (i < items.size) {
            val item = items[i]
            when (item.command) {
                MavCmd.NAV_FENCE_POLYGON_VERTEX_INCLUSION, MavCmd.NAV_FENCE_POLYGON_VERTEX_EXCLUSION -> {
                    val count = item.param1.roundToInt().coerceAtLeast(1)
                    val vertices = items.subList(i, minOf(items.size, i + count)).takeWhile { it.command == item.command }
                    if (vertices.size >= 3) {
                        zones += FenceZone.Polygon(
                            id = nextId(),
                            inclusion = item.command == MavCmd.NAV_FENCE_POLYGON_VERTEX_INCLUSION,
                            points = vertices.map { GeoPoint(it.latDeg, it.lonDeg) },
                        )
                    }
                    i += vertices.size.coerceAtLeast(1)
                }
                MavCmd.NAV_FENCE_CIRCLE_INCLUSION, MavCmd.NAV_FENCE_CIRCLE_EXCLUSION -> {
                    zones += FenceZone.Circle(
                        id = nextId(),
                        inclusion = item.command == MavCmd.NAV_FENCE_CIRCLE_INCLUSION,
                        center = GeoPoint(item.latDeg, item.lonDeg),
                        radiusM = item.param1.toDouble(),
                    )
                    i++
                }
                else -> i++
            }
        }
        return zones
    }

    private fun FlatItem.toMissionItem(homeSlot: Boolean): MissionItem {
        val location = MavCmd.carriesLocation(command)
        val p1 = if (command == MavCmd.DO_JUMP && !homeSlot) param1 - 1f else param1
        return MissionItem(
            command = command,
            frame = toIntFrame(frame),
            param1 = p1,
            param2 = param2,
            param3 = param3,
            param4 = param4,
            x = if (location) Math.round(x * 1e7).toInt() else x.roundToInt(),
            y = if (location) Math.round(y * 1e7).toInt() else y.roundToInt(),
            z = z,
            autocontinue = autoContinue,
        )
    }

    private fun MissionItem.toFlat(seq: Int, homeSlot: Boolean): FlatItem {
        val location = MavCmd.carriesLocation(command)
        return FlatItem(
            seq = seq,
            command = command,
            frame = fromIntFrame(frame),
            param1 = if (command == MavCmd.DO_JUMP && !homeSlot) param1 + 1f else param1,
            param2 = param2,
            param3 = param3,
            param4 = param4,
            x = if (location) latDeg else x.toDouble(),
            y = if (location) lonDeg else y.toDouble(),
            z = z,
            autoContinue = autocontinue,
        )
    }

    private const val FRAME_GLOBAL_INT = MavFrame.GLOBAL_INT

    /** `MISSION_ITEM_INT` carries the `_INT` global frames. */
    private fun toIntFrame(frame: Int) = when (frame) {
        MavFrame.GLOBAL -> MavFrame.GLOBAL_INT
        MavFrame.GLOBAL_RELATIVE_ALT -> MavFrame.GLOBAL_RELATIVE_ALT_INT
        MavFrame.GLOBAL_TERRAIN_ALT -> MavFrame.GLOBAL_TERRAIN_ALT_INT
        else -> frame
    }

    /** The planner keeps the non-`_INT` frame ids files use. */
    private fun fromIntFrame(frame: Int) = when (frame) {
        MavFrame.GLOBAL_INT -> MavFrame.GLOBAL
        MavFrame.GLOBAL_RELATIVE_ALT_INT -> MavFrame.GLOBAL_RELATIVE_ALT
        MavFrame.GLOBAL_TERRAIN_ALT_INT -> MavFrame.GLOBAL_TERRAIN_ALT
        else -> frame
    }
}
