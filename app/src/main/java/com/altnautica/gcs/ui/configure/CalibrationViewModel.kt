package com.altnautica.gcs.ui.configure

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.altnautica.gcs.data.mavlink.MavLinkCommandSender
import com.altnautica.gcs.data.telemetry.MagCalProgressState
import com.altnautica.gcs.data.telemetry.MagCalReportState
import com.altnautica.gcs.data.telemetry.TelemetryStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class AccelCalState {
    data object Idle : AccelCalState()
    data class InProgress(val step: Int) : AccelCalState() // 0-5
    data object Complete : AccelCalState()
    data class Failed(val message: String) : AccelCalState()
}

sealed class CompassCalState {
    data object Idle : CompassCalState()
    data class InProgress(val pct: Float = 0f) : CompassCalState()
    data object Complete : CompassCalState()
    data class Failed(val message: String) : CompassCalState()
}

sealed class LevelCalState {
    data object Idle : LevelCalState()
    data object InProgress : LevelCalState()
    data object Complete : LevelCalState()
    data class Failed(val message: String) : LevelCalState()
}

@HiltViewModel
class CalibrationViewModel @Inject constructor(
    private val commandSender: MavLinkCommandSender,
    private val telemetryStore: TelemetryStore,
) : ViewModel() {

    companion object {
        val ACCEL_STEPS = listOf(
            "Level" to "Place the drone on a flat, level surface.",
            "Nose Down" to "Point the nose straight down (vertical).",
            "Nose Up" to "Point the nose straight up (vertical).",
            "Left Side" to "Roll the drone onto its left side.",
            "Right Side" to "Roll the drone onto its right side.",
            "Inverted" to "Flip the drone upside down on a flat surface.",
        )
    }

    private val _accelState = MutableStateFlow<AccelCalState>(AccelCalState.Idle)
    val accelState: StateFlow<AccelCalState> = _accelState.asStateFlow()

    private val _compassState = MutableStateFlow<CompassCalState>(CompassCalState.Idle)
    val compassState: StateFlow<CompassCalState> = _compassState.asStateFlow()

    private val _levelState = MutableStateFlow<LevelCalState>(LevelCalState.Idle)
    val levelState: StateFlow<LevelCalState> = _levelState.asStateFlow()

    val magCalProgress: StateFlow<MagCalProgressState?> = telemetryStore.magCalProgress
    val magCalReport: StateFlow<MagCalReportState?> = telemetryStore.magCalReport
    val statusMessages: StateFlow<List<String>> = telemetryStore.statusMessages

    fun startAccelCal() {
        _accelState.value = AccelCalState.InProgress(step = 0)
        viewModelScope.launch {
            try {
                commandSender.sendCalibrationAccel()
            } catch (e: Exception) {
                _accelState.value = AccelCalState.Failed(e.message ?: "Failed to start calibration")
            }
        }
    }

    fun advanceAccelStep() {
        val current = _accelState.value
        if (current is AccelCalState.InProgress) {
            val nextStep = current.step + 1
            if (nextStep >= ACCEL_STEPS.size) {
                _accelState.value = AccelCalState.Complete
            } else {
                _accelState.value = AccelCalState.InProgress(step = nextStep)
            }
        }
    }

    fun onAccelCalStatusMessage(message: String) {
        if (message.contains("Calibration successful", ignoreCase = true) ||
            message.contains("Calibration complete", ignoreCase = true)
        ) {
            _accelState.value = AccelCalState.Complete
        } else if (message.contains("Calibration FAILED", ignoreCase = true)) {
            _accelState.value = AccelCalState.Failed("Accelerometer calibration failed")
        }
    }

    fun resetAccelCal() {
        _accelState.value = AccelCalState.Idle
    }

    fun startCompassCal() {
        _compassState.value = CompassCalState.InProgress(pct = 0f)
        telemetryStore.clearMagCal()
        viewModelScope.launch {
            try {
                commandSender.sendStartMagCal()
            } catch (e: Exception) {
                _compassState.value = CompassCalState.Failed(e.message ?: "Failed to start calibration")
            }
        }
    }

    fun onCompassProgress(progress: MagCalProgressState) {
        val current = _compassState.value
        if (current is CompassCalState.InProgress) {
            _compassState.value = CompassCalState.InProgress(pct = progress.completionPct)
        }
    }

    fun onCompassReport(report: MagCalReportState) {
        if (report.isSuccess) {
            _compassState.value = CompassCalState.Complete
        } else if (report.isFailed) {
            _compassState.value = CompassCalState.Failed("Compass calibration failed (fitness: %.2f)".format(report.fitness))
        }
    }

    fun resetCompassCal() {
        _compassState.value = CompassCalState.Idle
        telemetryStore.clearMagCal()
    }

    fun startLevelCal() {
        _levelState.value = LevelCalState.InProgress
        viewModelScope.launch {
            try {
                commandSender.sendCalibrationLevel()
            } catch (e: Exception) {
                _levelState.value = LevelCalState.Failed(e.message ?: "Failed to start calibration")
            }
        }
    }

    fun onLevelCalStatusMessage(message: String) {
        if (message.contains("Calibration successful", ignoreCase = true) ||
            message.contains("Calibration complete", ignoreCase = true)
        ) {
            _levelState.value = LevelCalState.Complete
        } else if (message.contains("Calibration FAILED", ignoreCase = true)) {
            _levelState.value = LevelCalState.Failed("Level calibration failed")
        }
    }

    fun resetLevelCal() {
        _levelState.value = LevelCalState.Idle
    }
}
