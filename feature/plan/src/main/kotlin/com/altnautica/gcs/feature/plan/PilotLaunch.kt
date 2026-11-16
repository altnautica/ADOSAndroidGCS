package com.altnautica.gcs.feature.plan

import com.altnautica.gcs.core.skills.builtin.TakeoffAltitude
import com.altnautica.gcs.feature.plan.model.MavCmd
import com.altnautica.gcs.feature.plan.model.MissionPlan

/** The climb of a pilot launch for [plan]: its first take-off item's altitude, clamped; the default take-off height without one. */
internal fun launchAltitudeM(plan: MissionPlan): Float {
    val alt = plan.items.firstOrNull { it.command == MavCmd.NAV_TAKEOFF || it.command == MavCmd.NAV_VTOL_TAKEOFF }?.z
    return if (alt == null || !alt.isFinite() || alt <= 0f) TakeoffAltitude.DEFAULT_M else alt.coerceIn(TakeoffAltitude.MIN_M, TakeoffAltitude.MAX_M)
}
