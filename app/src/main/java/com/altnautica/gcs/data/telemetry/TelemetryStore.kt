package com.altnautica.gcs.data.telemetry

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

    private val _homePosition = MutableStateFlow<PositionState?>(null)
    val homePosition: StateFlow<PositionState?> = _homePosition.asStateFlow()

    private val _statusMessages = MutableStateFlow<List<String>>(emptyList())
    val statusMessages: StateFlow<List<String>> = _statusMessages.asStateFlow()

    private val _lastParamValue = MutableStateFlow<ParamValueState?>(null)
    val lastParamValue: StateFlow<ParamValueState?> = _lastParamValue.asStateFlow()

    private val _lastCommandAck = MutableStateFlow<CommandAckState?>(null)
    val lastCommandAck: StateFlow<CommandAckState?> = _lastCommandAck.asStateFlow()

    private val _rcChannelsRaw = MutableStateFlow(RcChannelsRawState())
    val rcChannelsRaw: StateFlow<RcChannelsRawState> = _rcChannelsRaw.asStateFlow()

    private val _missionProgress = MutableStateFlow(MissionProgressState())
    val missionProgress: StateFlow<MissionProgressState> = _missionProgress.asStateFlow()

    private val _navController = MutableStateFlow(NavControllerState())
    val navController: StateFlow<NavControllerState> = _navController.asStateFlow()

    private val _imu = MutableStateFlow(ImuState())
    val imu: StateFlow<ImuState> = _imu.asStateFlow()

    private val _servoOutput = MutableStateFlow(ServoOutputState())
    val servoOutput: StateFlow<ServoOutputState> = _servoOutput.asStateFlow()

    private val _vibration = MutableStateFlow(VibrationState())
    val vibration: StateFlow<VibrationState> = _vibration.asStateFlow()

    private val _ekf = MutableStateFlow(EkfState())
    val ekf: StateFlow<EkfState> = _ekf.asStateFlow()

    private val _rcChannels = MutableStateFlow(RcChannelsState())
    val rcChannels: StateFlow<RcChannelsState> = _rcChannels.asStateFlow()

    private val _wind = MutableStateFlow(WindState())
    val wind: StateFlow<WindState> = _wind.asStateFlow()

    private val _fenceStatus = MutableStateFlow(FenceStatusState())
    val fenceStatus: StateFlow<FenceStatusState> = _fenceStatus.asStateFlow()

    private val _magCalProgress = MutableStateFlow<MagCalProgressState?>(null)
    val magCalProgress: StateFlow<MagCalProgressState?> = _magCalProgress.asStateFlow()

    private val _magCalReport = MutableStateFlow<MagCalReportState?>(null)
    val magCalReport: StateFlow<MagCalReportState?> = _magCalReport.asStateFlow()

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

    fun updateHomePosition(pos: PositionState) {
        _homePosition.value = pos
    }

    fun addStatusMessage(message: String) {
        _statusMessages.update { current ->
            (current + message).takeLast(50)
        }
    }

    fun updateParamValue(param: ParamValueState) {
        _lastParamValue.value = param
    }

    fun updateCommandAck(ack: CommandAckState) {
        _lastCommandAck.value = ack
    }

    fun updateRcChannelsRaw(rc: RcChannelsRawState) {
        _rcChannelsRaw.value = rc
    }

    fun updateMissionCurrent(seq: Int) {
        _missionProgress.value = _missionProgress.value.copy(currentSeq = seq)
    }

    fun updateMissionItemReached(seq: Int) {
        _missionProgress.value = _missionProgress.value.copy(lastReachedSeq = seq)
    }

    fun updateNavController(nav: NavControllerState) {
        _navController.value = nav
    }

    fun updateImu(imu: ImuState) {
        _imu.value = imu
    }

    fun updateServoOutput(servo: ServoOutputState) {
        _servoOutput.value = servo
    }

    fun updateVibration(vib: VibrationState) {
        _vibration.value = vib
    }

    fun updateEkf(ekf: EkfState) {
        _ekf.value = ekf
    }

    fun updateRcChannels(rc: RcChannelsState) {
        _rcChannels.value = rc
    }

    fun updateWind(wind: WindState) {
        _wind.value = wind
    }

    fun updateFenceStatus(fence: FenceStatusState) {
        _fenceStatus.value = fence
    }

    fun updateMagCalProgress(progress: MagCalProgressState) {
        _magCalProgress.value = progress
    }

    fun updateMagCalReport(report: MagCalReportState) {
        _magCalReport.value = report
    }

    fun clearMagCal() {
        _magCalProgress.value = null
        _magCalReport.value = null
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
        _homePosition.value = null
        _connection.value = ConnectionState()
        _statusMessages.value = emptyList()
        _lastParamValue.value = null
        _lastCommandAck.value = null
        _rcChannelsRaw.value = RcChannelsRawState()
        _missionProgress.value = MissionProgressState()
        _navController.value = NavControllerState()
        _imu.value = ImuState()
        _servoOutput.value = ServoOutputState()
        _vibration.value = VibrationState()
        _ekf.value = EkfState()
        _rcChannels.value = RcChannelsState()
        _wind.value = WindState()
        _fenceStatus.value = FenceStatusState()
        _magCalProgress.value = null
        _magCalReport.value = null
    }
}
