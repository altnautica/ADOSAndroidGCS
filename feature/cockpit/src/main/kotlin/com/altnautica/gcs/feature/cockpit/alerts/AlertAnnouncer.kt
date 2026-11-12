package com.altnautica.gcs.feature.cockpit.alerts

import com.altnautica.gcs.core.model.AlertLevel
import com.altnautica.gcs.core.model.CockpitAlert
import com.altnautica.gcs.core.skills.spec.CockpitSpecRepository
import javax.inject.Inject

/**
 * Speaks and vibrates for newly raised critical alerts, and speaks flight-mode
 * changes, according to the operator's audio settings. Every spoken word is the cockpit
 * spec's text.
 */
class AlertAnnouncer @Inject constructor(
    private val spec: CockpitSpecRepository,
    private val tts: TtsManager,
    private val vibration: VibrationManager,
) {
    private var announced: Set<String> = emptySet()
    private var lastMode: String? = null
    private var ttsStarted = false

    private fun ensureTts() {
        if (!ttsStarted) {
            tts.initialize()
            ttsStarted = true
        }
    }

    fun onAlerts(alerts: List<CockpitAlert>, audio: Boolean) {
        val critical = alerts.filter { it.level == AlertLevel.Critical }
        val fresh = critical.filter { it.id !in announced }
        announced = critical.mapTo(HashSet()) { it.id }
        if (fresh.isEmpty()) return
        vibration.vibrate(AlertLevel.Critical)
        if (audio) {
            ensureTts()
            for (alert in fresh) tts.speak(alertText(alert), AlertLevel.Critical)
        }
    }

    fun onModeChanged(mode: String?, speak: Boolean) {
        val previous = lastMode
        lastMode = mode
        if (!speak || mode == null || previous == null || previous == mode) return
        ensureTts()
        tts.speak(spec.setupStrings.format("cockpit.tts.mode", mapOf("mode" to mode.replace('_', ' ').lowercase())), AlertLevel.Advisory)
    }

    /** Speaks a flight-flow prompt (handover, return home, low battery, mission state). */
    fun speak(text: String) {
        ensureTts()
        tts.speak(text, AlertLevel.Warning)
    }

    /** The alert's spec title, or the FC's own text for a STATUSTEXT alert. */
    fun alertText(alert: CockpitAlert): String {
        val title = spec.setupStrings.format(alertTitleKey(alert.id))
        return if (alert.id == AlertId.STATUS_TEXT) alert.detail ?: title else title
    }

    fun shutdown() {
        if (ttsStarted) tts.shutdown()
        ttsStarted = false
    }
}

/** The spec key of an alert id's title; an alert the spec does not name reads as an FC message. */
fun alertTitleKey(id: String): String = when (id) {
    AlertId.LINK_LOST -> "alert.link_lost"
    AlertId.LINK_STALE -> "alert.link_stale"
    AlertId.BATT_CRIT -> "alert.batt_crit"
    AlertId.BATT_LOW -> "alert.batt_low"
    AlertId.FC_STATE -> "cockpit.status.source.fc_status_text"
    AlertId.EKF_FAILSAFE -> "alert.ekf_failsafe"
    AlertId.FENCE_BREACH -> "alert.fence_breach"
    AlertId.GPS_NO_3D -> "alert.gps_no_3d"
    AlertId.VIDEO_FROZEN -> "alert.video_frozen"
    AlertId.RC_FAILSAFE -> "alert.rc_failsafe"
    AlertId.PREARM -> "alert.prearm"
    AlertId.HIGH_VIBRATION -> "alert.high_vibration"
    AlertId.STICK_MODE -> "alert.stick_mode"
    AlertId.CONTROLLER_LOST -> "alert.controller_lost"
    AlertId.CONTROLLER_BATTERY -> "alert.controller_battery"
    else -> "alert.status_text"
}

/** The spec key of the FC-state alert's detail; other details are FC text shown verbatim. */
fun fcStateDetailKey(detail: String): String? = when (detail) {
    FcStateDetail.CRITICAL -> "alert.fc_state.critical"
    FcStateDetail.EMERGENCY -> "alert.fc_state.emergency"
    FcStateDetail.TERMINATION -> "alert.fc_state.termination"
    else -> null
}
