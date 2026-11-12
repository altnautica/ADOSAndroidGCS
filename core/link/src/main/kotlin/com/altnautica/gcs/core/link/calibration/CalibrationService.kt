package com.altnautica.gcs.core.link.calibration

import com.altnautica.gcs.core.model.CommandResult
import kotlinx.coroutines.flow.StateFlow

/**
 * [Esc] sets the ESC throttle endpoints, [CompassMot] measures motor interference on the
 * compass (ArduPilot), [Airspeed] zeroes the airspeed sensor.
 */
enum class CalibrationKind { Gyro, Level, Accel, Compass, Radio, Esc, CompassMot, Airspeed }

/** Vehicle orientations requested by the six-position accelerometer calibration (ACCELCAL_VEHICLE_POS). */
enum class AccelPose(val wire: Int) {
    Level(1),
    LeftSide(2),
    RightSide(3),
    NoseDown(4),
    NoseUp(5),
    Back(6),
    ;

    companion object {
        fun of(wire: Int): AccelPose? = entries.firstOrNull { it.wire == wire }
    }
}

/** MAG_CAL_STATUS of one compass. */
enum class CompassStatus { NotStarted, WaitingToStart, Running, Success, Failed, BadOrientation, BadRadius }

data class CompassProgress(val compassId: Int, val completionPct: Int, val status: CompassStatus)

data class CompassReport(val compassId: Int, val status: CompassStatus, val fitness: Float, val autosaved: Boolean)

data class RcChannelRange(val channel: Int, val current: Int, val min: Int, val max: Int)

sealed interface CalibrationState {
    data object Idle : CalibrationState

    /** Calibration in progress; [message] is the newest autopilot status text. */
    data class Running(val kind: CalibrationKind, val message: String?) : CalibrationState

    /** Accelerometer calibration waits for the operator to place the vehicle and confirm. */
    data class AccelPosition(val pose: AccelPose, val message: String?) : CalibrationState

    /** Onboard compass calibration: live progress, then one report per compass awaiting accept/cancel. */
    data class Compass(
        val progress: List<CompassProgress>,
        val reports: List<CompassReport>,
        val readyToAccept: Boolean,
    ) : CalibrationState

    /** Radio capture: move every stick and switch through its full range, then save. */
    data class Radio(val channels: List<RcChannelRange>) : CalibrationState
    data class Succeeded(val kind: CalibrationKind, val message: String?) : CalibrationState
    data class Failed(val kind: CalibrationKind, val reason: String) : CalibrationState
}

/** Sensor and radio calibration flows. One flow runs at a time. */
interface CalibrationService {
    val state: StateFlow<CalibrationState>

    suspend fun startGyro(): CommandResult
    suspend fun startLevel(): CommandResult
    suspend fun startAccel(): CommandResult

    /** The vehicle is in the requested [CalibrationState.AccelPosition.pose]. */
    suspend fun confirmAccelPosition(): CommandResult
    suspend fun startCompass(): CommandResult
    suspend fun acceptCompass(): CommandResult
    suspend fun cancelCompass(): CommandResult

    /** ESC throttle calibration (`MAV_CMD_PREFLIGHT_CALIBRATION` p7=1). Propellers off. */
    suspend fun startEsc(): CommandResult

    /** Compass-motor interference calibration (p6=1); ArduPilot only, refused elsewhere without sending. */
    suspend fun startCompassMot(): CommandResult

    /** Airspeed sensor zero (p6=2). Cover the pitot tube. */
    suspend fun startAirspeed(): CommandResult

    fun startRadio()

    /** Writes the captured `RCn_MIN/MAX/TRIM`; trims are the stick positions now (throttle: its minimum). */
    suspend fun saveRadio(): CommandResult

    /** Abandons the running flow (compass is cancelled on the vehicle) and returns to [CalibrationState.Idle]. */
    suspend fun cancel()
}
