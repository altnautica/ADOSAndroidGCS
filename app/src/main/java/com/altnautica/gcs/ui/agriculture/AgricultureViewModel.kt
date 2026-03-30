package com.altnautica.gcs.ui.agriculture

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.data.agriculture.MissionGenerator
import com.altnautica.gcs.data.agriculture.Waypoint
import com.altnautica.gcs.data.mavlink.MavLinkMissionUploader
import com.altnautica.gcs.data.mavlink.UploadState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class LatLon(val lat: Double, val lon: Double)

data class FieldState(
    val boundaryPoints: List<LatLon> = emptyList(),
    val areaHectares: Double = 0.0,
)

enum class MissionState(val label: String) {
    IDLE("Idle"),
    MAPPING("Mapping"),
    CONFIGURED("Ready"),
    RUNNING("Spraying"),
    COMPLETE("Done"),
}

@HiltViewModel
class AgricultureViewModel @Inject constructor(
    private val missionUploader: MavLinkMissionUploader,
) : ViewModel() {

    private val _fieldState = MutableStateFlow(FieldState())
    val fieldState: StateFlow<FieldState> = _fieldState.asStateFlow()

    private val _sprayConfig = MutableStateFlow<SprayConfig?>(null)
    val sprayConfig: StateFlow<SprayConfig?> = _sprayConfig.asStateFlow()

    private val _missionState = MutableStateFlow(MissionState.IDLE)
    val missionState: StateFlow<MissionState> = _missionState.asStateFlow()

    private val _showSpraySheet = MutableStateFlow(false)
    val showSpraySheet: StateFlow<Boolean> = _showSpraySheet.asStateFlow()

    private val _showSummaryDialog = MutableStateFlow(false)
    val showSummaryDialog: StateFlow<Boolean> = _showSummaryDialog.asStateFlow()

    private val _missionWaypoints = MutableStateFlow<List<Waypoint>>(emptyList())
    val missionWaypoints: StateFlow<List<Waypoint>> = _missionWaypoints.asStateFlow()

    fun startMapping() {
        _missionState.value = MissionState.MAPPING
    }

    fun finishMapping(points: List<LatLon>, areaHa: Double) {
        _fieldState.value = FieldState(boundaryPoints = points, areaHectares = areaHa)
        _missionState.value = MissionState.IDLE
    }

    fun openSprayConfig() {
        _showSpraySheet.value = true
    }

    fun dismissSpraySheet() {
        _showSpraySheet.value = false
    }

    fun setSprayConfig(config: SprayConfig) {
        _sprayConfig.value = config
        _showSpraySheet.value = false
        _missionState.value = MissionState.CONFIGURED
    }

    fun startMission() {
        val boundary = _fieldState.value.boundaryPoints
        val config = _sprayConfig.value ?: return

        val waypoints = MissionGenerator.generateSprayMission(boundary, config)
        _missionWaypoints.value = waypoints
        _missionState.value = MissionState.RUNNING

        viewModelScope.launch {
            missionUploader.uploadMission(waypoints)
            missionUploader.uploadState.collect { state ->
                when (state) {
                    is UploadState.Complete -> {
                        _missionState.value = MissionState.COMPLETE
                    }
                    is UploadState.Error -> {
                        _missionState.value = MissionState.CONFIGURED
                    }
                    else -> { /* Idle, Uploading: no state change needed */ }
                }
            }
        }
    }

    fun viewSummary() {
        _showSummaryDialog.value = true
    }

    fun dismissSummaryDialog() {
        _showSummaryDialog.value = false
    }
}
