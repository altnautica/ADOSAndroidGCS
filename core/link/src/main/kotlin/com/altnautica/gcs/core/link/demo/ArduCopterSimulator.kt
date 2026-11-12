package com.altnautica.gcs.core.link.demo

import com.altnautica.gcs.core.mavlink.MavlinkFrame
import com.altnautica.gcs.core.mavlink.enums.MavCmd
import com.altnautica.gcs.core.mavlink.enums.MavModeFlag
import com.altnautica.gcs.core.mavlink.enums.MavResult
import com.altnautica.gcs.core.mavlink.enums.MavSeverity
import com.altnautica.gcs.core.mavlink.messages.CommandInt
import com.altnautica.gcs.core.mavlink.messages.CommandLong
import com.altnautica.gcs.core.mavlink.messages.HomePosition
import com.altnautica.gcs.core.mavlink.messages.LogEntry
import com.altnautica.gcs.core.mavlink.messages.LogRequestList
import com.altnautica.gcs.core.mavlink.messages.MissionAck
import com.altnautica.gcs.core.mavlink.messages.MissionClearAll
import com.altnautica.gcs.core.mavlink.messages.MissionCount
import com.altnautica.gcs.core.mavlink.messages.MissionItemInt
import com.altnautica.gcs.core.mavlink.messages.MissionRequest
import com.altnautica.gcs.core.mavlink.messages.MissionRequestInt
import com.altnautica.gcs.core.mavlink.messages.MissionRequestList
import com.altnautica.gcs.core.mavlink.messages.ParamRequestList
import com.altnautica.gcs.core.mavlink.messages.ParamRequestRead
import com.altnautica.gcs.core.mavlink.messages.ParamSet
import kotlin.math.max
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Tunables of the simulated vehicle. */
data class DemoOptions(
    val homeLatDeg: Double = -35.363261,
    val homeLonDeg: Double = 149.165230,
    val homeAltMslM: Float = 584f,
    /** Parameter indices left out of the first full-list stream, to exercise gap repair. */
    val droppedParamIndices: Set<Int> = emptySet(),
)

/**
 * A simulated ArduCopter speaking MAVLink 2 as system 1, component 1.
 *
 * It streams telemetry at ArduPilot-like rates, answers commands with
 * `COMMAND_ACK`, and implements the vehicle side of the mission, parameter,
 * log-list and calibration protocols. Flight follows the kinematic model in
 * [SimPhysics].
 */
class ArduCopterSimulator(
    private val scope: CoroutineScope,
    options: DemoOptions,
    emit: (ByteArray) -> Unit,
) {
    private val sim = SimState(scope, options, emit)
    private val params = SimParams(sim)
    private val mission = SimMission(sim)
    private val calibration = SimCalibration(sim)
    private val physics = SimPhysics(sim, mission, params)
    private val telemetry = SimTelemetry(sim, physics, mission)

    fun start() {
        scope.launch {
            var tick = 0L
            while (isActive) {
                delay(TICK_MS)
                sim.bootMs += TICK_MS
                tick++
                physics.step(TICK_MS / 1000.0)
                sim.send(telemetry.attitude())
                if (tick % 2 == 0L) {
                    sim.send(telemetry.globalPosition())
                    sim.send(telemetry.vfrHud())
                }
                if (tick % 5 == 0L) {
                    sim.send(telemetry.gpsRaw())
                    sim.send(telemetry.rcChannels())
                }
                if (tick % 10 == 0L) {
                    sim.send(telemetry.heartbeat())
                    sim.send(telemetry.sysStatus())
                    sim.send(telemetry.batteryStatus())
                    sim.send(telemetry.ekfStatus())
                    sim.send(telemetry.vibration())
                    if (sim.mode == MODE_AUTO) sim.send(telemetry.missionCurrent())
                }
                if (tick % 50 == 0L) sim.send(telemetry.home())
            }
        }
    }

    // ---------------------------------------------------------------- inbound

    fun handle(frame: MavlinkFrame) {
        val msg = frame.decode() ?: return
        when (msg) {
            is CommandLong -> if (targeted(msg.targetSystem)) {
                onCommand(frame, msg.command, msg.param1, msg.param2, msg.param3, msg.param4, msg.param5.toDouble(), msg.param6.toDouble(), msg.param7)
            }
            is CommandInt -> if (targeted(msg.targetSystem)) {
                onCommand(frame, msg.command, msg.param1, msg.param2, msg.param3, msg.param4, msg.x / 1e7, msg.y / 1e7, msg.z)
            }
            is MissionCount -> if (targeted(msg.targetSystem)) mission.onCount(frame, msg)
            is MissionItemInt -> if (targeted(msg.targetSystem)) mission.onItem(frame, msg)
            is MissionRequestList -> if (targeted(msg.targetSystem)) mission.onRequestList(frame, msg)
            is MissionRequestInt -> if (targeted(msg.targetSystem)) mission.sendItem(frame, msg.missionType, msg.seq)
            is MissionRequest -> if (targeted(msg.targetSystem)) mission.sendItem(frame, msg.missionType, msg.seq)
            is MissionAck -> Unit
            is MissionClearAll -> if (targeted(msg.targetSystem)) mission.onClearAll(frame, msg)
            is ParamRequestList -> if (targeted(msg.targetSystem)) params.streamAll()
            is ParamRequestRead -> if (targeted(msg.targetSystem)) params.onRead(msg)
            is ParamSet -> if (targeted(msg.targetSystem)) params.onSet(msg)
            is LogRequestList -> if (targeted(msg.targetSystem)) sim.send(LogEntry(id = 0, numLogs = 0, lastLogNum = 0))
            else -> Unit
        }
    }

    private fun targeted(sys: Int) = sys == SYS_ID || sys == 0

    private fun onCommand(
        frame: MavlinkFrame,
        command: Int,
        p1: Float,
        p2: Float,
        p3: Float,
        p4: Float,
        p5: Double,
        p6: Double,
        p7: Float,
    ) {
        val accepted = MavResult.MAV_RESULT_ACCEPTED
        val failed = MavResult.MAV_RESULT_FAILED
        when (command) {
            MavCmd.MAV_CMD_COMPONENT_ARM_DISARM -> if (p1 == 1f) arm(frame, command, p2) else disarm(frame, command, p2)
            MavCmd.MAV_CMD_DO_SET_MODE -> {
                val custom = p2.toInt()
                if ((p1.toInt() and MavModeFlag.MAV_MODE_FLAG_CUSTOM_MODE_ENABLED) == 0 || custom !in MODE_NAMES) {
                    sim.ack(frame, command, failed)
                } else {
                    sim.switchMode(custom)
                    sim.ack(frame, command, accepted)
                }
            }
            MavCmd.MAV_CMD_NAV_TAKEOFF -> {
                if (sim.mode != MODE_GUIDED || !sim.armed || sim.alt > 0.3) {
                    sim.ack(frame, command, failed)
                } else {
                    sim.takeoffAlt = max(1.0, p7.toDouble())
                    sim.text(MavSeverity.MAV_SEVERITY_INFO, "Takeoff to ${p7.toInt()} m")
                    sim.ack(frame, command, accepted)
                }
            }
            MavCmd.MAV_CMD_NAV_RETURN_TO_LAUNCH -> { sim.switchMode(MODE_RTL); sim.ack(frame, command, accepted) }
            MavCmd.MAV_CMD_NAV_LAND -> { sim.switchMode(MODE_LAND); sim.ack(frame, command, accepted) }
            MavCmd.MAV_CMD_MISSION_START -> {
                if (mission.missionFor(SimMission.MISSION).size <= 1 && !sim.armed) {
                    sim.ack(frame, command, failed)
                } else {
                    sim.currentSeq = 1
                    sim.switchMode(MODE_AUTO)
                    sim.ack(frame, command, accepted)
                }
            }
            MavCmd.MAV_CMD_DO_PAUSE_CONTINUE -> {
                if (sim.mode != MODE_AUTO) {
                    sim.ack(frame, command, failed)
                } else {
                    sim.paused = p1 == 0f
                    sim.text(MavSeverity.MAV_SEVERITY_INFO, if (sim.paused) "Mission paused" else "Mission resumed")
                    sim.ack(frame, command, accepted)
                }
            }
            MavCmd.MAV_CMD_DO_SET_MISSION_CURRENT -> {
                val seq = p1.toInt()
                if (seq in mission.missionFor(SimMission.MISSION).indices) {
                    sim.currentSeq = max(1, seq)
                    sim.ack(frame, command, accepted)
                } else {
                    sim.ack(frame, command, MavResult.MAV_RESULT_DENIED)
                }
            }
            MavCmd.MAV_CMD_DO_REPOSITION -> {
                if (!sim.armed || sim.alt < 0.5) {
                    sim.ack(frame, command, failed)
                } else {
                    sim.switchMode(MODE_GUIDED)
                    val (n, e) = physics.toLocal(p5, p6)
                    sim.guidedTarget = SimState.Target(n, e, p7.toDouble())
                    sim.ack(frame, command, accepted)
                }
            }
            MavCmd.MAV_CMD_DO_CHANGE_SPEED -> {
                if (p2 > 0f) sim.waypointSpeed = p2.toDouble()
                sim.ack(frame, command, accepted)
            }
            MavCmd.MAV_CMD_REQUEST_MESSAGE -> {
                if (p1.toInt() == HomePosition.ID) sim.send(telemetry.home())
                sim.ack(frame, command, accepted)
            }
            MavCmd.MAV_CMD_SET_MESSAGE_INTERVAL -> sim.ack(frame, command, accepted)
            MavCmd.MAV_CMD_PREFLIGHT_CALIBRATION -> calibration.onPreflight(frame, command, p1, p5)
            MavCmd.MAV_CMD_ACCELCAL_VEHICLE_POS -> calibration.onAccelPose(frame, command, p1.toInt())
            MavCmd.MAV_CMD_DO_START_MAG_CAL -> calibration.startMag(frame, command)
            MavCmd.MAV_CMD_DO_ACCEPT_MAG_CAL -> {
                sim.ack(frame, command, accepted)
                sim.text(MavSeverity.MAV_SEVERITY_INFO, "Compass calibration saved")
            }
            MavCmd.MAV_CMD_DO_CANCEL_MAG_CAL -> {
                calibration.cancelMag()
                sim.ack(frame, command, accepted)
            }
            MavCmd.MAV_CMD_DO_MOTOR_TEST -> sim.ack(frame, command, if (sim.armed) MavResult.MAV_RESULT_DENIED else accepted)
            else -> sim.ack(frame, command, MavResult.MAV_RESULT_UNSUPPORTED)
        }
    }

    private fun arm(frame: MavlinkFrame, command: Int, p2: Float) {
        when {
            sim.armed -> sim.ack(frame, command, MavResult.MAV_RESULT_ACCEPTED)
            sim.mode !in ARMABLE_MODES && p2 != FORCE_ARM -> {
                sim.text(MavSeverity.MAV_SEVERITY_CRITICAL, "Arm: Mode not armable")
                sim.ack(frame, command, MavResult.MAV_RESULT_FAILED)
            }
            else -> {
                sim.armed = true
                sim.text(MavSeverity.MAV_SEVERITY_INFO, "Arming motors")
                sim.ack(frame, command, MavResult.MAV_RESULT_ACCEPTED)
            }
        }
    }

    private fun disarm(frame: MavlinkFrame, command: Int, p2: Float) {
        if (sim.alt > 0.3 && p2 != FORCE_DISARM) {
            sim.text(MavSeverity.MAV_SEVERITY_CRITICAL, "Disarm: vehicle is flying")
            sim.ack(frame, command, MavResult.MAV_RESULT_FAILED)
        } else {
            sim.disarm()
            sim.ack(frame, command, MavResult.MAV_RESULT_ACCEPTED)
        }
    }

    /** Current simulated altitude above home, for tests. */
    val altitudeM: Double get() = sim.alt
    val isArmed: Boolean get() = sim.armed
    val modeNumber: Int get() = sim.mode
    val horizontalDistanceFromHomeM: Double get() = sqrt(sim.north * sim.north + sim.east * sim.east)

    /** The GNSS fix the telemetry reports; false drops it (fix type 1, no satellites, no absolute position). */
    var gnssFix: Boolean
        get() = telemetry.gnssFix
        set(value) {
            telemetry.gnssFix = value
        }

    companion object {
        const val SYS_ID = 1
        const val COMP_ID = 1
        const val TICK_MS = 100L
        const val PARAM_STREAM_GAP_MS = 5L
        const val CLIMB_SPEED = 2.5
        const val LAND_SPEED = 1.0
        const val CIRCLE_RADIUS_M = 100.0
        const val BATTERY_CAPACITY_MAH = 5200.0

        /** Drains twice as fast as the modelled current so a demo flight shows the battery falling. */
        const val BATTERY_DRAIN_FACTOR = 2.0
        private const val FORCE_ARM = 2989f
        private const val FORCE_DISARM = 21196f

        const val MODE_STABILIZE = 0
        const val MODE_ALT_HOLD = 2
        const val MODE_AUTO = 3
        const val MODE_GUIDED = 4
        const val MODE_LOITER = 5
        const val MODE_RTL = 6
        const val MODE_CIRCLE = 7
        const val MODE_LAND = 9
        const val MODE_POSHOLD = 16
        const val MODE_BRAKE = 17

        internal val MODE_NAMES = mapOf(
            MODE_STABILIZE to "STABILIZE", 1 to "ACRO", MODE_ALT_HOLD to "ALT_HOLD", MODE_AUTO to "AUTO",
            MODE_GUIDED to "GUIDED", MODE_LOITER to "LOITER", MODE_RTL to "RTL", MODE_CIRCLE to "CIRCLE",
            MODE_LAND to "LAND", 13 to "SPORT", MODE_POSHOLD to "POSHOLD", MODE_BRAKE to "BRAKE", 21 to "SMART_RTL",
        )

        private val ARMABLE_MODES = setOf(MODE_STABILIZE, 1, MODE_ALT_HOLD, MODE_GUIDED, MODE_LOITER, 13, MODE_POSHOLD)

        internal fun defaultParams(): List<SimParam> = SimParams.defaultParams()
    }
}
