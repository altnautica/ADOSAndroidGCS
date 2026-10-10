package com.altnautica.gcs.feature.cockpit.alerts

import com.altnautica.gcs.core.model.AlertLevel
import com.altnautica.gcs.core.model.CockpitAlert
import com.altnautica.gcs.core.model.EkfState
import com.altnautica.gcs.core.model.FenceStatus
import com.altnautica.gcs.core.model.GpsFix
import com.altnautica.gcs.core.model.GpsState
import com.altnautica.gcs.core.model.SystemHealth
import com.altnautica.gcs.core.model.VibrationState
import com.altnautica.gcs.core.skills.heartbeatAgeMs
import com.altnautica.gcs.feature.cockpit.settings.BatteryBands

/** A STATUSTEXT line kept with its receive time. */
data class ReceivedText(val text: String, val atMs: Long)

/** Every input the alert rules read; times are on the monotonic telemetry clock. */
data class AlertInputs(
    val armed: Boolean?,
    val heartbeatMs: Long?,
    val systemHealth: SystemHealth?,
    /** FC remaining charge; null or negative means unknown and never alerts. */
    val batteryPct: Int?,
    val bands: BatteryBands,
    val ekf: EkfState?,
    val fence: FenceStatus?,
    val gps: GpsState?,
    val vibration: VibrationState?,
    val videoFrozen: Boolean,
    val criticalText: ReceivedText?,
    val prearmText: ReceivedText?,
)

/** Alert ids; the cockpit maps each to a localized title. */
object AlertId {
    const val LINK_LOST = "linkLost"
    const val LINK_STALE = "linkStale"
    const val BATT_CRIT = "battCrit"
    const val BATT_LOW = "battLow"
    const val FC_STATE = "fcState"
    const val EKF_FAILSAFE = "ekfFailsafe"
    const val FENCE_BREACH = "fenceBreach"
    const val STATUS_TEXT = "statusText"
    const val GPS_NO_3D = "gpsNo3d"
    const val VIDEO_FROZEN = "videoFrozen"
    const val RC_FAILSAFE = "rcFailsafe"
    const val PREARM = "prearm"
    const val HIGH_VIBRATION = "highVibration"
}

/** Detail keys for the FC-state alert (MAV_STATE). */
object FcStateDetail {
    const val CRITICAL = "fcCritical"
    const val EMERGENCY = "fcEmergency"
    const val TERMINATION = "fcTermination"
}

enum class LinkHealth { None, Ok, Stale, Lost }

/** Cockpit alert rules (CS-7). Pure apart from remembering when each alert first appeared. */
class AlertEngine {
    private val since = HashMap<String, Long>()

    /** Current alerts, most severe first; [CockpitAlert.sinceMs] is when each id first appeared. */
    fun evaluate(i: AlertInputs, nowMs: Long): List<CockpitAlert> {
        val raw = derive(i, nowMs)
        val ids = raw.mapTo(HashSet()) { it.first }
        since.keys.retainAll(ids)
        return raw.map { (id, level, detail) ->
            val start = since.getOrPut(id) { nowMs }
            CockpitAlert(id = id, level = level, titleKey = "alert.$id", detail = detail, sinceMs = start)
        }
    }

    companion object {
        const val LINK_STALE_MS = 3_000L
        const val LINK_LOST_MS = 10_000L
        const val SAMPLE_FRESH_MS = 5_000L
        const val STATUS_TEXT_HOLD_MS = 10_000L
        const val PREARM_HOLD_MS = 35_000L
        const val EKF_FAILSAFE_VARIANCE = 0.8f
        const val HIGH_VIBRATION = 30f
        const val SENSOR_RC_RECEIVER = 1L shl 16
        const val MAV_SEVERITY_CRITICAL = 2
        private const val FAILSAFE_MAX_SEVERITY = 4

        private val FAILSAFE = Regex("fail\\s*-?\\s*safe", RegexOption.IGNORE_CASE)
        private val ENDED = Regex("\\b(clear(ed)?|resolved|recovered|off|ended|exit(ed)?|deactivated)\\b", RegexOption.IGNORE_CASE)
        private val PREARM = Regex("^PreArm:\\s*", RegexOption.IGNORE_CASE)

        fun linkHealth(heartbeatMs: Long?, nowMs: Long): LinkHealth {
            if (heartbeatMs == null) return LinkHealth.None
            val age = heartbeatAgeMs(heartbeatMs, nowMs)
            return when {
                age > LINK_LOST_MS -> LinkHealth.Lost
                age > LINK_STALE_MS -> LinkHealth.Stale
                else -> LinkHealth.Ok
            }
        }

        /** A STATUSTEXT announcing a failsafe that has not ended. */
        fun isFailsafeAnnouncement(severity: Int, text: String) =
            severity <= FAILSAFE_MAX_SEVERITY && FAILSAFE.containsMatchIn(text) && !ENDED.containsMatchIn(text)

        /** True for a prearm failure line; [prearmBody] strips its prefix. */
        fun isPrearm(text: String) = PREARM.containsMatchIn(text)

        fun prearmBody(text: String) = text.replace(PREARM, "")

        /** Battery band for [pct]; null when unknown. */
        fun batteryLevel(pct: Int?, bands: BatteryBands): AlertLevel? = when {
            pct == null || pct < 0 -> null
            pct < bands.criticalPct -> AlertLevel.Critical
            pct < bands.lowPct -> AlertLevel.Warning
            else -> null
        }

        private fun fresh(tMs: Long?, nowMs: Long) = tMs != null && nowMs - tMs < SAMPLE_FRESH_MS

        private fun derive(i: AlertInputs, now: Long): List<Triple<String, AlertLevel, String?>> {
            val out = ArrayList<Triple<String, AlertLevel, String?>>()
            val armed = i.armed == true
            val link = linkHealth(i.heartbeatMs, now)

            if (link == LinkHealth.Lost && armed) out += Triple(AlertId.LINK_LOST, AlertLevel.Critical, null)
            else if (link == LinkHealth.Stale || link == LinkHealth.Lost) out += Triple(AlertId.LINK_STALE, AlertLevel.Warning, null)

            when (batteryLevel(i.batteryPct, i.bands)) {
                AlertLevel.Critical -> out += Triple(AlertId.BATT_CRIT, AlertLevel.Critical, null)
                AlertLevel.Warning -> out += Triple(AlertId.BATT_LOW, AlertLevel.Warning, null)
                else -> Unit
            }

            // MAV_STATE only speaks for the aircraft while it is heard.
            val stateDetail = when (i.systemHealth?.systemStatus) {
                5 -> FcStateDetail.CRITICAL
                6 -> FcStateDetail.EMERGENCY
                8 -> FcStateDetail.TERMINATION
                else -> null
            }
            if (stateDetail != null && (link == LinkHealth.Ok || link == LinkHealth.Stale)) {
                out += Triple(AlertId.FC_STATE, AlertLevel.Critical, stateDetail)
            }

            val ekf = i.ekf?.takeIf { fresh(it.tMs, now) }
            if (ekf != null && armed) {
                val high = listOf(ekf.velVar, ekf.posHorizVar, ekf.compassVar).count { it >= EKF_FAILSAFE_VARIANCE }
                if (high >= 2) out += Triple(AlertId.EKF_FAILSAFE, AlertLevel.Critical, null)
            }

            val fence = i.fence?.takeIf { fresh(it.tMs, now) }
            if (fence != null && fence.breachStatus > 0) out += Triple(AlertId.FENCE_BREACH, AlertLevel.Critical, null)

            val text = i.criticalText
            if (text != null && now - text.atMs < STATUS_TEXT_HOLD_MS) {
                out += Triple(AlertId.STATUS_TEXT, AlertLevel.Critical, text.text)
            }

            val gps = i.gps?.takeIf { fresh(it.tMs, now) }
            if (gps != null && armed && gps.fix.ordinal < GpsFix.Fix3D.ordinal) {
                out += Triple(AlertId.GPS_NO_3D, AlertLevel.Warning, null)
            }

            if (i.videoFrozen) out += Triple(AlertId.VIDEO_FROZEN, AlertLevel.Warning, null)

            val sys = i.systemHealth?.takeIf { fresh(it.tMs, now) }
            if (sys != null && armed && sys.sensorsPresent and SENSOR_RC_RECEIVER != 0L &&
                sys.sensorsHealthy and SENSOR_RC_RECEIVER == 0L
            ) {
                out += Triple(AlertId.RC_FAILSAFE, AlertLevel.Warning, null)
            }

            val prearm = i.prearmText
            if (i.armed == false && prearm != null && now - prearm.atMs < PREARM_HOLD_MS) {
                out += Triple(AlertId.PREARM, AlertLevel.Advisory, prearm.text)
            }

            val vib = i.vibration?.takeIf { fresh(it.tMs, now) }
            if (vib != null && maxOf(vib.x, vib.y, vib.z) > HIGH_VIBRATION) {
                out += Triple(AlertId.HIGH_VIBRATION, AlertLevel.Advisory, null)
            }

            return out.sortedBy { it.second.ordinal }
        }
    }
}
