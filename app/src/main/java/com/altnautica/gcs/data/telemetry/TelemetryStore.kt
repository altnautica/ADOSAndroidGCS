package com.altnautica.gcs.data.telemetry

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TelemetryStore @Inject constructor() {

    private val _attitude = MutableStateFlow(AttitudeState())
    val attitude: StateFlow<AttitudeState> = _attitude.asStateFlow()

    private val _position = MutableStateFlow(PositionState())
    val position: StateFlow<PositionState> = _position.asStateFlow()

    private val _battery = MutableStateFlow(BatteryState())
    val battery: StateFlow<BatteryState> = _battery.asStateFlow()

    private val _gps = MutableStateFlow(GpsState())
    val gps: StateFlow<GpsState> = _gps.asStateFlow()

    private val _vfr = MutableStateFlow(VfrState())
    val vfr: StateFlow<VfrState> = _vfr.asStateFlow()

    private val _sysStatus = MutableStateFlow(SysStatusState())
    val sysStatus: StateFlow<SysStatusState> = _sysStatus.asStateFlow()

    private val _flightMode = MutableStateFlow<FlightMode?>(null)
    val flightMode: StateFlow<FlightMode?> = _flightMode.asStateFlow()

    private val _armed = MutableStateFlow(false)
    val armed: StateFlow<Boolean> = _armed.asStateFlow()

    private val _connection = MutableStateFlow(ConnectionState())
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _statusMessages = MutableStateFlow<List<String>>(emptyList())
    val statusMessages: StateFlow<List<String>> = _statusMessages.asStateFlow()

    fun updateAttitude(att: AttitudeState) {
        _attitude.value = att
    }

    fun updatePosition(pos: PositionState) {
        _position.value = pos
    }

    fun updateBattery(bat: BatteryState) {
        _battery.value = bat
    }

    fun updateGps(gps: GpsState) {
        _gps.value = gps
    }

    fun updateVfr(vfr: VfrState) {
        _vfr.value = vfr
    }

    fun updateSysStatus(sys: SysStatusState) {
        _sysStatus.value = sys
    }

    fun updateFlightMode(mode: FlightMode?) {
        _flightMode.value = mode
    }

    fun updateArmed(armed: Boolean) {
        _armed.value = armed
    }

    fun updateConnection(state: ConnectionState) {
        _connection.value = state
    }

    fun addStatusMessage(message: String) {
        val current = _statusMessages.value.toMutableList()
        current.add(message)
        // Keep last 50 messages
        if (current.size > 50) {
            current.removeAt(0)
        }
        _statusMessages.value = current
    }

    fun clear() {
        _attitude.value = AttitudeState()
        _position.value = PositionState()
        _battery.value = BatteryState()
        _gps.value = GpsState()
        _vfr.value = VfrState()
        _sysStatus.value = SysStatusState()
        _flightMode.value = null
        _armed.value = false
        _connection.value = ConnectionState()
        _statusMessages.value = emptyList()
    }
}
