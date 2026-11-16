package com.altnautica.gcs.feature.plan

import com.altnautica.gcs.core.skills.builtin.TakeoffAltitude
import com.altnautica.gcs.feature.plan.model.MavCmd
import com.altnautica.gcs.feature.plan.model.MissionPlan
import com.altnautica.gcs.feature.plan.model.MissionValidator

/**
 * The climb of a pilot launch for [plan]: its first take-off item's height above home, clamped;
 * the default take-off height without one, or for a sea-level item when the plan has no home altitude.
 */
internal fun launchAltitudeM(plan: MissionPlan): Float {
    val item = plan.items.firstOrNull { it.command == MavCmd.NAV_TAKEOFF || it.command == MavCmd.NAV_VTOL_TAKEOFF }
    val alt = item?.let { MissionValidator.altitudeAboveHome(plan, it) }
    return if (alt == null || !alt.isFinite() || alt <= 0f) TakeoffAltitude.DEFAULT_M else alt.coerceIn(TakeoffAltitude.MIN_M, TakeoffAltitude.MAX_M)
}
