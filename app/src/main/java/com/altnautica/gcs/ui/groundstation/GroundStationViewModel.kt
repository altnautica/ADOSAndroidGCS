package com.altnautica.gcs.ui.groundstation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.data.firmware.FirmwareManager
import com.altnautica.gcs.data.firmware.FirmwareState
import com.altnautica.gcs.data.firmware.FirmwareUpdate
import com.altnautica.gcs.data.groundstation.CameraNotSupportedError
import com.altnautica.gcs.data.groundstation.GroundStationRepository
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

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    // Firmware state
    val firmwareUpdate: StateFlow<FirmwareUpdate?> = firmwareManager.availableUpdate
    val firmwareState: StateFlow<FirmwareState> = firmwareManager.state
    val firmwareProgress: StateFlow<Float> = firmwareManager.progress
    val firmwareError: StateFlow<String?> = firmwareManager.error

    private val _showFirmwareDialog = MutableStateFlow(false)
    val showFirmwareDialog: StateFlow<Boolean> = _showFirmwareDialog.asStateFlow()

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

    fun startFirmwareUpdate() {
        val update = firmwareUpdate.value ?: return
        viewModelScope.launch {
            firmwareManager.downloadAndPush(update) {}
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
                .onFailure {
                    _notice.value = "Could not start recording on the ground station"
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
                    _notice.value = if (err is CameraNotSupportedError) {
                        "This drone does not advertise multi-camera support"
                    } else {
                        "Could not switch the camera source"
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
                .onFailure {
                    _notice.value = "Could not stop recording on the ground station"
                }
        }
    }

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
