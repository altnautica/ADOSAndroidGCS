package com.altnautica.gcs.feature.plan.model

import com.altnautica.gcs.core.map.GeoMath
import com.altnautica.gcs.core.map.GeoPoint

/** Pre-upload findings; [blocking] ones disable Upload, the rest are shown and allowed. */
enum class PlanIssueKind(val blocking: Boolean) {
    NoTakeoffFirst(false),
    NoLandOrRtlLast(false),
    AltitudeAbove120mRel(false),
    OutsideInclusionFence(true),
    InsideExclusionFence(true),
    Empty(true),
}

/** One finding; [itemId] names the mission item it is about, when it is about one. */
data class PlanIssue(val kind: PlanIssueKind, val itemId: Long? = null)

/** Checks a plan before it is uploaded. Pure: same plan, same findings. */
object MissionValidator {

    /** Relative altitude above which the plan warns (a common regulatory ceiling). */
    const val MAX_REL_ALT_M = 120f

    // MAV_TYPE values that take off without a takeoff item: wings, ground and water vehicles.
    private const val MAV_TYPE_FIXED_WING = 1
    private const val MAV_TYPE_GROUND_ROVER = 10
    private const val MAV_TYPE_SURFACE_BOAT = 11
    private const val MAV_TYPE_SUBMARINE = 12
    private val NO_TAKEOFF_TYPES = setOf(MAV_TYPE_FIXED_WING, MAV_TYPE_GROUND_ROVER, MAV_TYPE_SURFACE_BOAT, MAV_TYPE_SUBMARINE)

    private val TAKEOFFS = setOf(MavCmd.NAV_TAKEOFF, MavCmd.NAV_VTOL_TAKEOFF)
    private val ENDINGS = setOf(MavCmd.NAV_LAND, MavCmd.NAV_VTOL_LAND, MavCmd.NAV_RETURN_TO_LAUNCH)

    /** True for multirotors, VTOLs and unknown airframes: their missions should start with a takeoff. */
    fun takeoffExpected(mavType: Int?): Boolean = mavType == null || mavType !in NO_TAKEOFF_TYPES

    fun validate(plan: MissionPlan, takeoffExpected: Boolean): List<PlanIssue> {
        if (plan.isEmpty) return listOf(PlanIssue(PlanIssueKind.Empty))
        val issues = ArrayList<PlanIssue>()
        val nav = plan.items.filter { MavCmd.isNav(it.command) }
        if (takeoffExpected) {
            val first = nav.firstOrNull()
            if (first != null && first.command !in TAKEOFFS) issues += PlanIssue(PlanIssueKind.NoTakeoffFirst, first.id)
        }
        nav.lastOrNull()?.let { last ->
            if (last.command !in ENDINGS) issues += PlanIssue(PlanIssueKind.NoLandOrRtlLast, last.id)
        }
        plan.items.forEach { item ->
            if (item.altitudeRef == AltitudeRef.Relative && item.command != MavCmd.NAV_RETURN_TO_LAUNCH && item.z > MAX_REL_ALT_M) {
                issues += PlanIssue(PlanIssueKind.AltitudeAbove120mRel, item.id)
            }
        }
        val inclusions = plan.fence.filter { it.inclusion }
        val exclusions = plan.fence.filterNot { it.inclusion }
        nav.forEach { item ->
            val p = item.position ?: return@forEach
            if (inclusions.isNotEmpty() && inclusions.none { contains(it, p) }) issues += PlanIssue(PlanIssueKind.OutsideInclusionFence, item.id)
            if (exclusions.any { contains(it, p) }) issues += PlanIssue(PlanIssueKind.InsideExclusionFence, item.id)
        }
        return issues
    }

    fun contains(zone: FenceZone, p: GeoPoint): Boolean = when (zone) {
        is FenceZone.Circle -> GeoMath.distanceM(zone.center, p) <= zone.radiusM
        is FenceZone.Polygon -> zone.points.size >= 3 && inPolygon(zone.points, p)
    }

    /** Even-odd ray cast on lat/lon; fence zones are small enough for a planar test. */
    private fun inPolygon(ring: List<GeoPoint>, p: GeoPoint): Boolean {
        var inside = false
        var j = ring.lastIndex
        for (i in ring.indices) {
            val a = ring[i]
            val b = ring[j]
            if ((a.latDeg > p.latDeg) != (b.latDeg > p.latDeg)) {
                val lonAtLat = a.lonDeg + (p.latDeg - a.latDeg) * (b.lonDeg - a.lonDeg) / (b.latDeg - a.latDeg)
                if (p.lonDeg < lonAtLat) inside = !inside
            }
            j = i
        }
        return inside
    }
}
