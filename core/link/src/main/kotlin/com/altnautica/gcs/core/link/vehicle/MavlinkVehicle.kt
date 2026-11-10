package com.altnautica.gcs.core.link.vehicle

import com.altnautica.gcs.core.link.calibration.CalibrationService
import com.altnautica.gcs.core.link.calibration.MavlinkCalibrationService
import com.altnautica.gcs.core.link.command.CommandService
import com.altnautica.gcs.core.link.log.LogService
import com.altnautica.gcs.core.link.log.MavlinkLogService
import com.altnautica.gcs.core.link.manual.ManualControlStreamer
import com.altnautica.gcs.core.link.manual.MavlinkManualControlStreamer
import com.altnautica.gcs.core.link.mavlink.ElapsedClock
import com.altnautica.gcs.core.link.mavlink.MavlinkLink
import com.altnautica.gcs.core.link.mavlink.VehicleTarget
import com.altnautica.gcs.core.link.mavlink.TransportMavlinkLink
import com.altnautica.gcs.core.link.mission.MavlinkMissionService
import com.altnautica.gcs.core.link.mission.MissionService
import com.altnautica.gcs.core.link.param.MavlinkParamService
import com.altnautica.gcs.core.link.param.ParamService
import com.altnautica.gcs.core.link.services.VehicleServices
import com.altnautica.gcs.core.mavlink.enums.MavCmd
import com.altnautica.gcs.core.mavlink.enums.MavDataStream
import com.altnautica.gcs.core.mavlink.messages.CommandLong
import com.altnautica.gcs.core.mavlink.messages.HomePosition
import com.altnautica.gcs.core.mavlink.messages.GpsGlobalOrigin
import com.altnautica.gcs.core.mavlink.messages.RequestDataStream
import com.altnautica.gcs.core.mavlink.messages.ExtendedSysState
import com.altnautica.gcs.core.mavlink.modes.FirmwareFamily
import com.altnautica.gcs.core.model.BlockReason
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.Reach
import com.altnautica.gcs.core.model.TransportState
import com.altnautica.gcs.core.model.Vehicle
import com.altnautica.gcs.core.model.VehicleControl
import com.altnautica.gcs.core.model.VehicleTelemetry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * A vehicle spoken to over MAVLink. Telemetry and services survive transport
 * switches: [link] swaps the byte pipe underneath while the state flows stay.
 */
class MavlinkVehicle(
    override val id: DeviceId,
    parent: CoroutineScope,
    clock: ElapsedClock,
    activity: LinkActivity?,
) : Vehicle, VehicleServices {

    internal val scope = CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))

    val link = TransportMavlinkLink(scope, clock)

    private val _reach = MutableStateFlow<Reach>(Reach.Blocked(BlockReason.Offline))
    override val reach: StateFlow<Reach> = _reach.asStateFlow()

    private val mavTelemetry = MavlinkVehicleTelemetry(scope, link, clock)
    override val telemetry: VehicleTelemetry get() = mavTelemetry

    val commands = CommandService(scope, link, mavTelemetry.heartbeat, mavTelemetry.home)
    override val control: VehicleControl get() = commands

    override val vehicle: Vehicle get() = this
    override val firmware: StateFlow<FirmwareFamily?> = link.target
        .map { it?.family }
        .stateIn(scope, SharingStarted.Eagerly, null)

    override val params: ParamService = MavlinkParamService(scope, link)
    override val mission: MissionService =
        MavlinkMissionService(link, commands, mavTelemetry.home) { mavTelemetry.knownMissionCount = it }
    override val calibration: CalibrationService = MavlinkCalibrationService(scope, link, commands, params)
    override val logs: LogService = MavlinkLogService(link)
    override val manualControl: ManualControlStreamer = MavlinkManualControlStreamer(scope, link)

    init {
        if (activity != null) {
            scope.launch {
                mavTelemetry.heartbeat.filterNotNull().collect { activity.heartbeat(id, it.tMs, it.autopilot) }
            }
        }
        // Ask for streams and the home position whenever a vehicle is identified on an open transport,
        // and again when it is heard after a silence (a reboot forgets the message intervals).
        scope.launch {
            combine(link.target, link.transportState, link.targetSession) { t, s, n -> if (s == TransportState.Open && t != null) t to n else null }
                .distinctUntilChanged()
                .collectLatest { session ->
                    val t = session?.first ?: return@collectLatest
                    link.requestStreams(t)
                    // Until a home arrives, ask for it and for the EKF origin, either of which anchors the vehicle.
                    while (mavTelemetry.home.value == null) {
                        for (id in if (mavTelemetry.ekfOriginSet.value) HOME_ONLY else HOME_AND_ORIGIN) {
                            link.send(
                                CommandLong(
                                    targetSystem = t.sysId,
                                    targetComponent = t.compId,
                                    command = MavCmd.MAV_CMD_REQUEST_MESSAGE,
                                    param1 = id.toFloat(),
                                ),
                            )
                        }
                        delay(HOME_REQUEST_PERIOD_MS)
                    }
                }
        }
    }

    internal fun setReach(reach: Reach) {
        _reach.value = reach
    }

    internal suspend fun shutdown() {
        manualControl.stop()
        link.detach()
        scope.cancel()
    }

    private companion object {
        const val HOME_REQUEST_PERIOD_MS = 5_000L
        val HOME_ONLY = intArrayOf(HomePosition.ID)
        val HOME_AND_ORIGIN = intArrayOf(HomePosition.ID, GpsGlobalOrigin.ID)
    }
}

/** ArduPilot stream groups and rates (Hz). */
private val STREAM_RATES = listOf(
    MavDataStream.MAV_DATA_STREAM_EXTENDED_STATUS to 2,
    MavDataStream.MAV_DATA_STREAM_POSITION to 5,
    MavDataStream.MAV_DATA_STREAM_EXTRA1 to 10,
    MavDataStream.MAV_DATA_STREAM_EXTRA2 to 5,
    MavDataStream.MAV_DATA_STREAM_EXTRA3 to 2,
    MavDataStream.MAV_DATA_STREAM_RC_CHANNELS to 2,
)

private const val EXTENDED_SYS_STATE_INTERVAL_US = 1_000_000f

/**
 * Asks [t] for its telemetry: ArduPilot stream groups, and `EXTENDED_SYS_STATE` at 1 Hz for the
 * landed state. The interval request is sent once without waiting; an FC that refuses or ignores
 * it simply leaves the landed state unknown.
 */
internal suspend fun MavlinkLink.requestStreams(t: VehicleTarget) {
    if (t.family != FirmwareFamily.Px4) {
        STREAM_RATES.forEach { (stream, hz) -> send(RequestDataStream(t.sysId, t.compId, stream, hz, 1)) }
    }
    send(
        CommandLong(
            targetSystem = t.sysId,
            targetComponent = t.compId,
            command = MavCmd.MAV_CMD_SET_MESSAGE_INTERVAL,
            param1 = ExtendedSysState.ID.toFloat(),
            param2 = EXTENDED_SYS_STATE_INTERVAL_US,
        ),
    )
}
