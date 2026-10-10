package com.altnautica.gcs.feature.cockpit.preflight

import com.altnautica.gcs.core.model.EkfState
import com.altnautica.gcs.core.model.GpsFix
import com.altnautica.gcs.core.model.GpsState
import com.altnautica.gcs.core.model.HomePosition
import com.altnautica.gcs.feature.cockpit.settings.BatteryBands

enum class PreflightItem { Gps3d, Battery, Ekf, Home, Link, NoPrearm }

/** Outcome of one check; [known] is false when the vehicle has not reported the input. */
data class PreflightCheck(val item: PreflightItem, val passed: Boolean, val known: Boolean)

data class PreflightResult(val checks: List<PreflightCheck>) {
    val passed: Int get() = checks.count { it.passed }
    val total: Int get() = checks.size
    val ready: Boolean get() = checks.all { it.passed }
    val failing: List<PreflightCheck> get() = checks.filterNot { it.passed }
}

data class PreflightInputs(
    val gps: GpsState?,
    val batteryPct: Int?,
    val bands: BatteryBands,
    val ekf: EkfState?,
    val home: HomePosition?,
    val linkFresh: Boolean,
    /** Receive time of the last prearm STATUSTEXT, or null when none was seen. */
    val lastPrearmMs: Long?,
)

object PreflightChecklist {
    const val PREARM_QUIET_MS = 5_000L
    const val EKF_HEALTHY_VARIANCE = 0.8f

    /** EKF_STATUS_REPORT flag: filter not initialised. */
    private const val EKF_UNINITIALIZED = 1 shl 10

    /** EKF_STATUS_REPORT flag: attitude estimate good. */
    private const val EKF_ATTITUDE = 1

    fun evaluate(i: PreflightInputs, nowMs: Long): PreflightResult {
        val gpsOk = i.gps != null && i.gps.fix.ordinal >= GpsFix.Fix3D.ordinal
        val batteryKnown = i.batteryPct != null && i.batteryPct >= 0
        val batteryOk = batteryKnown && i.batteryPct!! >= i.bands.lowPct
        val ekf = i.ekf
        val ekfOk = ekf != null &&
            ekf.flags and EKF_UNINITIALIZED == 0 &&
            ekf.flags and EKF_ATTITUDE != 0 &&
            listOf(ekf.velVar, ekf.posHorizVar, ekf.posVertVar, ekf.compassVar).all { it < EKF_HEALTHY_VARIANCE }
        val prearmQuiet = i.lastPrearmMs == null || nowMs - i.lastPrearmMs >= PREARM_QUIET_MS
        return PreflightResult(
            listOf(
                PreflightCheck(PreflightItem.Gps3d, gpsOk, i.gps != null),
                PreflightCheck(PreflightItem.Battery, batteryOk, batteryKnown),
                PreflightCheck(PreflightItem.Ekf, ekfOk, ekf != null),
                PreflightCheck(PreflightItem.Home, i.home != null, true),
                PreflightCheck(PreflightItem.Link, i.linkFresh, true),
                PreflightCheck(PreflightItem.NoPrearm, prearmQuiet, true),
            ),
        )
    }
}
