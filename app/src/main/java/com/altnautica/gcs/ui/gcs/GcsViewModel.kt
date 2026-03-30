package com.altnautica.gcs.ui.gcs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.data.mavlink.MavLinkCommandSender
import com.altnautica.gcs.data.telemetry.BatteryState
import com.altnautica.gcs.data.telemetry.ConnectionState
import com.altnautica.gcs.data.telemetry.FlightMode
import com.altnautica.gcs.data.telemetry.GpsState
import com.altnautica.gcs.data.telemetry.PositionState
import com.altnautica.gcs.data.telemetry.SysStatusState
import com.altnautica.gcs.data.telemetry.TelemetryStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PendingAction(
    val message: String,
    val action: () -> Unit,
)

@HiltViewModel
class GcsViewModel @Inject constructor(
    private val telemetryStore: TelemetryStore,
    private val commandSender: MavLinkCommandSender,
) : ViewModel() {

    val flightMode: StateFlow<FlightMode?> = telemetryStore.flightMode
    val armed: StateFlow<Boolean> = telemetryStore.armed
    val position: StateFlow<PositionState> = telemetryStore.position
    val homePosition: StateFlow<PositionState?> = telemetryStore.homePosition
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
        viewModelScope.launch { commandSender.sendSetMode(mode) }
    }

    private fun sendArm() {
        viewModelScope.launch { commandSender.sendArm() }
    }

    private fun sendDisarm() {
        viewModelScope.launch { commandSender.sendDisarm() }
    }
}
