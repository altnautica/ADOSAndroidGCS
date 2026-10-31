package com.altnautica.gcs.feature.plan.model

import com.altnautica.gcs.core.map.GeoMath

/**
 * Plan summary for the stats strip: [distanceM] sums the legs between positional items,
 * [itemCount] counts mission items, [maxAltRelM] is the highest home-relative altitude
 * (null when no item uses a relative altitude).
 */
data class PlanStats(val distanceM: Double, val itemCount: Int, val maxAltRelM: Float?) {
    companion object {
        fun of(plan: MissionPlan): PlanStats {
            val points = plan.items.mapNotNull { it.position }
            val distance = points.zipWithNext { a, b -> GeoMath.distanceM(a, b) }.sum()
            val maxAlt = plan.items
                .filter { it.altitudeRef == AltitudeRef.Relative && it.command != MavCmd.NAV_RETURN_TO_LAUNCH && it.position != null }
                .maxOfOrNull { it.z }
            return PlanStats(distance, plan.items.size, maxAlt)
        }
    }
}
