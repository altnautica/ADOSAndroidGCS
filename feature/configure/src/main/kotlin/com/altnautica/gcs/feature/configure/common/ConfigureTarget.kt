package com.altnautica.gcs.feature.configure.common

import com.altnautica.gcs.core.link.calibration.CalibrationService
import com.altnautica.gcs.core.link.param.ParamService
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.FcVariant
import com.altnautica.gcs.core.model.HeartbeatState
import com.altnautica.gcs.core.model.Vehicle
import kotlinx.coroutines.flow.Flow

/** The protocol services the configure screens drive for one MAVLink vehicle. */
class ConfigureTarget(
    val vehicle: Vehicle,
    val params: ParamService,
    val calibration: CalibrationService,
)

/** Resolves the configure services of a node; emits null while the node has no open MAVLink link. */
fun interface ConfigureTargetSource {
    fun observe(id: DeviceId): Flow<ConfigureTarget?>
}

private const val MAV_AUTOPILOT_ARDUPILOTMEGA = 3
private const val MAV_AUTOPILOT_PX4 = 12

/** Firmware family from the autopilot field of the vehicle's HEARTBEAT. */
fun HeartbeatState.fcVariant(): FcVariant = when (autopilot) {
    MAV_AUTOPILOT_ARDUPILOTMEGA -> FcVariant.MavlinkArdupilot
    MAV_AUTOPILOT_PX4 -> FcVariant.MavlinkPx4
    else -> FcVariant.Unknown
}

/** Link freshness rule shared with the cockpit: a heartbeat older than 3 s is stale. */
const val LINK_FRESH_MS = 3_000L
