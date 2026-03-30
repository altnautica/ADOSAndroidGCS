package com.altnautica.gcs.ui.groundstation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
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
class GroundStationViewModel @Inject constructor() : ViewModel() {

    private val _stats = MutableStateFlow(GroundStationStats())
    val stats: StateFlow<GroundStationStats> = _stats.asStateFlow()

    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    private val _systemInfo = MutableStateFlow(SystemInfo())
    val systemInfo: StateFlow<SystemInfo> = _systemInfo.asStateFlow()

    init {
        startPolling()
    }

    private fun startPolling() {
        viewModelScope.launch {
            while (isActive) {
                // TODO: Poll GroundStationRepository for live stats
                // _stats.value = repository.fetchStats()
                // _systemInfo.value = repository.fetchSystemInfo()
                delay(2000)
            }
        }
    }

    fun startRecording() {
        _recording.value = true
        // TODO: Send recording start command to ground station
    }

    fun stopRecording() {
        _recording.value = false
        // TODO: Send recording stop command to ground station
    }
}
