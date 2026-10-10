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
