package com.altnautica.gcs.core.link.queue

import com.altnautica.gcs.core.model.Attitude
import com.altnautica.gcs.core.model.BatteryState
import com.altnautica.gcs.core.model.CommandResult
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.EkfState
import com.altnautica.gcs.core.model.FenceStatus
import com.altnautica.gcs.core.model.FlightMode
import com.altnautica.gcs.core.model.GpsState
import com.altnautica.gcs.core.model.HeartbeatState
import com.altnautica.gcs.core.model.HomePosition
import com.altnautica.gcs.core.model.LinkQuality
import com.altnautica.gcs.core.model.MissionProgress
import com.altnautica.gcs.core.model.ObstacleSectors
import com.altnautica.gcs.core.model.PositionFix
import com.altnautica.gcs.core.model.Reach
import com.altnautica.gcs.core.model.StatusText
import com.altnautica.gcs.core.model.SystemHealth
import com.altnautica.gcs.core.model.Vehicle
import com.altnautica.gcs.core.model.VehicleCommand
import com.altnautica.gcs.core.model.VehicleControl
import com.altnautica.gcs.core.model.VehicleTelemetry
import com.altnautica.gcs.core.model.VibrationState
import com.altnautica.gcs.core.model.Vfr
import com.altnautica.gcs.core.model.WindState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A vehicle reachable only through a [QueuedCommandSink]: no telemetry (every
 * topic stays null), no mode list, commands handed to the sink.
 */
class QueuedVehicle(
    override val id: DeviceId,
    reach: Reach,
    private val sink: QueuedCommandSink,
) : Vehicle {
    override val reach: StateFlow<Reach> = MutableStateFlow(reach).asStateFlow()
    override val telemetry: VehicleTelemetry = NoTelemetry
    override val control: VehicleControl = object : VehicleControl {
        override val availableModes: StateFlow<List<FlightMode>> = MutableStateFlow(emptyList<FlightMode>()).asStateFlow()
        override suspend fun run(command: VehicleCommand): CommandResult = sink.run(id, command)
    }

    private object NoTelemetry : VehicleTelemetry {
        override val attitude: StateFlow<Attitude?> = none()
        override val position: StateFlow<PositionFix?> = none()
        override val vfr: StateFlow<Vfr?> = none()
        override val battery: StateFlow<BatteryState?> = none()
        override val gps: StateFlow<GpsState?> = none()
        override val heartbeat: StateFlow<HeartbeatState?> = none()
        override val home: StateFlow<HomePosition?> = none()
        override val wind: StateFlow<WindState?> = none()
        override val ekf: StateFlow<EkfState?> = none()
        override val vibration: StateFlow<VibrationState?> = none()
        override val linkQuality: StateFlow<LinkQuality?> = none()
        override val missionProgress: StateFlow<MissionProgress?> = none()
        override val systemHealth: StateFlow<SystemHealth?> = none()
        override val fence: StateFlow<FenceStatus?> = none()
        override val obstacles: StateFlow<ObstacleSectors?> = none()
        override val statusText: SharedFlow<StatusText> = MutableSharedFlow<StatusText>().asSharedFlow()

        private fun <T> none(): StateFlow<T?> = MutableStateFlow<T?>(null).asStateFlow()
    }
}
