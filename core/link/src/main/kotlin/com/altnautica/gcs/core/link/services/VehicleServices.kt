package com.altnautica.gcs.core.link.services

import com.altnautica.gcs.core.link.calibration.CalibrationService
import com.altnautica.gcs.core.link.log.LogService
import com.altnautica.gcs.core.link.manual.ManualControlStreamer
import com.altnautica.gcs.core.link.mission.MissionService
import com.altnautica.gcs.core.link.param.ParamService
import com.altnautica.gcs.core.mavlink.modes.FirmwareFamily
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.Vehicle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Protocol services of one MAVLink vehicle, alive while the vehicle is observed. */
interface VehicleServices {
    val vehicle: Vehicle

    /** Null until the first HEARTBEAT identifies the autopilot. */
    val firmware: StateFlow<FirmwareFamily?>
    val mission: MissionService
    val params: ParamService
    val calibration: CalibrationService
    val logs: LogService
    val manualControl: ManualControlStreamer
}

/**
 * Per-vehicle services. Emits null for unknown nodes and for vehicles the app
 * does not speak MAVLink to (MSP flight controllers seen through an agent).
 * Collecting keeps the link open, like [com.altnautica.gcs.core.model.VehicleProvider.vehicle].
 */
interface VehicleServicesProvider {
    fun services(id: DeviceId): Flow<VehicleServices?>
}
