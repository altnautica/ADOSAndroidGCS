package com.altnautica.gcs.ui.groundstation

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.R
import com.altnautica.gcs.data.firmware.FirmwareManager
import com.altnautica.gcs.data.firmware.FirmwareState
import com.altnautica.gcs.data.firmware.FirmwareUpdate
import com.altnautica.gcs.data.groundstation.CameraNotSupportedError
import com.altnautica.gcs.data.groundstation.GroundStationRepository
import com.altnautica.gcs.data.pairing.NotPairedError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GroundStationStats(
    val connected: Boolean = false,
    val rssiDbm: Int = -100,
    val packetLossPercent: Float = 0f,
    val fecRecovered: Int = 0,
    val bitrateKbps: Float = 0f,
    val adapterRssiList: List<AdapterRssi> = emptyList(),
)

data class SystemInfo(
    val hostname: String = "--",
    val ipAddress: String = "--",
    val cpuTempC: Int = 0,
    val uptime: String = "--",
    val wfbVersion: String = "--",
    val firmwareUpdateAvailable: Boolean = false,
)

@HiltViewModel
class GroundStationViewModel @Inject constructor(
    private val repository: GroundStationRepository,
    private val firmwareManager: FirmwareManager,
) : ViewModel() {

    private val _stats = MutableStateFlow(GroundStationStats())
    val stats: StateFlow<GroundStationStats> = _stats.asStateFlow()

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    private val _recordingStartTime = MutableStateFlow(0L)
    val recordingStartTime: StateFlow<Long> = _recordingStartTime.asStateFlow()

    private val _activeCamera = MutableStateFlow("cam0")
    val activeCamera: StateFlow<String> = _activeCamera.asStateFlow()

    private val _systemInfo = MutableStateFlow(SystemInfo())
    val systemInfo: StateFlow<SystemInfo> = _systemInfo.asStateFlow()

    /**
     * Transient operator notice as a string resource id.
     *
     * A resource id rather than a formatted string so the message is
     * translatable — the app ships localized resources, and a message built
     * here would stay English for every one of them.
     */
    private val _notice = MutableStateFlow<Int?>(null)
    val notice: StateFlow<Int?> = _notice.asStateFlow()

    // Firmware advisory. There is no push path — the agent serves no OTA
    // route — so there is no progress to report and none is invented.
    val firmwareUpdate: StateFlow<FirmwareUpdate?> = firmwareManager.availableUpdate
    val firmwareState: StateFlow<FirmwareState> = firmwareManager.state
    val firmwareError: StateFlow<String?> = firmwareManager.error
    val firmwareApplyInstructionRes: Int = firmwareManager.applyInstructionRes()

    private val _showFirmwareDialog = MutableStateFlow(false)
    val showFirmwareDialog: StateFlow<Boolean> = _showFirmwareDialog.asStateFlow()

    private val _restarting = MutableStateFlow(false)
    val restarting: StateFlow<Boolean> = _restarting.asStateFlow()

    init {
        repository.startPolling()
        collectRepositoryFlows()
        checkFirmware()
    }

    private fun checkFirmware() {
        viewModelScope.launch {
            val currentVersion = _systemInfo.value.wfbVersion
            if (currentVersion != "--") {
                firmwareManager.checkForUpdate(currentVersion)
            }
        }
    }

    fun showFirmwareDialog() {
        _showFirmwareDialog.value = true
    }

    fun dismissFirmwareDialog() {
        _showFirmwareDialog.value = false
        firmwareManager.reset()
    }

    /**
     * Cycle the agent's service tree on the node.
     *
     * The recovery action for a wedged radio, video or MAVLink service that
     * would otherwise need an SSH session. Not an OS reboot: the agent serves
     * no route for that.
     */
    fun restartAgentServices() {
        if (_restarting.value) return
        viewModelScope.launch {
            _restarting.value = true
            repository.restartAgentServices()
                .onSuccess { _notice.value = R.string.station_restart_requested }
                .onFailure { err -> _notice.value = noticeFor(err, R.string.station_restart_failed) }
            _restarting.value = false
        }
    }

    fun consumeNotice() {
        _notice.value = null
    }

    private fun collectRepositoryFlows() {
        viewModelScope.launch {
            repository.status.collect { status ->
                _recording.value = status.recording
                _stats.value = GroundStationStats(
                    connected = repository.reachable.value,
                    rssiDbm = status.link.rssiDbm ?: -100,
                    packetLossPercent = 0f,
                    fecRecovered = status.link.fecRecovered,
                    bitrateKbps = status.link.bitrateMbps?.let { it * 1000f } ?: 0f,
                )
                _systemInfo.value = SystemInfo(
                    hostname = status.network.apSsid ?: "--",
                    ipAddress = status.network.apIp ?: "--",
                    cpuTempC = status.system.tempC?.toInt() ?: 0,
                    uptime = formatUptime(status.system.uptimeSeconds),
                    wfbVersion = status.system.agentVersion.ifEmpty { "--" },
                    firmwareUpdateAvailable = firmwareManager.availableUpdate.value != null,
                )
            }
        }
    }

    fun startRecording() {
        viewModelScope.launch {
            repository.startRecording()
                .onSuccess {
                    _recording.value = true
                    _recordingStartTime.value = System.currentTimeMillis()
                }
                .onFailure { err ->
                    _notice.value = noticeFor(err, R.string.station_recording_start_failed)
                }
        }
    }

    fun switchCamera(cameraId: String) {
        viewModelScope.launch {
            repository.switchCamera(cameraId)
                .onSuccess {
                    _activeCamera.value = cameraId
                }
                .onFailure { err ->
                    _notice.value = when {
                        err is CameraNotSupportedError -> R.string.station_camera_unsupported
                        else -> noticeFor(err, R.string.station_camera_switch_failed)
                    }
                }
        }
    }

    fun stopRecording() {
        viewModelScope.launch {
            repository.stopRecording()
                .onSuccess {
                    _recording.value = false
                    _recordingStartTime.value = 0L
                }
                .onFailure { err ->
                    _notice.value = noticeFor(err, R.string.station_recording_stop_failed)
                }
        }
    }

    /**
     * Map a failure to a notice, promoting the pairing case.
     *
     * A 401 from a paired node is not a broken ground station: it means this
     * handset holds no key. Reported as the generic failure it used to be, the
     * operator had no way to reach the one action that fixes it.
     */
    @StringRes
    private fun noticeFor(error: Throwable, @StringRes fallback: Int): Int =
        if (error is NotPairedError) R.string.station_not_paired else fallback

    private fun formatUptime(seconds: Long): String {
        if (seconds <= 0) return "--"
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return "%dh %02dm".format(hours, minutes)
    }

    override fun onCleared() {
        super.onCleared()
        repository.stopPolling()
    }
}
