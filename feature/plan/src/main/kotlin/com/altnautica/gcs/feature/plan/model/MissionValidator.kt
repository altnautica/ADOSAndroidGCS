package com.altnautica.gcs.feature.plan.model

import com.altnautica.gcs.core.map.GeoMath
import com.altnautica.gcs.core.map.GeoPoint
import kotlin.math.cos
import kotlin.math.hypot

/** Pre-upload findings; [blocking] ones disable Upload, the rest are shown and allowed. */
enum class PlanIssueKind(val blocking: Boolean) {
    /** The first leg is not a takeoff; the vehicle may still launch (advisory). */
    NoTakeoffFirst(false),

    /** No takeoff where the autopilot needs one: the plan starts airborne, or ArduCopter on the ground. */
    MissingTakeoff(true),
    NoLandOrRtlLast(false),
    AltitudeAbove120mRel(false),

    /** A flown item at or below 0 m above home (vehicles that fly only). */
    AltitudeAtOrBelowHome(true),

    /** A flown item at (0, 0) or outside the valid latitude/longitude range. */
    InvalidCoordinates(true),
    OutsideInclusionFence(true),
    InsideExclusionFence(true),

    /** A leg between two items crosses an exclusion zone or leaves its inclusion zone. */
    LegCrossesFence(true),

    /** A flown item farther from home than the vehicle's home-centred circle fence. */
    OutsideHomeCircleFence(true),

    /** A flown item above the vehicle's fence ceiling. */
    AboveFenceCeiling(true),

    /** A flown item below the vehicle's fence floor. */
    BelowFenceFloor(true),

    /** A `DO_JUMP` with no target, or one that loops back forever and never reaches the end. */
    InvalidJump(true),
    Empty(true),
}

/** One finding; [itemId] names the mission item it is about, when it is about one. */
data class PlanIssue(val kind: PlanIssueKind, val itemId: Long? = null)

/** The autopilot the plan is for, as far as the planner knows it. */
enum class Autopilot { ArduCopter, ArduPilotOther, Px4, Unknown }

/**
 * Fence limits the vehicle's own parameters enforce, in metres (altitudes above home).
 * A null field is a limit the vehicle does not enforce.
 */
data class FenceLimits(val homeRadiusM: Double? = null, val ceilingM: Float? = null, val floorM: Float? = null) {
    companion object {
        // ArduPilot FENCE_TYPE bits.
        private const val TYPE_ALT_MAX = 1
        private const val TYPE_CIRCLE = 2
        private const val TYPE_ALT_MIN = 8

        /** The parameters [fromParams] reads. */
        val PARAMS = listOf(
            "FENCE_ENABLE", "FENCE_TYPE", "FENCE_RADIUS", "FENCE_ALT_MAX", "FENCE_ALT_MIN",
            "GF_ACTION", "GF_MAX_HOR_DIST", "GF_MAX_VER_DIST",
        )

        /**
         * Reads the limits from the vehicle's parameters. ArduPilot enforces each limit only
         * with `FENCE_ENABLE` set and its `FENCE_TYPE` bit set; PX4 only with a `GF_ACTION`.
         * Null when no limit applies or the parameters are not loaded.
         */
        fun fromParams(params: Map<String, Double>, autopilot: Autopilot): FenceLimits? {
            fun positive(name: String): Double? = params[name]?.takeIf { it > 0.0 }
            val limits = when (autopilot) {
                Autopilot.ArduCopter, Autopilot.ArduPilotOther -> {
                    val type = params["FENCE_TYPE"]?.toInt()
                    if (params["FENCE_ENABLE"]?.takeIf { it != 0.0 } == null || type == null) return null
                    FenceLimits(
                        homeRadiusM = if (type and TYPE_CIRCLE != 0) positive("FENCE_RADIUS") else null,
                        ceilingM = if (type and TYPE_ALT_MAX != 0) positive("FENCE_ALT_MAX")?.toFloat() else null,
                        floorM = if (type and TYPE_ALT_MIN != 0) positive("FENCE_ALT_MIN")?.toFloat() else null,
                    )
                }
                Autopilot.Px4 -> {
                    if (params["GF_ACTION"]?.takeIf { it != 0.0 } == null) return null
                    FenceLimits(homeRadiusM = positive("GF_MAX_HOR_DIST"), ceilingM = positive("GF_MAX_VER_DIST")?.toFloat())
                }
                Autopilot.Unknown -> return null
            }
            return limits.takeIf { it != FenceLimits() }
        }
    }
}

/**
 * What the validator knows of the vehicle: its MAV_TYPE, autopilot, fence limits, and its
 * home (used when the plan sets none).
 */
data class ValidationProfile(
    val mavType: Int? = null,
    val autopilot: Autopilot = Autopilot.Unknown,
    val fence: FenceLimits? = null,
    val vehicleHome: GeoPoint? = null,
)

/** Checks a plan before it is uploaded. Pure: same plan, same findings. */
object MissionValidator {

    /** Relative altitude above which the plan warns (a common regulatory ceiling). */
    const val MAX_REL_ALT_M = 120f

    // MAV_TYPE values that never take off: ground and water vehicles.
    private const val MAV_TYPE_GROUND_ROVER = 10
    private const val MAV_TYPE_SURFACE_BOAT = 11
    private const val MAV_TYPE_SUBMARINE = 12
    private val SURFACE_TYPES = setOf(MAV_TYPE_GROUND_ROVER, MAV_TYPE_SURFACE_BOAT, MAV_TYPE_SUBMARINE)

    private val TAKEOFFS = setOf(MavCmd.NAV_TAKEOFF, MavCmd.NAV_VTOL_TAKEOFF)
    private val ENDINGS = setOf(MavCmd.NAV_LAND, MavCmd.NAV_VTOL_LAND, MavCmd.NAV_RETURN_TO_LAUNCH)

    /** Legs flown to a position the item must name (takeoff and land may use the current position). */
    private val FLOWN_TO = setOf(
        MavCmd.NAV_WAYPOINT, MavCmd.NAV_SPLINE_WAYPOINT, MavCmd.NAV_LOITER_UNLIM, MavCmd.NAV_LOITER_TURNS,
        MavCmd.NAV_LOITER_TIME, MavCmd.NAV_LOITER_TO_ALT, MavCmd.NAV_PAYLOAD_PLACE,
    )

    /** Items flown at their altitude: everything in [FLOWN_TO] plus the takeoffs. */
    private val FLOWN_AT_ALTITUDE = FLOWN_TO + TAKEOFFS

    /** False for rovers, boats and subs: their missions have no takeoff. */
    fun takeoffExpected(mavType: Int?): Boolean = mavType == null || mavType !in SURFACE_TYPES

    fun validate(plan: MissionPlan, profile: ValidationProfile = ValidationProfile()): List<PlanIssue> {
        if (plan.isEmpty) return listOf(PlanIssue(PlanIssueKind.Empty))
        val issues = ArrayList<PlanIssue>()
        val nav = plan.items.filter { MavCmd.isNav(it.command) }
        // Rovers and boats run at 0 m and subs at negative depth: only flyers need altitude.
        val airborne = takeoffExpected(profile.mavType)
        if (airborne) takeoffIssue(plan, nav.firstOrNull(), profile)?.let { issues += it }
        nav.lastOrNull()?.let { last ->
            if (last.command !in ENDINGS) issues += PlanIssue(PlanIssueKind.NoLandOrRtlLast, last.id)
        }
        plan.items.forEach { item ->
            if (item.altitudeRef == AltitudeRef.Relative && item.command != MavCmd.NAV_RETURN_TO_LAUNCH && item.z > MAX_REL_ALT_M) {
                issues += PlanIssue(PlanIssueKind.AltitudeAbove120mRel, item.id)
            }
            if (airborne && item.command in FLOWN_AT_ALTITUDE && item.altitudeRef == AltitudeRef.Relative && item.z <= 0f) {
                issues += PlanIssue(PlanIssueKind.AltitudeAtOrBelowHome, item.id)
            }
            if (item.command in FLOWN_TO && !validCoordinates(item)) issues += PlanIssue(PlanIssueKind.InvalidCoordinates, item.id)
            profile.fence?.let { issues += fenceLimitIssues(plan, item, it, profile.vehicleHome) }
        }
        val inclusions = plan.fence.filter { it.inclusion }
        val exclusions = plan.fence.filterNot { it.inclusion }
        val outOfBounds = HashSet<Long>()
        nav.forEach { item ->
            val p = item.position ?: return@forEach
            if (inclusions.isNotEmpty() && inclusions.none { contains(it, p) }) {
                issues += PlanIssue(PlanIssueKind.OutsideInclusionFence, item.id)
                outOfBounds += item.id
            }
            if (exclusions.any { contains(it, p) }) {
                issues += PlanIssue(PlanIssueKind.InsideExclusionFence, item.id)
                outOfBounds += item.id
            }
        }
        // Legs between two in-bounds points: a straight line can still cut a zone.
        nav.filter { it.position != null }.zipWithNext().forEach { (from, to) ->
            if (from.id in outOfBounds || to.id in outOfBounds) return@forEach
            val a = from.position!!
            val b = to.position!!
            val crosses = exclusions.any { crossesZone(it, a, b) } ||
                inclusions.filter { contains(it, a) && contains(it, b) }.let { holding -> holding.isNotEmpty() && holding.all { crossesZone(it, a, b) } }
            if (crosses) issues += PlanIssue(PlanIssueKind.LegCrossesFence, to.id)
        }
        issues += jumpIssues(plan.items)
        return issues
    }

    private fun takeoffIssue(plan: MissionPlan, first: PlanItem?, profile: ValidationProfile): PlanIssue? {
        if (first == null || first.command in TAKEOFFS) return null
        val startsAirborne = altitudeAboveHome(plan, first)?.let { it > 0f } ?: true
        val blocking = when {
            startsAirborne -> profile.autopilot != Autopilot.Px4 // PX4 climbs to MIS_TAKEOFF_ALT by itself first.
            else -> profile.autopilot == Autopilot.ArduCopter // ArduCopter does not start AUTO on the ground without one.
        }
        return PlanIssue(if (blocking) PlanIssueKind.MissingTakeoff else PlanIssueKind.NoTakeoffFirst, first.id)
    }

    /** The item's altitude above home, or null when it cannot be told (MSL without a home altitude). */
    private fun altitudeAboveHome(plan: MissionPlan, item: PlanItem): Float? = when (item.altitudeRef) {
        AltitudeRef.Relative, AltitudeRef.Terrain -> item.z
        AltitudeRef.Msl -> plan.home?.altMslM?.let { item.z - it }
        null -> null
    }

    private fun validCoordinates(item: PlanItem): Boolean =
        !(item.x == 0.0 && item.y == 0.0) && item.x in -90.0..90.0 && item.y in -180.0..180.0

    private fun fenceLimitIssues(plan: MissionPlan, item: PlanItem, limits: FenceLimits, vehicleHome: GeoPoint?): List<PlanIssue> {
        if (item.command !in FLOWN_AT_ALTITUDE) return emptyList()
        val issues = ArrayList<PlanIssue>(1)
        // Terrain-relative altitudes have no fixed height above home to compare.
        val aboveHome = if (item.altitudeRef == AltitudeRef.Terrain) null else altitudeAboveHome(plan, item)
        if (aboveHome != null) {
            if (limits.ceilingM != null && aboveHome > limits.ceilingM) issues += PlanIssue(PlanIssueKind.AboveFenceCeiling, item.id)
            if (limits.floorM != null && aboveHome < limits.floorM) issues += PlanIssue(PlanIssueKind.BelowFenceFloor, item.id)
        }
        val home = plan.home?.position ?: vehicleHome
        val p = item.position
        if (limits.homeRadiusM != null && home != null && p != null && item.command in FLOWN_TO && validCoordinates(item) &&
            GeoMath.distanceM(home, p) > limits.homeRadiusM
        ) {
            issues += PlanIssue(PlanIssueKind.OutsideHomeCircleFence, item.id)
        }
        return issues
    }

    private fun jumpIssues(items: List<PlanItem>): List<PlanIssue> {
        val index = items.withIndex().associate { (i, item) -> item.id to i }
        return items.withIndex().mapNotNull { (i, item) ->
            if (item.command != MavCmd.DO_JUMP) return@mapNotNull null
            val target = item.jumpTarget?.let { index[it] }
            // param2 is the repeat count; a negative count on a backward jump loops forever.
            val loopsForever = target != null && target <= i && item.param2 < 0f
            if (target == null || loopsForever) PlanIssue(PlanIssueKind.InvalidJump, item.id) else null
        }
    }

    fun contains(zone: FenceZone, p: GeoPoint): Boolean = when (zone) {
        is FenceZone.Circle -> GeoMath.distanceM(zone.center, p) <= zone.radiusM
        is FenceZone.Polygon -> zone.points.size >= 3 && inPolygon(zone.points, p)
    }

    /** True when the segment a→b touches the zone's boundary or, for a circle, passes through it. */
    private fun crossesZone(zone: FenceZone, a: GeoPoint, b: GeoPoint): Boolean = when (zone) {
        is FenceZone.Circle -> if (zone.inclusion) false else segmentDistanceM(zone.center, a, b) <= zone.radiusM
        is FenceZone.Polygon -> zone.points.size >= 3 &&
            zone.points.indices.any { i -> segmentsIntersect(a, b, zone.points[i], zone.points[(i + 1) % zone.points.size]) }
    }

    /** Distance from [p] to the segment a→b on a local flat projection around [p]. */
    private fun segmentDistanceM(p: GeoPoint, a: GeoPoint, b: GeoPoint): Double {
        val mPerDegLat = 111_320.0
        val mPerDegLon = mPerDegLat * cos(Math.toRadians(p.latDeg))
        val ax = (a.lonDeg - p.lonDeg) * mPerDegLon
        val ay = (a.latDeg - p.latDeg) * mPerDegLat
        val bx = (b.lonDeg - p.lonDeg) * mPerDegLon
        val by = (b.latDeg - p.latDeg) * mPerDegLat
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(ax + t * dx, ay + t * dy)
    }

    private fun segmentsIntersect(p1: GeoPoint, p2: GeoPoint, q1: GeoPoint, q2: GeoPoint): Boolean {
        fun orient(a: GeoPoint, b: GeoPoint, c: GeoPoint): Double =
            (b.lonDeg - a.lonDeg) * (c.latDeg - a.latDeg) - (b.latDeg - a.latDeg) * (c.lonDeg - a.lonDeg)
        val d1 = orient(q1, q2, p1)
        val d2 = orient(q1, q2, p2)
        val d3 = orient(p1, p2, q1)
        val d4 = orient(p1, p2, q2)
        return ((d1 > 0) != (d2 > 0)) && ((d3 > 0) != (d4 > 0))
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
