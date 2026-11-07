package com.altnautica.gcs.feature.cockpit.manual

import com.altnautica.gcs.core.model.AlertLevel
import com.altnautica.gcs.feature.cockpit.StickChip
import com.altnautica.gcs.feature.cockpit.alerts.AlertEngine
import com.altnautica.gcs.feature.cockpit.alerts.AlertId
import com.altnautica.gcs.feature.cockpit.alerts.RaisedAlert

/**
 * The alert for stick control turning itself off. Losing the controller while armed is
 * critical: the operator has lost the stick input they were flying with.
 */
internal fun StickOff.toAlert(nowMs: Long): RaisedAlert = when (this) {
    is StickOff.ModeLeft -> RaisedAlert(AlertId.STICK_MODE, AlertLevel.Warning, mode, nowMs)
    is StickOff.ControllerLost ->
        RaisedAlert(AlertId.CONTROLLER_LOST, if (armed) AlertLevel.Critical else AlertLevel.Warning, null, nowMs)
}

/** True while stick control is on and the controller reports a low battery. */
internal fun controllerBatteryLow(stick: StickChip, batteryPct: Int?): Boolean {
    val flying = (stick as? StickChip.Available)?.on == true || (stick as? StickChip.Pilot)?.streaming == true
    return flying && batteryPct != null && batteryPct < AlertEngine.CONTROLLER_BATTERY_LOW_PCT
}
