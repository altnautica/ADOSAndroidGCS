package com.altnautica.gcs.core.model

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

sealed interface VehicleCommand {
    data class Arm(val force: Boolean = false) : VehicleCommand
    data class Disarm(val force: Boolean = false) : VehicleCommand
    data class Takeoff(val altM: Float) : VehicleCommand
    data object Land : VehicleCommand
    data object ReturnToLaunch : VehicleCommand
    data class SetMode(val mode: FlightMode) : VehicleCommand
    data object PauseMission : VehicleCommand
    data object ResumeMission : VehicleCommand
    data object Kill : VehicleCommand
    data class FlyTo(val latDeg: Double, val lonDeg: Double, val altRelM: Float) : VehicleCommand
    data class MotorTest(val motor: Int, val throttlePct: Float, val durationS: Float) : VehicleCommand
    data object RebootAutopilot : VehicleCommand

    /** `MAV_CMD_SET_EKF_SOURCE_SET`: make EKF3 source set [set] (1-3) active. */
    data class SetEkfSourceSet(val set: Int) : VehicleCommand

    /** Gimbal pitch and yaw rates in deg/s (`MAV_CMD_DO_GIMBAL_MANAGER_PITCHYAW`). */
    data class GimbalRate(val pitchDps: Float, val yawDps: Float) : VehicleCommand

    /** Gimbal back to level and forward. */
    data object GimbalRecenter : VehicleCommand

    /** One zoom step in ([step] > 0) or out (`MAV_CMD_SET_CAMERA_ZOOM`, step type). */
    data class CameraZoom(val step: Int) : VehicleCommand

    /** One still image (`MAV_CMD_IMAGE_START_CAPTURE`). */
    data object CapturePhoto : VehicleCommand
}

sealed interface CommandResult {
    data object Accepted : CommandResult
    data class Rejected(val code: Int, val reason: String) : CommandResult
    data object Unsupported : CommandResult
    data object Timeout : CommandResult
    data class Denied(val reason: String) : CommandResult

    /** Queued for later delivery (cloud command queue); not yet accepted by the vehicle. */
    data class Queued(val receiptId: String) : CommandResult
}

interface VehicleControl {
    val availableModes: StateFlow<List<FlightMode>>
    suspend fun run(command: VehicleCommand): CommandResult
}

interface Vehicle {
    val id: DeviceId
    val reach: StateFlow<Reach>
    val telemetry: VehicleTelemetry
    val control: VehicleControl
}

interface VehicleProvider {
    fun vehicle(id: DeviceId): Flow<Vehicle?>
}
