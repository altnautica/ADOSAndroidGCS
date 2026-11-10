package com.altnautica.gcs.feature.cockpit.preflight

import com.altnautica.gcs.core.data.cockpit.BatteryBands
import com.altnautica.gcs.core.model.ControlMode
import com.altnautica.gcs.core.model.EkfState
import com.altnautica.gcs.core.model.GpsFix
import com.altnautica.gcs.core.model.GpsState
import com.altnautica.gcs.core.model.HomePosition
import com.altnautica.gcs.core.model.ReadinessItem
import com.altnautica.gcs.core.model.ReadinessSeverity
import com.altnautica.gcs.feature.cockpit.autonomy.NavChip
import com.altnautica.gcs.feature.cockpit.autonomy.NavLabel
import com.altnautica.gcs.feature.cockpit.autonomy.NavTone

/** A checklist item; [id] names it in the status pill's `preflight:<id>` key. */
enum class PreflightItem(val id: String) {
    Gps3d("gps"),
    Battery("battery"),
    Ekf("ekf"),
    Home("home"),
    Link("link"),
    NoPrearm("fc_prearm"),
    NavSource("nav_source"),
    Controller("controller"),
    Storage("storage"),
}

/**
 * Outcome of one check; [known] is false when the vehicle has not reported the input. A
 * check that is not [blocking] warns without holding the first arm back.
 */
data class PreflightCheck(val item: PreflightItem, val passed: Boolean, val known: Boolean, val blocking: Boolean = true)

/**
 * The surface checks plus the onboard pilot's own readiness items, and the percent of the
 * data volume used ([storageUsedPct], null when unknown) that the status pill grades.
 */
data class PreflightResult(
    val checks: List<PreflightCheck>,
    val readiness: List<ReadinessItem> = emptyList(),
    val storageUsedPct: Double? = null,
) {
    val passed: Int get() = checks.count { it.passed } + readiness.count { it.ok }
    val total: Int get() = checks.size + readiness.size
    /** Blocking checks that fail; warnings stay out so they never need an override. */
    val failing: List<PreflightCheck> get() = checks.filter { it.blocking && !it.passed }

    /** Pilot readiness items that refuse arming. */
    val blockingReadiness: List<ReadinessItem> get() = readiness.filter { !it.ok && it.severity == ReadinessSeverity.Block }

    /** Nothing that blocks is failing: the first arm may go ahead. */
    val ready: Boolean get() = checks.none { it.blocking && !it.passed } && blockingReadiness.isEmpty()
}

data class PreflightInputs(
    val gps: GpsState?,
    val batteryPct: Int?,
    val bands: BatteryBands,
    val ekf: EkfState?,
    val home: HomePosition?,
    /** The FC reported its EKF origin (`GPS_GLOBAL_ORIGIN`). */
    val ekfOriginSet: Boolean = false,
    val linkFresh: Boolean,
    /** Receive time of the last prearm STATUSTEXT, or null when none was seen. */
    val lastPrearmMs: Long?,
    /** The onboard pilot's readiness items; empty without ADOS Pilot. */
    val readiness: List<ReadinessItem> = emptyList(),
    val controlMode: ControlMode? = null,
    /** A controller or the on-screen sticks can fly. */
    val controllerConnected: Boolean = false,
    val navChip: NavChip? = null,
    /** Percent of the data volume used, where recordings are written; null when unknown. */
    val storageUsedPct: Double? = null,
    /** Percent used at which storage warns (`layout.json` `status_pill.storage_warn_pct`). */
    val storageWarnPct: Int,
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
        // At the low band the pack already warns: the check fails with it.
        val batteryOk = batteryKnown && i.batteryPct!! > i.bands.lowPct
        val ekf = i.ekf
        val ekfOk = ekf != null &&
            ekf.flags and EKF_UNINITIALIZED == 0 &&
            ekf.flags and EKF_ATTITUDE != 0 &&
            listOf(ekf.velVar, ekf.posHorizVar, ekf.posVertVar, ekf.compassVar).all { it < EKF_HEALTHY_VARIANCE }
        val prearmQuiet = i.lastPrearmMs == null || nowMs - i.lastPrearmMs >= PREARM_QUIET_MS
        val nav = i.navChip
        val navOk = nav != null && nav.label != NavLabel.Stale && nav.tone != NavTone.Crit
        val checks = buildList {
            add(PreflightCheck(PreflightItem.Gps3d, gpsOk, i.gps != null))
            add(PreflightCheck(PreflightItem.Battery, batteryOk, batteryKnown))
            add(PreflightCheck(PreflightItem.Ekf, ekfOk, ekf != null))
            // Home from the FC's HOME_POSITION or its EKF origin, both set on the ground once position is good; never waits for arming.
            add(PreflightCheck(PreflightItem.Home, i.home != null || i.ekfOriginSet, true))
            add(PreflightCheck(PreflightItem.Link, i.linkFresh, true))
            add(PreflightCheck(PreflightItem.NoPrearm, prearmQuiet, true))
            add(PreflightCheck(PreflightItem.NavSource, navOk, nav != null))
            // Sticks are needed only when ADOS Pilot takes them.
            if (i.controlMode == ControlMode.Ados) add(PreflightCheck(PreflightItem.Controller, i.controllerConnected, true))
            add(
                PreflightCheck(
                    PreflightItem.Storage,
                    passed = i.storageUsedPct != null && i.storageUsedPct < i.storageWarnPct,
                    known = i.storageUsedPct != null,
                    blocking = false,
                ),
            )
        }
        return PreflightResult(checks, i.readiness, i.storageUsedPct)
    }
}
