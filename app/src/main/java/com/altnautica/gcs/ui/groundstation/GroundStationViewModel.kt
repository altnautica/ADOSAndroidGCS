package com.altnautica.gcs.ui.groundstation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
)

data class SystemInfo(
    val hostname: String = "--",
    val ipAddress: String = "--",
    val cpuTempC: Int = 0,
    val uptime: String = "--",
    val wfbVersion: String = "--",
)

@HiltViewModel
class GroundStationViewModel @Inject constructor(
    private val repository: GroundStationRepository,
) : ViewModel() {

    private val _stats = MutableStateFlow(GroundStationStats())
    val stats: StateFlow<GroundStationStats> = _stats.asStateFlow()

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    private val _recordingStartTime = MutableStateFlow(0L)
    val recordingStartTime: StateFlow<Long> = _recordingStartTime.asStateFlow()

    private val _systemInfo = MutableStateFlow(SystemInfo())
    val systemInfo: StateFlow<SystemInfo> = _systemInfo.asStateFlow()

    init {
        repository.startPolling()
        collectRepositoryFlows()
    }

    private fun collectRepositoryFlows() {
        viewModelScope.launch {
            repository.wfbStats.collect { wfb ->
                _stats.value = GroundStationStats(
                    connected = repository.reachable.value,
                    rssiDbm = wfb.rssi,
                    packetLossPercent = wfb.packetLoss,
                    fecRecovered = wfb.fecRecovery.toInt(),
                    bitrateKbps = repository.videoStats.value.bitrate.toFloat(),
                )
            }
        }
        viewModelScope.launch {
            repository.systemInfo.collect { info ->
                _systemInfo.value = SystemInfo(
                    hostname = info.hostname.ifEmpty { "--" },
                    ipAddress = "--",
                    cpuTempC = info.socTemp.toInt(),
                    uptime = "--",
                    wfbVersion = info.firmwareVersion.ifEmpty { "--" },
                )
            }
        }
    }

    fun startRecording() {
        viewModelScope.launch {
            repository.startRecording().onSuccess {
                _recording.value = true
                _recordingStartTime.value = System.currentTimeMillis()
            }
        }
    }

    fun stopRecording() {
        viewModelScope.launch {
            repository.stopRecording().onSuccess {
                _recording.value = false
                _recordingStartTime.value = 0L
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        repository.stopPolling()
    }
}
