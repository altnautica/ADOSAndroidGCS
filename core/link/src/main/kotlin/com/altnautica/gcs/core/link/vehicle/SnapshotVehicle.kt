package com.altnautica.gcs.core.link.vehicle

import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.agent.telemetry.SnapshotMapping
import com.altnautica.gcs.core.agent.telemetry.TelemetrySnapshotSource
import com.altnautica.gcs.core.link.mavlink.ElapsedClock
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A vehicle whose flight controller the app does not speak MAVLink to (MSP),
 * fed from the agent's telemetry snapshot at 5 Hz. Commands go through the
 * agent's command route. Topics the snapshot lacks stay null.
 */
class SnapshotVehicle(
    override val id: DeviceId,
    reach: Reach,
    parent: CoroutineScope,
    private val source: TelemetrySnapshotSource,
    clock: ElapsedClock,
) : Vehicle {

    private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))

    private val _reach = MutableStateFlow(reach)
    override val reach: StateFlow<Reach> = _reach.asStateFlow()

    private val snapshotTelemetry = SnapshotTelemetry()
    override val telemetry: VehicleTelemetry get() = snapshotTelemetry

    override val control: VehicleControl = object : VehicleControl {
        override val availableModes: StateFlow<List<FlightMode>> = MutableStateFlow(emptyList<FlightMode>()).asStateFlow()
        override suspend fun run(command: VehicleCommand): CommandResult = source.run(id, command)
    }

    init {
        scope.launch {
            source.snapshots(id).collect { r ->
                if (r is AgentResult.Ok) snapshotTelemetry.update(r.value, clock.nowMs())
            }
        }
    }

    internal fun setReach(reach: Reach) {
        _reach.value = reach
    }

    internal fun shutdown() = scope.cancel()

    private class SnapshotTelemetry : VehicleTelemetry {
        override val attitude = MutableStateFlow<Attitude?>(null)
        override val position = MutableStateFlow<PositionFix?>(null)
        override val vfr = MutableStateFlow<Vfr?>(null)
        override val battery = MutableStateFlow<BatteryState?>(null)
        override val gps = MutableStateFlow<GpsState?>(null)
        override val heartbeat = MutableStateFlow<HeartbeatState?>(null)
        override val home: StateFlow<HomePosition?> = MutableStateFlow(null)
        override val wind: StateFlow<WindState?> = MutableStateFlow(null)
        override val ekf: StateFlow<EkfState?> = MutableStateFlow(null)
        override val vibration: StateFlow<VibrationState?> = MutableStateFlow(null)
        override val linkQuality = MutableStateFlow<LinkQuality?>(null)
        override val missionProgress: StateFlow<MissionProgress?> = MutableStateFlow(null)
        override val systemHealth: StateFlow<SystemHealth?> = MutableStateFlow(null)
        override val fence: StateFlow<FenceStatus?> = MutableStateFlow(null)
        override val obstacles: StateFlow<ObstacleSectors?> = MutableStateFlow(null)
        private val texts = MutableSharedFlow<StatusText>(extraBufferCapacity = 16)
        override val statusText: SharedFlow<StatusText> = texts.asSharedFlow()

        fun update(s: com.altnautica.gcs.core.agent.model.TelemetrySnapshot, now: Long) {
            SnapshotMapping.attitude(s, now)?.let { attitude.value = it }
            SnapshotMapping.position(s, now)?.let { position.value = it }
            SnapshotMapping.vfr(s, now)?.let { vfr.value = it }
            SnapshotMapping.battery(s, now)?.let { battery.value = it }
            SnapshotMapping.gps(s, now)?.let { gps.value = it }
            SnapshotMapping.heartbeat(s, now)?.let { heartbeat.value = it }
            linkQuality.value = LinkQuality(null, null, heartbeat.value?.let { now - it.tMs } ?: Long.MAX_VALUE)
        }
    }
}
