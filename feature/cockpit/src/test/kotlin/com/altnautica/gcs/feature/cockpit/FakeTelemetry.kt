package com.altnautica.gcs.feature.cockpit

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
import com.altnautica.gcs.core.model.Vfr
import com.altnautica.gcs.core.model.VibrationState
import com.altnautica.gcs.core.model.WindState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeTelemetry : VehicleTelemetry {
    override val attitude = MutableStateFlow<Attitude?>(null)
    override val position = MutableStateFlow<PositionFix?>(null)
    override val vfr = MutableStateFlow<Vfr?>(null)
    override val battery = MutableStateFlow<BatteryState?>(null)
    override val gps = MutableStateFlow<GpsState?>(null)
    override val heartbeat = MutableStateFlow<HeartbeatState?>(null)
    override val home = MutableStateFlow<HomePosition?>(null)
    override val wind = MutableStateFlow<WindState?>(null)
    override val ekf = MutableStateFlow<EkfState?>(null)
    override val vibration = MutableStateFlow<VibrationState?>(null)
    override val linkQuality = MutableStateFlow<LinkQuality?>(null)
    override val missionProgress = MutableStateFlow<MissionProgress?>(null)
    override val systemHealth = MutableStateFlow<SystemHealth?>(null)
    override val fence = MutableStateFlow<FenceStatus?>(null)
    override val obstacles = MutableStateFlow<ObstacleSectors?>(null)
    override val statusText = MutableSharedFlow<StatusText>(extraBufferCapacity = 16)
}

class FakeControl : VehicleControl {
    override val availableModes = MutableStateFlow(listOf(FlightMode(0, "STABILIZE"), FlightMode(5, "LOITER")))
    val sent = mutableListOf<VehicleCommand>()
    override suspend fun run(command: VehicleCommand): CommandResult {
        sent += command
        return CommandResult.Accepted
    }
}

class FakeVehicle(id: String = "drone-1") : Vehicle {
    override val id = DeviceId(id)
    override val reach = MutableStateFlow<Reach>(Reach.Lan)
    override val telemetry = FakeTelemetry()
    override val control = FakeControl()
}
