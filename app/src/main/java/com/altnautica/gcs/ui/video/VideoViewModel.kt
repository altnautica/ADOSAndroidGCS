package com.altnautica.gcs.ui.video

import androidx.lifecycle.ViewModel
import com.altnautica.gcs.data.telemetry.AttitudeState
import com.altnautica.gcs.data.telemetry.BatteryState
import com.altnautica.gcs.data.telemetry.FlightMode
import com.altnautica.gcs.data.telemetry.GpsState
import com.altnautica.gcs.data.telemetry.PositionState
import com.altnautica.gcs.data.telemetry.TelemetryStore
import com.altnautica.gcs.data.telemetry.VfrState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

enum class VideoMode(val label: String) {
    MODE_A("A"),  // Ground station WebRTC/WHEP
    MODE_B("B"),  // Direct USB WFB-ng
    MODE_C("C"),  // Cloud relay TURN
    NONE("--"),   // No video source
}

@HiltViewModel
class VideoViewModel @Inject constructor(
    private val telemetryStore: TelemetryStore,
) : ViewModel() {

    val attitude: StateFlow<AttitudeState> = telemetryStore.attitude
    val position: StateFlow<PositionState> = telemetryStore.position
    val battery: StateFlow<BatteryState> = telemetryStore.battery
    val gps: StateFlow<GpsState> = telemetryStore.gps
    val vfr: StateFlow<VfrState> = telemetryStore.vfr
    val flightMode: StateFlow<FlightMode?> = telemetryStore.flightMode
    val armed: StateFlow<Boolean> = telemetryStore.armed

    // Video mode is determined by VideoStreamManager (not yet implemented)
    private val _videoMode = MutableStateFlow(VideoMode.NONE)
    val videoMode: StateFlow<VideoMode> = _videoMode.asStateFlow()

    fun setVideoMode(mode: VideoMode) {
        _videoMode.value = mode
    }
}
