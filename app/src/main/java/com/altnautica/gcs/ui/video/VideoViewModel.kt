package com.altnautica.gcs.ui.video

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.data.telemetry.AttitudeState
import com.altnautica.gcs.data.telemetry.BatteryState
import com.altnautica.gcs.data.telemetry.FlightMode
import com.altnautica.gcs.data.telemetry.GpsState
import com.altnautica.gcs.data.telemetry.PositionState
import com.altnautica.gcs.data.telemetry.TelemetryStore
import com.altnautica.gcs.data.telemetry.VfrState
import com.altnautica.gcs.data.video.VideoMode
import com.altnautica.gcs.data.video.VideoStreamManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class VideoViewModel @Inject constructor(
    private val telemetryStore: TelemetryStore,
    private val videoStreamManager: VideoStreamManager,
) : ViewModel() {

    val attitude: StateFlow<AttitudeState> = telemetryStore.attitude
    val position: StateFlow<PositionState> = telemetryStore.position
    val homePosition: StateFlow<PositionState?> = telemetryStore.homePosition
    val battery: StateFlow<BatteryState> = telemetryStore.battery
    val gps: StateFlow<GpsState> = telemetryStore.gps
    val vfr: StateFlow<VfrState> = telemetryStore.vfr
    val flightMode: StateFlow<FlightMode?> = telemetryStore.flightMode
    val armed: StateFlow<Boolean> = telemetryStore.armed

    val videoMode: StateFlow<VideoMode> = videoStreamManager.activeMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), VideoMode.NoConnection)

    val isStreaming: StateFlow<Boolean> = videoStreamManager.isStreaming
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
}
