package com.altnautica.gcs.ui.gcs

import androidx.lifecycle.ViewModel
import com.altnautica.gcs.data.telemetry.BatteryState
import com.altnautica.gcs.data.telemetry.FlightMode
import com.altnautica.gcs.data.telemetry.GpsState
import com.altnautica.gcs.data.telemetry.SysStatusState
import com.altnautica.gcs.data.telemetry.TelemetryStore
import com.altnautica.gcs.data.telemetry.ConnectionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

data class PendingAction(
    val message: String,
    val action: () -> Unit,
)

@HiltViewModel
class GcsViewModel @Inject constructor(
    private val telemetryStore: TelemetryStore,
) : ViewModel() {

    val flightMode: StateFlow<FlightMode?> = telemetryStore.flightMode
    val armed: StateFlow<Boolean> = telemetryStore.armed
    val battery: StateFlow<BatteryState> = telemetryStore.battery
    val gps: StateFlow<GpsState> = telemetryStore.gps
    val sysStatus: StateFlow<SysStatusState> = telemetryStore.sysStatus
    val connection: StateFlow<ConnectionState> = telemetryStore.connection

    private val _confirmAction = MutableStateFlow<PendingAction?>(null)
    val confirmAction: StateFlow<PendingAction?> = _confirmAction.asStateFlow()

    fun requestSetMode(mode: FlightMode) {
        _confirmAction.value = PendingAction(
            message = "Switch flight mode to ${mode.label}?",
            action = { sendSetMode(mode) },
        )
    }

    fun requestArm() {
        _confirmAction.value = PendingAction(
            message = "Arm motors? Make sure the area is clear.",
            action = { sendArm() },
        )
    }

    fun requestDisarm() {
        _confirmAction.value = PendingAction(
            message = "Disarm motors?",
            action = { sendDisarm() },
        )
    }

    fun confirmAction() {
        _confirmAction.value?.action?.invoke()
        _confirmAction.value = null
    }

    fun cancelAction() {
        _confirmAction.value = null
    }

    private fun sendSetMode(mode: FlightMode) {
        // TODO: Send MAV_CMD_DO_SET_MODE via MAVLink connection
    }

    private fun sendArm() {
        // TODO: Send MAV_CMD_COMPONENT_ARM_DISARM (arm) via MAVLink connection
    }

    private fun sendDisarm() {
        // TODO: Send MAV_CMD_COMPONENT_ARM_DISARM (disarm) via MAVLink connection
    }
}
