package com.altnautica.gcs.core.agent.telemetry

import com.altnautica.gcs.core.agent.AgentClientFactory
import com.altnautica.gcs.core.agent.http.AgentError
import com.altnautica.gcs.core.agent.http.AgentResult
import com.altnautica.gcs.core.agent.model.TelemetrySnapshot
import com.altnautica.gcs.core.model.Attitude
import com.altnautica.gcs.core.model.BatteryState
import com.altnautica.gcs.core.model.CommandResult
import com.altnautica.gcs.core.model.DeviceId
import com.altnautica.gcs.core.model.FlightMode
import com.altnautica.gcs.core.model.GpsFix
import com.altnautica.gcs.core.model.GpsState
import com.altnautica.gcs.core.model.HeartbeatState
import com.altnautica.gcs.core.model.PositionFix
import com.altnautica.gcs.core.model.VehicleCommand
import com.altnautica.gcs.core.model.Vfr
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonPrimitive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The agent's own vehicle snapshot (`GET /api/telemetry`) for flight
 * controllers the app does not speak MAVLink to (MSP). Polls at 5 Hz while
 * collected; commands go through `POST /api/command`.
 */
@Singleton
class TelemetrySnapshotSource @Inject constructor(
    private val clients: AgentClientFactory,
) {

    /** One result per poll; an [AgentResult.Err] does not end the flow. */
    fun snapshots(node: DeviceId): Flow<AgentResult<TelemetrySnapshot>> = flow {
        while (true) {
            val client = clients.forNode(node)
            emit(client?.telemetry() ?: AgentResult.Err(AgentError.Unauthorized))
            delay(INTERVAL_MS)
        }
    }

    /** Run [command] through the agent's command route. */
    suspend fun run(node: DeviceId, command: VehicleCommand): CommandResult {
        val (cmd, args) = SnapshotMapping.commandOf(command) ?: return CommandResult.Unsupported
        val client = clients.forNode(node) ?: return CommandResult.Denied("not paired")
        return when (val r = client.command(cmd, args)) {
            is AgentResult.Ok ->
                if (r.value.accepted) {
                    CommandResult.Accepted
                } else {
                    CommandResult.Rejected(r.value.ack?.result ?: -1, r.value.message ?: r.value.ack?.resultName ?: "rejected")
                }
            is AgentResult.Err -> when (val e = r.error) {
                is AgentError.Network -> CommandResult.Timeout
                is AgentError.Unauthorized -> CommandResult.Denied("not authorized")
                is AgentError.PinRequired -> CommandResult.Denied("PIN required")
                is AgentError.FeatureAbsent -> CommandResult.Unsupported
                is AgentError.Server -> CommandResult.Rejected(e.code, e.message ?: "agent error")
                else -> CommandResult.Rejected(-1, e.toString())
            }
        }
    }

    companion object {
        const val INTERVAL_MS = 200L
    }
}

/** Projects an agent snapshot onto the shared telemetry model. [tMs] is the receive time. */
object SnapshotMapping {

    /** The snapshot carries the mode name only; the numeric custom mode is not known. */
    const val UNKNOWN_CUSTOM_MODE = -1L

    fun attitude(s: TelemetrySnapshot, tMs: Long): Attitude? {
        val a = s.attitude ?: return null
        return Attitude((a.roll ?: return null).toFloat(), (a.pitch ?: return null).toFloat(), (a.yaw ?: return null).toFloat(), tMs)
    }

    fun position(s: TelemetrySnapshot, tMs: Long): PositionFix? {
        val p = s.position ?: return null
        val lat = p.lat ?: return null
        val lon = p.lon ?: return null
        if (lat == 0.0 && lon == 0.0) return null
        val v = s.velocity
        return PositionFix(
            latDeg = lat,
            lonDeg = lon,
            altMslM = (p.altMsl ?: 0.0).toFloat(),
            altRelM = (p.altRel ?: 0.0).toFloat(),
            vxMs = (v?.vx ?: 0.0).toFloat(),
            vyMs = (v?.vy ?: 0.0).toFloat(),
            vzMs = (v?.vz ?: 0.0).toFloat(),
            tMs = tMs,
        )
    }

    fun vfr(s: TelemetrySnapshot, tMs: Long): Vfr? {
        val v = s.velocity ?: return null
        return Vfr(
            airspeedMs = (v.airspeed ?: 0.0).toFloat(),
            groundspeedMs = (v.groundspeed ?: return null).toFloat(),
            headingDeg = (s.position?.heading ?: 0.0).toFloat(),
            throttlePct = s.throttle ?: 0,
            climbMs = (v.climb ?: 0.0).toFloat(),
            tMs = tMs,
        )
    }

    fun battery(s: TelemetrySnapshot, tMs: Long): BatteryState? {
        val b = s.battery ?: return null
        if (b.voltage == null && b.remaining == null) return null
        return BatteryState(
            id = 0,
            voltageV = b.voltage?.toFloat(),
            currentA = b.current?.toFloat(),
            remainingPct = b.remaining?.takeIf { it in 0..100 },
            timeToReserveS = null,
            tMs = tMs,
        )
    }

    fun gps(s: TelemetrySnapshot, tMs: Long): GpsState? {
        val g = s.gps ?: return null
        val fix = when (g.fixType) {
            null, 0 -> GpsFix.NoGps
            1 -> GpsFix.NoFix
            2 -> GpsFix.Fix2D
            3 -> GpsFix.Fix3D
            4 -> GpsFix.DGps
            5 -> GpsFix.RtkFloat
            else -> GpsFix.RtkFixed
        }
        return GpsState(fix, g.satellites, g.eph?.toFloat(), tMs)
    }

    fun heartbeat(s: TelemetrySnapshot, tMs: Long): HeartbeatState? {
        if (s.mavlinkAlive == false) return null
        val armed = s.armed ?: return null
        return HeartbeatState(
            armed = armed,
            mode = FlightMode(UNKNOWN_CUSTOM_MODE, s.mode.orEmpty()),
            vehicleType = s.mavType ?: 0,
            autopilot = s.autopilot ?: 0,
            tMs = tMs,
        )
    }

    /** The `/api/command` verb and args for [command], or null when the route has no equivalent. */
    fun commandOf(command: VehicleCommand): Pair<String, List<JsonPrimitive>>? = when (command) {
        is VehicleCommand.Arm -> "arm" to emptyList()
        is VehicleCommand.Disarm -> "disarm" to emptyList()
        is VehicleCommand.Takeoff -> "takeoff" to listOf(JsonPrimitive(command.altM))
        VehicleCommand.Land -> "land" to emptyList()
        VehicleCommand.ReturnToLaunch -> "rtl" to emptyList()
        VehicleCommand.Kill -> "killSwitch" to emptyList()
        VehicleCommand.PauseMission -> "pauseMission" to emptyList()
        VehicleCommand.ResumeMission -> "resumeMission" to emptyList()
        is VehicleCommand.SetMode -> "mode" to listOf(JsonPrimitive(command.mode.name))
        is VehicleCommand.FlyTo, is VehicleCommand.MotorTest -> null
    }
}
