package com.altnautica.gcs.ui.agriculture

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

data class LatLon(val lat: Double, val lon: Double)

data class FieldState(
    val boundaryPoints: List<LatLon> = emptyList(),
    val areaHectares: Double = 0.0,
)

data class SprayConfig(
    val flowRateLPerHa: Float = 10f,
    val swathWidthM: Float = 5f,
    val altitudeM: Float = 3f,
    val speedMs: Float = 3f,
)

enum class MissionState(val label: String) {
    IDLE("Idle"),
    MAPPING("Mapping"),
    CONFIGURED("Ready"),
    RUNNING("Spraying"),
    COMPLETE("Done"),
}

@HiltViewModel
class AgricultureViewModel @Inject constructor() : ViewModel() {

    private val _fieldState = MutableStateFlow(FieldState())
    val fieldState: StateFlow<FieldState> = _fieldState.asStateFlow()

    private val _sprayConfig = MutableStateFlow(SprayConfig())
    val sprayConfig: StateFlow<SprayConfig> = _sprayConfig.asStateFlow()

    private val _missionState = MutableStateFlow(MissionState.IDLE)
    val missionState: StateFlow<MissionState> = _missionState.asStateFlow()

    fun startMapping() {
        _missionState.value = MissionState.MAPPING
        // TODO: Launch FieldMapper flow
    }

    fun finishMapping(points: List<LatLon>, areaHa: Double) {
        _fieldState.value = FieldState(boundaryPoints = points, areaHectares = areaHa)
        _missionState.value = MissionState.IDLE
    }

    fun openSprayConfig() {
        // TODO: Show spray configuration bottom sheet
    }

    fun updateSprayConfig(config: SprayConfig) {
        _sprayConfig.value = config
        _missionState.value = MissionState.CONFIGURED
    }

    fun startMission() {
        _missionState.value = MissionState.RUNNING
        // TODO: Generate waypoints from field boundary + spray config, upload to FC
    }

    fun viewSummary() {
        // TODO: Navigate to mission summary screen
    }
}
