package com.altnautica.gcs.core.link.command

import com.altnautica.gcs.core.link.mavlink.MavlinkLink
import com.altnautica.gcs.core.link.mavlink.VehicleTarget
import com.altnautica.gcs.core.link.mavlink.messagesOf
import com.altnautica.gcs.core.mavlink.enums.MavCmd
import com.altnautica.gcs.core.mavlink.enums.MavFrame
import com.altnautica.gcs.core.mavlink.enums.MavModeFlag
import com.altnautica.gcs.core.mavlink.enums.MavResult
import com.altnautica.gcs.core.mavlink.enums.MavType
import com.altnautica.gcs.core.mavlink.messages.CommandAck
import com.altnautica.gcs.core.mavlink.messages.CommandInt
import com.altnautica.gcs.core.mavlink.messages.CommandLong
import com.altnautica.gcs.core.mavlink.messages.MavMessage
import com.altnautica.gcs.core.mavlink.modes.FirmwareFamily
import com.altnautica.gcs.core.mavlink.modes.FlightModeTables
import com.altnautica.gcs.core.model.CommandResult
import com.altnautica.gcs.core.model.FlightMode
import com.altnautica.gcs.core.model.HeartbeatState
import com.altnautica.gcs.core.model.HomePosition
import com.altnautica.gcs.core.model.VehicleCommand
import com.altnautica.gcs.core.model.VehicleControl
import com.altnautica.gcs.core.model.WriteResult
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * [VehicleControl] over MAVLink commands.
 *
 * Every command goes out as `COMMAND_LONG` (or `COMMAND_INT` for positions)
 * and waits for the `COMMAND_ACK` naming the same command: 1.5 s per attempt,
 * three retries with `confirmation` incremented, and `MAV_RESULT_IN_PROGRESS`
 * held as pending for up to 10 s until the final ACK.
 */
class CommandService(
    scope: CoroutineScope,
    private val link: MavlinkLink,
    private val heartbeat: StateFlow<HeartbeatState?>,
    private val home: StateFlow<HomePosition?>,
) : VehicleControl {

    override val availableModes: StateFlow<List<FlightMode>> = link.target
        .map { t -> t?.let { FlightModeTables.available(it.family) } ?: emptyList() }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** One in-flight exchange per command id: the ACK only names the command. */
    private val locks = ConcurrentHashMap<Int, Mutex>()

    override suspend fun run(command: VehicleCommand): CommandResult {
        val target = link.target.value ?: return CommandResult.Denied(REASON_NO_LINK)
        val family = target.family
        return when (command) {
            is VehicleCommand.Arm -> long(MavCmd.MAV_CMD_COMPONENT_ARM_DISARM, 1f, if (command.force) FORCE_ARM else 0f)
            is VehicleCommand.Disarm -> long(MavCmd.MAV_CMD_COMPONENT_ARM_DISARM, 0f, if (command.force) FORCE_DISARM else 0f)
            VehicleCommand.Kill -> long(MavCmd.MAV_CMD_COMPONENT_ARM_DISARM, 0f, FORCE_DISARM)
            is VehicleCommand.SetMode -> setMode(target, command.mode)
            is VehicleCommand.Takeoff -> takeoff(target, command.altM)
            VehicleCommand.Land -> land(target)
            VehicleCommand.ReturnToLaunch -> when (family) {
                FirmwareFamily.Px4 -> long(MavCmd.MAV_CMD_NAV_RETURN_TO_LAUNCH)
                FirmwareFamily.ArduSub -> modeByName(target, "SURFACE")
                FirmwareFamily.Unknown -> long(MavCmd.MAV_CMD_NAV_RETURN_TO_LAUNCH)
                else -> modeByName(target, "RTL")
            }
            VehicleCommand.PauseMission -> pauseResume(target, pause = true)
            VehicleCommand.ResumeMission -> pauseResume(target, pause = false)
            is VehicleCommand.FlyTo -> commandInt(
                MavCmd.MAV_CMD_DO_REPOSITION,
                frame = MavFrame.MAV_FRAME_GLOBAL_RELATIVE_ALT_INT,
                p1 = -1f,
                p2 = MAV_DO_REPOSITION_FLAGS_CHANGE_MODE,
                p4 = Float.NaN,
                x = (command.latDeg * 1e7).toInt(),
                y = (command.lonDeg * 1e7).toInt(),
                z = command.altRelM,
            )
            is VehicleCommand.MotorTest -> long(
                MavCmd.MAV_CMD_DO_MOTOR_TEST,
                command.motor.toFloat(),
                MOTOR_TEST_THROTTLE_PERCENT,
                command.throttlePct,
                command.durationS,
                0f,
                0f,
            )
        }
    }

    /** Sends an arbitrary `COMMAND_LONG` with ACK handling (calibration, mission start, …). */
    suspend fun long(command: Int, vararg params: Float): CommandResult {
        val target = link.target.value ?: return CommandResult.Denied(REASON_NO_LINK)
        val p = FloatArray(7) { params.getOrElse(it) { 0f } }
        return exchange(command) { confirmation ->
            CommandLong(
                targetSystem = target.sysId,
                targetComponent = target.compId,
                command = command,
                confirmation = confirmation,
                param1 = p[0], param2 = p[1], param3 = p[2], param4 = p[3],
                param5 = p[4], param6 = p[5], param7 = p[6],
            )
        }
    }

    /** Sends a `COMMAND_INT` (positions as degE7) with ACK handling. */
    suspend fun commandInt(
        command: Int,
        frame: Int,
        p1: Float = 0f,
        p2: Float = 0f,
        p3: Float = 0f,
        p4: Float = 0f,
        x: Int = 0,
        y: Int = 0,
        z: Float = 0f,
    ): CommandResult {
        val target = link.target.value ?: return CommandResult.Denied(REASON_NO_LINK)
        // COMMAND_INT has no confirmation field; retries resend the same message.
        return exchange(command) {
            CommandInt(
                targetSystem = target.sysId,
                targetComponent = target.compId,
                frame = frame,
                command = command,
                param1 = p1, param2 = p2, param3 = p3, param4 = p4,
                x = x, y = y, z = z,
            )
        }
    }

    private sealed interface AckState {
        data object None : AckState
        data object InProgress : AckState
        data class Final(val result: CommandResult) : AckState
    }

    private suspend fun exchange(command: Int, build: (confirmation: Int) -> MavMessage): CommandResult {
        val lock = locks.getOrPut(command) { Mutex() }
        return lock.withLock {
            coroutineScope {
                val state = MutableStateFlow<AckState>(AckState.None)
                // Subscribed before the first send so a fast ACK cannot be missed.
                val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                    link.messagesOf<CommandAck>().filter { it.command == command }.collect { ack ->
                        if (state.value !is AckState.Final) {
                            state.value = ack.toResult()?.let { AckState.Final(it) } ?: AckState.InProgress
                        }
                    }
                }
                try {
                    var attempt = 0
                    while (attempt <= RETRIES) {
                        when (val w = link.send(build(attempt))) {
                            WriteResult.Sent -> Unit
                            WriteResult.ReceiveOnly -> return@coroutineScope CommandResult.Denied(REASON_RECEIVE_ONLY)
                            WriteResult.NotAuthorized -> return@coroutineScope CommandResult.Denied(REASON_NOT_AUTHORIZED)
                            is WriteResult.Failed -> return@coroutineScope CommandResult.Denied(w.message)
                        }
                        val got = withTimeoutOrNull(ACK_TIMEOUT_MS) { state.first { it != AckState.None } }
                        if (got != null) break
                        attempt++
                    }
                    when (val s = state.value) {
                        is AckState.Final -> s.result
                        AckState.None -> CommandResult.Timeout
                        AckState.InProgress -> withTimeoutOrNull(IN_PROGRESS_TIMEOUT_MS) {
                            (state.first { it is AckState.Final } as AckState.Final).result
                        } ?: CommandResult.Timeout
                    }
                } finally {
                    collector.cancel()
                }
            }
        }
    }

    private fun CommandAck.toResult(): CommandResult? = when (result) {
        MavResult.MAV_RESULT_ACCEPTED -> CommandResult.Accepted
        MavResult.MAV_RESULT_IN_PROGRESS -> null
        MavResult.MAV_RESULT_UNSUPPORTED -> CommandResult.Unsupported
        MavResult.MAV_RESULT_TEMPORARILY_REJECTED -> CommandResult.Rejected(result, "temporarily_rejected")
        MavResult.MAV_RESULT_DENIED -> CommandResult.Rejected(result, "denied")
        MavResult.MAV_RESULT_FAILED -> CommandResult.Rejected(result, "failed")
        MavResult.MAV_RESULT_CANCELLED -> CommandResult.Rejected(result, "cancelled")
        MavResult.MAV_RESULT_COMMAND_LONG_ONLY -> CommandResult.Rejected(result, "command_long_only")
        MavResult.MAV_RESULT_COMMAND_INT_ONLY -> CommandResult.Rejected(result, "command_int_only")
        MavResult.MAV_RESULT_COMMAND_UNSUPPORTED_MAV_FRAME -> CommandResult.Rejected(result, "unsupported_frame")
        else -> CommandResult.Rejected(result, "result_$result")
    }

    /** `MAV_CMD_DO_SET_MODE` with the custom-mode flag; PX4 takes main and sub mode separately. */
    suspend fun setMode(target: VehicleTarget, mode: FlightMode): CommandResult {
        val flag = MavModeFlag.MAV_MODE_FLAG_CUSTOM_MODE_ENABLED.toFloat()
        return if (target.family == FirmwareFamily.Px4) {
            val main = ((mode.customMode shr 16) and 0xFF).toFloat()
            val sub = ((mode.customMode shr 24) and 0xFF).toFloat()
            long(MavCmd.MAV_CMD_DO_SET_MODE, flag, main, sub)
        } else {
            long(MavCmd.MAV_CMD_DO_SET_MODE, flag, mode.customMode.toFloat())
        }
    }

    private suspend fun modeByName(target: VehicleTarget, name: String): CommandResult {
        val mode = FlightModeTables.byName(target.family, name) ?: return CommandResult.Unsupported
        return setMode(target, mode)
    }

    private suspend fun takeoff(target: VehicleTarget, altM: Float): CommandResult = when (target.family) {
        FirmwareFamily.Px4 -> {
            // PX4 takes an AMSL altitude; NaN lets it use its default when home is unknown.
            val amsl = home.value?.let { it.altMslM + altM } ?: Float.NaN
            long(MavCmd.MAV_CMD_NAV_TAKEOFF, -1f, 0f, 0f, Float.NaN, Float.NaN, Float.NaN, amsl)
        }
        FirmwareFamily.ArduCopter, FirmwareFamily.ArduPlane -> {
            val current = heartbeat.value?.mode?.name
            if (current != "GUIDED") {
                val switched = modeByName(target, "GUIDED")
                if (switched != CommandResult.Accepted) return switched
            }
            long(MavCmd.MAV_CMD_NAV_TAKEOFF, 0f, 0f, 0f, Float.NaN, 0f, 0f, altM)
        }
        else -> CommandResult.Unsupported
    }

    private suspend fun land(target: VehicleTarget): CommandResult = when (target.family) {
        FirmwareFamily.ArduCopter -> modeByName(target, "LAND")
        FirmwareFamily.ArduPlane -> if (isVtol(target.vehicleType)) modeByName(target, "QLAND") else modeByName(target, "AUTOLAND")
        FirmwareFamily.ArduRover -> modeByName(target, "HOLD")
        FirmwareFamily.ArduSub -> modeByName(target, "SURFACE")
        FirmwareFamily.Px4, FirmwareFamily.Unknown -> long(MavCmd.MAV_CMD_NAV_LAND, 0f, 0f, 0f, Float.NaN, Float.NaN, Float.NaN, Float.NaN)
    }

    private suspend fun pauseResume(target: VehicleTarget, pause: Boolean): CommandResult = when (target.family) {
        FirmwareFamily.Px4 -> modeByName(target, if (pause) "LOITER" else "AUTO")
        FirmwareFamily.Unknown -> CommandResult.Unsupported
        else -> {
            val r = long(MavCmd.MAV_CMD_DO_PAUSE_CONTINUE, if (pause) 0f else 1f)
            if (r is CommandResult.Rejected || r == CommandResult.Unsupported) {
                val fallback = when {
                    !pause -> "AUTO"
                    target.family == FirmwareFamily.ArduRover -> "HOLD"
                    target.family == FirmwareFamily.ArduSub -> "POSHOLD"
                    target.family == FirmwareFamily.ArduPlane && isVtol(target.vehicleType) -> "QLOITER"
                    else -> "LOITER"
                }
                modeByName(target, fallback)
            } else {
                r
            }
        }
    }

    private fun isVtol(type: Int) = type in VTOL_TYPES

    companion object {
        const val ACK_TIMEOUT_MS = 1_500L
        const val RETRIES = 3
        const val IN_PROGRESS_TIMEOUT_MS = 10_000L

        /** `param2` magic of MAV_CMD_COMPONENT_ARM_DISARM that bypasses arming checks. */
        const val FORCE_ARM = 2989f

        /** `param2` magic that disarms even in flight (flight termination of the motors). */
        const val FORCE_DISARM = 21196f

        const val MAV_DO_REPOSITION_FLAGS_CHANGE_MODE = 1f
        const val MOTOR_TEST_THROTTLE_PERCENT = 0f

        const val REASON_NO_LINK = "noFcLink"
        const val REASON_RECEIVE_ONLY = "receiveOnly"
        const val REASON_NOT_AUTHORIZED = "notAuthorized"

        private val VTOL_TYPES = setOf(
            MavType.MAV_TYPE_VTOL_TAILSITTER_DUOROTOR,
            MavType.MAV_TYPE_VTOL_TAILSITTER_QUADROTOR,
            MavType.MAV_TYPE_VTOL_TILTROTOR,
            MavType.MAV_TYPE_VTOL_FIXEDROTOR,
            MavType.MAV_TYPE_VTOL_TAILSITTER,
            MavType.MAV_TYPE_VTOL_TILTWING,
        )
    }
}
