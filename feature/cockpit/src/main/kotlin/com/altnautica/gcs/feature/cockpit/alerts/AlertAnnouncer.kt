package com.altnautica.gcs.feature.cockpit.alerts

import android.content.Context
import com.altnautica.gcs.core.model.AlertLevel
import com.altnautica.gcs.core.model.CockpitAlert
import com.altnautica.gcs.feature.cockpit.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Speaks and vibrates for newly raised critical alerts, and speaks flight-mode
 * changes, according to the operator's audio settings.
 */
class AlertAnnouncer @Inject constructor(
    @ApplicationContext private val context: Context,
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
            for (alert in fresh) tts.speak(spokenTitle(alert), AlertLevel.Critical)
        }
    }

    fun onModeChanged(mode: String?, speak: Boolean) {
        val previous = lastMode
        lastMode = mode
        if (!speak || mode == null || previous == null || previous == mode) return
        ensureTts()
        tts.speak(context.getString(R.string.cockpit_tts_mode, mode.replace('_', ' ').lowercase()), AlertLevel.Advisory)
    }

    private fun spokenTitle(alert: CockpitAlert): String {
        val title = context.getString(alertTitleRes(alert.id))
        return if (alert.id == AlertId.STATUS_TEXT) alert.detail ?: title else title
    }

    fun shutdown() {
        if (ttsStarted) tts.shutdown()
        ttsStarted = false
    }
}

/** Localized title of an alert id. */
fun alertTitleRes(id: String): Int = when (id) {
    AlertId.LINK_LOST -> R.string.cockpit_alert_link_lost
    AlertId.LINK_STALE -> R.string.cockpit_alert_link_stale
    AlertId.BATT_CRIT -> R.string.cockpit_alert_battery_critical
    AlertId.BATT_LOW -> R.string.cockpit_alert_battery_low
    AlertId.FC_STATE -> R.string.cockpit_alert_fc_state
    AlertId.EKF_FAILSAFE -> R.string.cockpit_alert_ekf_failsafe
    AlertId.FENCE_BREACH -> R.string.cockpit_alert_fence_breach
    AlertId.STATUS_TEXT -> R.string.cockpit_alert_status_text
    AlertId.GPS_NO_3D -> R.string.cockpit_alert_gps_no_3d
    AlertId.VIDEO_FROZEN -> R.string.cockpit_alert_video_frozen
    AlertId.RC_FAILSAFE -> R.string.cockpit_alert_rc_failsafe
    AlertId.PREARM -> R.string.cockpit_alert_prearm
    AlertId.HIGH_VIBRATION -> R.string.cockpit_alert_high_vibration
    else -> R.string.cockpit_alert_status_text
}

/** Localized detail for the FC-state alert; other details are FC text shown verbatim. */
fun fcStateDetailRes(detail: String): Int? = when (detail) {
    FcStateDetail.CRITICAL -> R.string.cockpit_alert_fc_critical
    FcStateDetail.EMERGENCY -> R.string.cockpit_alert_fc_emergency
    FcStateDetail.TERMINATION -> R.string.cockpit_alert_fc_termination
    else -> null
}
