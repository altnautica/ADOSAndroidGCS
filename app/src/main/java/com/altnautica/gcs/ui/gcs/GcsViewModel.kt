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
import com.altnautica.gcs.ui.common.GamepadManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    private val gamepadManager: GamepadManager,
) : ViewModel() {

    companion object {
        private const val MANUAL_CONTROL_INTERVAL_MS = 20L // 50Hz
    }

    val flightMode: StateFlow<FlightMode?> = telemetryStore.flightMode
    val armed: StateFlow<Boolean> = telemetryStore.armed
    val position: StateFlow<PositionState> = telemetryStore.position
    val homePosition: StateFlow<PositionState?> = telemetryStore.homePosition
    val battery: StateFlow<BatteryState> = telemetryStore.battery
    val gps: StateFlow<GpsState> = telemetryStore.gps
    val sysStatus: StateFlow<SysStatusState> = telemetryStore.sysStatus
    val connection: StateFlow<ConnectionState> = telemetryStore.connection
    val gamepadState = gamepadManager.state

    private val _confirmAction = MutableStateFlow<PendingAction?>(null)
    val confirmAction: StateFlow<PendingAction?> = _confirmAction.asStateFlow()

    private val _missionPaused = MutableStateFlow(false)
    val missionPaused: StateFlow<Boolean> = _missionPaused.asStateFlow()

    private val _showTakeoffDialog = MutableStateFlow(false)
    val showTakeoffDialog: StateFlow<Boolean> = _showTakeoffDialog.asStateFlow()

    private var gamepadJob: Job? = null

    init {
        // Start gamepad polling loop
        startGamepadLoop()
    }

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

    // --- Flight control actions ---

    fun requestTakeoff() {
        _showTakeoffDialog.value = true
    }

    fun dismissTakeoffDialog() {
        _showTakeoffDialog.value = false
    }

    fun confirmTakeoff(altitude: Float) {
        _showTakeoffDialog.value = false
        viewModelScope.launch { commandSender.sendTakeoff(altitude) }
    }

    fun requestLand() {
        _confirmAction.value = PendingAction(
            message = "Land the drone at the current position?",
            action = {
                viewModelScope.launch { commandSender.sendLand() }
            },
        )
    }

    fun requestPause() {
        viewModelScope.launch {
            commandSender.sendPauseMission()
            _missionPaused.value = true
        }
    }

    fun requestResume() {
        viewModelScope.launch {
            commandSender.sendResumeMission()
            _missionPaused.value = false
        }
    }

    // --- Gamepad ---

    private fun startGamepadLoop() {
        gamepadJob?.cancel()
        gamepadJob = viewModelScope.launch {
            while (true) {
                gamepadManager.refreshConnection()
                val state = gamepadManager.state.value
                if (state.connected && state.hasInput) {
                    commandSender.sendManualControl(
                        x = state.x,
                        y = state.y,
                        z = state.z,
                        r = state.r,
                        buttons = state.buttons,
                    )
                }
                delay(MANUAL_CONTROL_INTERVAL_MS)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        gamepadJob?.cancel()
    }

    // --- Private send helpers ---

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
