package com.altnautica.gcs.core.link.demo

import com.altnautica.gcs.core.mavlink.MavlinkFrame
import com.altnautica.gcs.core.mavlink.MavlinkFrameWriter
import com.altnautica.gcs.core.mavlink.enums.AccelcalVehiclePos
import com.altnautica.gcs.core.mavlink.enums.MavAutopilot
import com.altnautica.gcs.core.mavlink.enums.MavBatteryFunction
import com.altnautica.gcs.core.mavlink.enums.MavBatteryType
import com.altnautica.gcs.core.mavlink.enums.MavCmd
import com.altnautica.gcs.core.mavlink.enums.MavFrame
import com.altnautica.gcs.core.mavlink.enums.MavMissionResult
import com.altnautica.gcs.core.mavlink.enums.MavModeFlag
import com.altnautica.gcs.core.mavlink.enums.MavParamType
import com.altnautica.gcs.core.mavlink.enums.MavResult
import com.altnautica.gcs.core.mavlink.enums.MavSeverity
import com.altnautica.gcs.core.mavlink.enums.MavState
import com.altnautica.gcs.core.mavlink.enums.MavType
import com.altnautica.gcs.core.mavlink.messages.Attitude
import com.altnautica.gcs.core.mavlink.messages.BatteryStatus
import com.altnautica.gcs.core.mavlink.messages.CommandAck
import com.altnautica.gcs.core.mavlink.messages.CommandInt
import com.altnautica.gcs.core.mavlink.messages.CommandLong
import com.altnautica.gcs.core.mavlink.messages.EkfStatusReport
import com.altnautica.gcs.core.mavlink.messages.GlobalPositionInt
import com.altnautica.gcs.core.mavlink.messages.GpsRawInt
import com.altnautica.gcs.core.mavlink.messages.Heartbeat
import com.altnautica.gcs.core.mavlink.messages.HomePosition
import com.altnautica.gcs.core.mavlink.messages.LogEntry
import com.altnautica.gcs.core.mavlink.messages.LogRequestList
import com.altnautica.gcs.core.mavlink.messages.MagCalProgress
import com.altnautica.gcs.core.mavlink.messages.MagCalReport
import com.altnautica.gcs.core.mavlink.messages.MavMessage
import com.altnautica.gcs.core.mavlink.messages.MissionAck
import com.altnautica.gcs.core.mavlink.messages.MissionClearAll
import com.altnautica.gcs.core.mavlink.messages.MissionCount
import com.altnautica.gcs.core.mavlink.messages.MissionCurrent
import com.altnautica.gcs.core.mavlink.messages.MissionItemInt
import com.altnautica.gcs.core.mavlink.messages.MissionItemReached
import com.altnautica.gcs.core.mavlink.messages.MissionRequest
import com.altnautica.gcs.core.mavlink.messages.MissionRequestInt
import com.altnautica.gcs.core.mavlink.messages.MissionRequestList
import com.altnautica.gcs.core.mavlink.messages.ParamRequestList
import com.altnautica.gcs.core.mavlink.messages.ParamRequestRead
import com.altnautica.gcs.core.mavlink.messages.ParamSet
import com.altnautica.gcs.core.mavlink.messages.ParamValue
import com.altnautica.gcs.core.mavlink.messages.RcChannels
import com.altnautica.gcs.core.mavlink.messages.Statustext
import com.altnautica.gcs.core.mavlink.messages.SysStatus
import com.altnautica.gcs.core.mavlink.messages.VfrHud
import com.altnautica.gcs.core.mavlink.messages.Vibration
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
 * log-list and calibration protocols. Flight is a simple kinematic model:
 * takeoff climbs at 2.5 m/s, horizontal moves at the waypoint speed, LAND
 * descends at 1 m/s and disarms on touchdown, RTL climbs to `RTL_ALT` and
 * returns home, AUTO flies the stored mission (or a 100 m circle round home
 * when the mission is empty).
 */
class ArduCopterSimulator(
    private val scope: CoroutineScope,
    private val options: DemoOptions,
    private val emit: (ByteArray) -> Unit,
) {
    private val writer = MavlinkFrameWriter(sysId = SYS_ID, compId = COMP_ID)
    private val params: MutableList<SimParam> = defaultParams().toMutableList()

    private var bootMs = 0L
    private var armed = false
    private var mode = MODE_STABILIZE

    // Position relative to home: north/east metres, altitude above home (m).
    private var north = 0.0
    private var east = 0.0
    private var alt = 0.0
    private var vn = 0.0
    private var ve = 0.0
    private var vd = 0.0
    private var yaw = 0.0
    private var roll = 0.0
    private var pitch = 0.0
    private var batteryMah = 0.0
    private var throttle = 0

    private var guidedTarget: Target? = null
    private var takeoffAlt: Double? = null
    private var landing = false
    private var rtlPhase = 0
    private var paused = false
    private var circleAngle = 0.0
    private var waypointSpeed = 5.0

    private val missions = mutableMapOf<Int, List<MissionItemInt>>()
    private var currentSeq = 1
    private var holdUntilMs: Long? = null

    private var upload: Upload? = null
    private var accelPose: Int? = null
    private var magJob: Job? = null

    private class Target(val north: Double, val east: Double, val alt: Double)
    private class Upload(val type: Int, val count: Int, val items: Array<MissionItemInt?>, var next: Int)

    fun start() {
        scope.launch {
            var tick = 0L
            while (isActive) {
                delay(TICK_MS)
                bootMs += TICK_MS
                tick++
                step(TICK_MS / 1000.0)
                send(attitude())
                if (tick % 2 == 0L) {
                    send(globalPosition())
                    send(vfrHud())
                }
                if (tick % 5 == 0L) {
                    send(gpsRaw())
                    send(rcChannels())
                }
                if (tick % 10 == 0L) {
                    send(heartbeat())
                    send(sysStatus())
                    send(batteryStatus())
                    send(EkfStatusReport(flags = 0x3FF, velocityVariance = 0.05f, posHorizVariance = 0.04f, posVertVariance = 0.03f, compassVariance = 0.02f))
                    send(Vibration(timeUsec = bootMs * 1000, vibrationX = 3.1f, vibrationY = 2.8f, vibrationZ = 4.2f))
                    if (mode == MODE_AUTO) send(missionCurrent())
                }
                if (tick % 50 == 0L) send(home())
            }
        }
    }

    // ---------------------------------------------------------------- inbound

    fun handle(frame: MavlinkFrame) {
        val msg = frame.decode() ?: return
        when (msg) {
            is CommandLong -> if (targeted(msg.targetSystem)) onCommand(frame, msg.command, msg.param1, msg.param2, msg.param3, msg.param4, msg.param5.toDouble(), msg.param6.toDouble(), msg.param7)
            is CommandInt -> if (targeted(msg.targetSystem)) onCommand(frame, msg.command, msg.param1, msg.param2, msg.param3, msg.param4, msg.x / 1e7, msg.y / 1e7, msg.z)
            is MissionCount -> if (targeted(msg.targetSystem)) onMissionCount(frame, msg)
            is MissionItemInt -> if (targeted(msg.targetSystem)) onMissionItem(frame, msg)
            is MissionRequestList -> if (targeted(msg.targetSystem)) {
                send(MissionCount(frame.sysId, frame.compId, missionFor(msg.missionType).size, msg.missionType))
            }
            is MissionRequestInt -> if (targeted(msg.targetSystem)) sendItem(frame, msg.missionType, msg.seq)
            is MissionRequest -> if (targeted(msg.targetSystem)) sendItem(frame, msg.missionType, msg.seq)
            is MissionAck -> Unit
            is MissionClearAll -> if (targeted(msg.targetSystem)) {
                missions[msg.missionType] = if (msg.missionType == MISSION) listOf(homeItem()) else emptyList()
                currentSeq = 1
                send(MissionAck(frame.sysId, frame.compId, MavMissionResult.MAV_MISSION_ACCEPTED, msg.missionType))
            }
            is ParamRequestList -> if (targeted(msg.targetSystem)) streamParams()
            is ParamRequestRead -> if (targeted(msg.targetSystem)) {
                val index = if (msg.paramIndex >= 0) msg.paramIndex else params.indexOfFirst { it.name == msg.paramId }
                if (index in params.indices) send(paramValue(index))
            }
            is ParamSet -> if (targeted(msg.targetSystem)) {
                val index = params.indexOfFirst { it.name == msg.paramId }
                if (index >= 0) {
                    params[index] = params[index].copy(value = msg.paramValue)
                    send(paramValue(index))
                }
            }
            is LogRequestList -> if (targeted(msg.targetSystem)) send(LogEntry(id = 0, numLogs = 0, lastLogNum = 0))
            else -> Unit
        }
    }

    private fun targeted(sys: Int) = sys == SYS_ID || sys == 0

    private fun ack(frame: MavlinkFrame, command: Int, result: Int) =
        send(CommandAck(command = command, result = result, targetSystem = frame.sysId, targetComponent = frame.compId))

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
        when (command) {
            MavCmd.MAV_CMD_COMPONENT_ARM_DISARM -> {
                if (p1 == 1f) {
                    when {
                        armed -> ack(frame, command, MavResult.MAV_RESULT_ACCEPTED)
                        mode !in ARMABLE_MODES && p2 != FORCE_ARM -> {
                            text(MavSeverity.MAV_SEVERITY_CRITICAL, "Arm: Mode not armable")
                            ack(frame, command, MavResult.MAV_RESULT_FAILED)
                        }
                        else -> {
                            armed = true
                            text(MavSeverity.MAV_SEVERITY_INFO, "Arming motors")
                            ack(frame, command, MavResult.MAV_RESULT_ACCEPTED)
                        }
                    }
                } else {
                    if (alt > 0.3 && p2 != FORCE_DISARM) {
                        text(MavSeverity.MAV_SEVERITY_CRITICAL, "Disarm: vehicle is flying")
                        ack(frame, command, MavResult.MAV_RESULT_FAILED)
                    } else {
                        disarm()
                        ack(frame, command, MavResult.MAV_RESULT_ACCEPTED)
                    }
                }
            }
            MavCmd.MAV_CMD_DO_SET_MODE -> {
                val custom = p2.toInt()
                if ((p1.toInt() and MavModeFlag.MAV_MODE_FLAG_CUSTOM_MODE_ENABLED) == 0 || custom !in MODE_NAMES) {
                    ack(frame, command, MavResult.MAV_RESULT_FAILED)
                } else {
                    setMode(custom)
                    ack(frame, command, MavResult.MAV_RESULT_ACCEPTED)
                }
            }
            MavCmd.MAV_CMD_NAV_TAKEOFF -> {
                if (mode != MODE_GUIDED || !armed || alt > 0.3) {
                    ack(frame, command, MavResult.MAV_RESULT_FAILED)
                } else {
                    takeoffAlt = max(1.0, p7.toDouble())
                    text(MavSeverity.MAV_SEVERITY_INFO, "Takeoff to ${p7.toInt()} m")
                    ack(frame, command, MavResult.MAV_RESULT_ACCEPTED)
                }
            }
            MavCmd.MAV_CMD_NAV_RETURN_TO_LAUNCH -> { setMode(MODE_RTL); ack(frame, command, MavResult.MAV_RESULT_ACCEPTED) }
            MavCmd.MAV_CMD_NAV_LAND -> { setMode(MODE_LAND); ack(frame, command, MavResult.MAV_RESULT_ACCEPTED) }
            MavCmd.MAV_CMD_MISSION_START -> {
                if (missionFor(MISSION).size <= 1 && !armed) {
                    ack(frame, command, MavResult.MAV_RESULT_FAILED)
                } else {
                    currentSeq = 1
                    setMode(MODE_AUTO)
                    ack(frame, command, MavResult.MAV_RESULT_ACCEPTED)
                }
            }
            MavCmd.MAV_CMD_DO_PAUSE_CONTINUE -> {
                if (mode != MODE_AUTO) {
                    ack(frame, command, MavResult.MAV_RESULT_FAILED)
                } else {
                    paused = p1 == 0f
                    text(MavSeverity.MAV_SEVERITY_INFO, if (paused) "Mission paused" else "Mission resumed")
                    ack(frame, command, MavResult.MAV_RESULT_ACCEPTED)
                }
            }
            MavCmd.MAV_CMD_DO_SET_MISSION_CURRENT -> {
                val seq = p1.toInt()
                if (seq in missionFor(MISSION).indices) {
                    currentSeq = max(1, seq)
                    ack(frame, command, MavResult.MAV_RESULT_ACCEPTED)
                } else {
                    ack(frame, command, MavResult.MAV_RESULT_DENIED)
                }
            }
            MavCmd.MAV_CMD_DO_REPOSITION -> {
                if (!armed || alt < 0.5) {
                    ack(frame, command, MavResult.MAV_RESULT_FAILED)
                } else {
                    setMode(MODE_GUIDED)
                    val (n, e) = toLocal(p5, p6)
                    guidedTarget = Target(n, e, p7.toDouble())
                    ack(frame, command, MavResult.MAV_RESULT_ACCEPTED)
                }
            }
            MavCmd.MAV_CMD_DO_CHANGE_SPEED -> {
                if (p2 > 0f) waypointSpeed = p2.toDouble()
                ack(frame, command, MavResult.MAV_RESULT_ACCEPTED)
            }
            MavCmd.MAV_CMD_REQUEST_MESSAGE -> {
                if (p1.toInt() == HomePosition.ID) send(home())
                ack(frame, command, MavResult.MAV_RESULT_ACCEPTED)
            }
            MavCmd.MAV_CMD_SET_MESSAGE_INTERVAL -> ack(frame, command, MavResult.MAV_RESULT_ACCEPTED)
            MavCmd.MAV_CMD_PREFLIGHT_CALIBRATION -> onCalibration(frame, command, p1, p5)
            MavCmd.MAV_CMD_ACCELCAL_VEHICLE_POS -> onAccelPose(frame, command, p1.toInt())
            MavCmd.MAV_CMD_DO_START_MAG_CAL -> startMagCal(frame, command)
            MavCmd.MAV_CMD_DO_ACCEPT_MAG_CAL -> {
                ack(frame, command, MavResult.MAV_RESULT_ACCEPTED)
                text(MavSeverity.MAV_SEVERITY_INFO, "Compass calibration saved")
            }
            MavCmd.MAV_CMD_DO_CANCEL_MAG_CAL -> {
                magJob?.cancel()
                ack(frame, command, MavResult.MAV_RESULT_ACCEPTED)
            }
            MavCmd.MAV_CMD_DO_MOTOR_TEST -> ack(frame, command, if (armed) MavResult.MAV_RESULT_DENIED else MavResult.MAV_RESULT_ACCEPTED)
            else -> ack(frame, command, MavResult.MAV_RESULT_UNSUPPORTED)
        }
    }

    private fun onCalibration(frame: MavlinkFrame, command: Int, p1: Float, p5: Double) {
        if (armed) {
            ack(frame, command, MavResult.MAV_RESULT_DENIED)
            return
        }
        when {
            p1 == 1f -> scope.launch {
                text(MavSeverity.MAV_SEVERITY_INFO, "Calibrating gyros")
                delay(500)
                ack(frame, command, MavResult.MAV_RESULT_ACCEPTED)
            }
            p5 == 2.0 -> scope.launch {
                delay(300)
                ack(frame, command, MavResult.MAV_RESULT_ACCEPTED)
            }
            p5 == 1.0 -> {
                ack(frame, command, MavResult.MAV_RESULT_ACCEPTED)
                accelPose = 1
                requestPose(frame, 1)
            }
            else -> ack(frame, command, MavResult.MAV_RESULT_UNSUPPORTED)
        }
    }

    private fun requestPose(frame: MavlinkFrame, pose: Int) {
        val names = listOf("level", "on its LEFT side", "on its RIGHT side", "nose DOWN", "nose UP", "on its BACK")
        text(MavSeverity.MAV_SEVERITY_INFO, "Place vehicle ${names[pose - 1]} and press any key.")
        send(CommandLong(targetSystem = frame.sysId, targetComponent = frame.compId, command = MavCmd.MAV_CMD_ACCELCAL_VEHICLE_POS, param1 = pose.toFloat()))
    }

    private fun onAccelPose(frame: MavlinkFrame, command: Int, pose: Int) {
        val expected = accelPose
        if (expected == null || pose != expected) {
            ack(frame, command, MavResult.MAV_RESULT_DENIED)
            return
        }
        ack(frame, command, MavResult.MAV_RESULT_ACCEPTED)
        if (pose < 6) {
            accelPose = pose + 1
            requestPose(frame, pose + 1)
        } else {
            accelPose = null
            text(MavSeverity.MAV_SEVERITY_INFO, "Calibration successful")
            send(CommandLong(targetSystem = frame.sysId, targetComponent = frame.compId, command = MavCmd.MAV_CMD_ACCELCAL_VEHICLE_POS, param1 = AccelcalVehiclePos.ACCELCAL_VEHICLE_POS_SUCCESS.toFloat()))
        }
    }

    private fun startMagCal(frame: MavlinkFrame, command: Int) {
        if (armed) {
            ack(frame, command, MavResult.MAV_RESULT_DENIED)
            return
        }
        ack(frame, command, MavResult.MAV_RESULT_ACCEPTED)
        magJob?.cancel()
        magJob = scope.launch {
            for (pct in 0..100 step 5) {
                send(MagCalProgress(compassId = 0, calMask = 1, calStatus = if (pct < 50) 2 else 3, attempt = 1, completionPct = pct))
                delay(250)
            }
            send(MagCalReport(compassId = 0, calMask = 1, calStatus = 4, autosaved = 0, fitness = 8.5f, ofsX = 12f, ofsY = -7f, ofsZ = 30f, diagX = 1f, diagY = 1f, diagZ = 1f, scaleFactor = 1f))
        }
    }

    // ---------------------------------------------------------------- mission protocol

    private fun missionFor(type: Int): List<MissionItemInt> =
        missions[type] ?: if (type == MISSION) listOf(homeItem()) else emptyList()

    private fun homeItem() = MissionItemInt(
        targetSystem = 255,
        targetComponent = 190,
        seq = 0,
        frame = MavFrame.MAV_FRAME_GLOBAL,
        command = MavCmd.MAV_CMD_NAV_WAYPOINT,
        current = 0,
        autocontinue = 1,
        x = (options.homeLatDeg * 1e7).toInt(),
        y = (options.homeLonDeg * 1e7).toInt(),
        z = options.homeAltMslM,
        missionType = MISSION,
    )

    private fun onMissionCount(frame: MavlinkFrame, msg: MissionCount) {
        if (msg.count == 0) {
            missions[msg.missionType] = if (msg.missionType == MISSION) listOf(homeItem()) else emptyList()
            send(MissionAck(frame.sysId, frame.compId, MavMissionResult.MAV_MISSION_ACCEPTED, msg.missionType))
            return
        }
        upload = Upload(msg.missionType, msg.count, arrayOfNulls(msg.count), 0)
        send(MissionRequestInt(frame.sysId, frame.compId, 0, msg.missionType))
    }

    private fun onMissionItem(frame: MavlinkFrame, msg: MissionItemInt) {
        val u = upload ?: return
        if (msg.missionType != u.type) return
        if (msg.seq != u.next) {
            // Out of order: ask again for the one we need.
            send(MissionRequestInt(frame.sysId, frame.compId, u.next, u.type))
            return
        }
        u.items[msg.seq] = msg
        u.next++
        if (u.next < u.count) {
            send(MissionRequestInt(frame.sysId, frame.compId, u.next, u.type))
        } else {
            val items = u.items.map { it!! }.toMutableList()
            // ArduPilot keeps its own home at seq 0 whatever the GCS wrote there.
            if (u.type == MISSION) items[0] = homeItem()
            missions[u.type] = items
            upload = null
            currentSeq = 1
            send(MissionAck(frame.sysId, frame.compId, MavMissionResult.MAV_MISSION_ACCEPTED, u.type))
        }
    }

    private fun sendItem(frame: MavlinkFrame, type: Int, seq: Int) {
        val items = missionFor(type)
        if (seq !in items.indices) {
            send(MissionAck(frame.sysId, frame.compId, MavMissionResult.MAV_MISSION_INVALID_SEQUENCE, type))
            return
        }
        send(items[seq].copy(targetSystem = frame.sysId, targetComponent = frame.compId, seq = seq, missionType = type, current = if (seq == currentSeq) 1 else 0))
    }

    // ---------------------------------------------------------------- params

    private fun streamParams() {
        scope.launch {
            val dropped = options.droppedParamIndices
            for (i in params.indices) {
                if (i in dropped && !paramsStreamedOnce) continue
                send(paramValue(i))
                delay(PARAM_STREAM_GAP_MS)
            }
            paramsStreamedOnce = true
        }
    }

    private var paramsStreamedOnce = false

    private fun paramValue(index: Int): ParamValue {
        val p = params[index]
        return ParamValue(paramId = p.name, paramValue = p.value, paramType = p.type, paramCount = params.size, paramIndex = index)
    }

    // ---------------------------------------------------------------- flight model

    private fun setMode(custom: Int) {
        if (custom == mode) return
        mode = custom
        guidedTarget = null
        landing = false
        rtlPhase = 0
        paused = false
        holdUntilMs = null
        if (custom != MODE_GUIDED) takeoffAlt = null
        text(MavSeverity.MAV_SEVERITY_INFO, "Mode ${MODE_NAMES[custom]}")
    }

    private fun disarm() {
        armed = false
        takeoffAlt = null
        guidedTarget = null
        text(MavSeverity.MAV_SEVERITY_INFO, "Disarming motors")
    }

    private fun step(dt: Double) {
        if (!armed) {
            // Unpowered: fall to the ground.
            if (alt > 0) alt = max(0.0, alt - 9.0 * dt)
            vn = 0.0; ve = 0.0; vd = 0.0; throttle = 0
            roll = 0.0; pitch = 0.0
            return
        }
        var desiredN = 0.0
        var desiredE = 0.0
        var desiredD = 0.0
        when (mode) {
            MODE_GUIDED -> {
                val toAlt = takeoffAlt
                val t = guidedTarget
                if (toAlt != null) {
                    desiredD = climbTo(toAlt)
                    if (abs(alt - toAlt) < 0.2) takeoffAlt = null
                } else if (t != null) {
                    val v = flyTo(t.north, t.east, t.alt)
                    desiredN = v[0]; desiredE = v[1]; desiredD = v[2]
                }
            }
            MODE_LAND -> desiredD = LAND_SPEED
            MODE_RTL -> {
                val rtlAlt = (paramValue("RTL_ALT") / 100.0).coerceAtLeast(alt)
                when (rtlPhase) {
                    0 -> { desiredD = climbTo(rtlAlt); if (abs(alt - rtlAlt) < 0.3) rtlPhase = 1 }
                    1 -> {
                        val v = flyTo(0.0, 0.0, alt)
                        desiredN = v[0]; desiredE = v[1]
                        if (hypot(north, east) < 1.0) rtlPhase = 2
                    }
                    else -> desiredD = LAND_SPEED
                }
            }
            MODE_AUTO -> if (!paused) {
                val v = autoStep()
                desiredN = v[0]; desiredE = v[1]; desiredD = v[2]
            }
            MODE_CIRCLE -> {
                val v = circleStep(dt)
                desiredN = v[0]; desiredE = v[1]
            }
        }
        vn += (desiredN - vn) * min(1.0, dt * 3)
        ve += (desiredE - ve) * min(1.0, dt * 3)
        vd += (desiredD - vd) * min(1.0, dt * 3)
        north += vn * dt
        east += ve * dt
        alt -= vd * dt
        if (alt <= 0.0) {
            alt = 0.0
            vd = 0.0
            if (mode == MODE_LAND || (mode == MODE_RTL && rtlPhase == 2) || landing) {
                disarm()
                return
            }
        }
        val speed = hypot(vn, ve)
        if (speed > 0.3) yaw = atan2(ve, vn)
        // Lean into the motion like a multicopter does.
        pitch = -min(0.35, speed / 20.0)
        roll = 0.03 * sin(bootMs / 900.0)
        throttle = (45 + 10 * (-vd).coerceIn(-2.0, 2.0) + speed).toInt().coerceIn(0, 100)
        batteryMah += (8.0 + speed * 0.8) * dt * 1000.0 / 3600.0 * BATTERY_DRAIN_FACTOR
    }

    /** Velocity (m/s, NED down positive) toward [targetAlt]. */
    private fun climbTo(targetAlt: Double): Double {
        val err = targetAlt - alt
        return -(err * 1.0).coerceIn(-CLIMB_SPEED, CLIMB_SPEED)
    }

    private fun flyTo(n: Double, e: Double, a: Double): DoubleArray {
        val dn = n - north
        val de = e - east
        val dist = hypot(dn, de)
        val speed = min(waypointSpeed, dist * 0.8)
        val k = if (dist > 0.01) speed / dist else 0.0
        return doubleArrayOf(dn * k, de * k, climbTo(a))
    }

    private fun circleStep(dt: Double): DoubleArray {
        val omega = waypointSpeed / CIRCLE_RADIUS_M
        circleAngle += omega * dt
        val tn = CIRCLE_RADIUS_M * cos(circleAngle)
        val te = CIRCLE_RADIUS_M * sin(circleAngle)
        val v = flyTo(tn, te, max(alt, 10.0))
        // Feed-forward along the circle so it does not lag behind the target.
        v[0] += -waypointSpeed * sin(circleAngle) * 0.8
        v[1] += waypointSpeed * cos(circleAngle) * 0.8
        return v
    }

    private fun autoStep(): DoubleArray {
        val items = missionFor(MISSION)
        if (items.size <= 1) return if (alt < 1.0) doubleArrayOf(0.0, 0.0, climbTo(10.0)) else circleStep(TICK_MS / 1000.0)
        if (currentSeq >= items.size) return doubleArrayOf(0.0, 0.0, 0.0)
        val item = items[currentSeq]
        val itemAlt = if (item.frame == MavFrame.MAV_FRAME_GLOBAL || item.frame == MavFrame.MAV_FRAME_GLOBAL_INT) {
            item.z - options.homeAltMslM.toDouble()
        } else {
            item.z.toDouble()
        }
        val hasPosition = item.x != 0 || item.y != 0
        val (tn, te) = if (hasPosition) toLocal(item.x / 1e7, item.y / 1e7) else north to east
        when (item.command) {
            MavCmd.MAV_CMD_NAV_TAKEOFF -> {
                if (abs(alt - itemAlt) < 0.5) advance() else return doubleArrayOf(0.0, 0.0, climbTo(itemAlt))
            }
            MavCmd.MAV_CMD_NAV_WAYPOINT, MavCmd.MAV_CMD_NAV_LOITER_TIME, MavCmd.MAV_CMD_NAV_LOITER_TURNS, MavCmd.MAV_CMD_NAV_LOITER_UNLIM -> {
                val v = flyTo(tn, te, itemAlt)
                if (hypot(tn - north, te - east) < 2.0 && abs(alt - itemAlt) < 1.0) {
                    val holdS = when (item.command) {
                        MavCmd.MAV_CMD_NAV_WAYPOINT, MavCmd.MAV_CMD_NAV_LOITER_TIME -> item.param1.toDouble()
                        MavCmd.MAV_CMD_NAV_LOITER_TURNS -> item.param1 * 2 * PI * max(item.param3.toDouble(), 10.0) / waypointSpeed
                        else -> Double.POSITIVE_INFINITY
                    }
                    val until = holdUntilMs ?: (bootMs + (holdS * 1000).toLong().coerceAtLeast(0)).also { holdUntilMs = it }
                    if (bootMs >= until) advance()
                }
                return v
            }
            MavCmd.MAV_CMD_NAV_LAND -> {
                val v = flyTo(tn, te, alt)
                if (hypot(tn - north, te - east) < 1.0) {
                    landing = true
                    v[2] = LAND_SPEED
                }
                return v
            }
            MavCmd.MAV_CMD_NAV_RETURN_TO_LAUNCH -> {
                setMode(MODE_RTL)
            }
            MavCmd.MAV_CMD_DO_CHANGE_SPEED -> {
                if (item.param2 > 0f) waypointSpeed = item.param2.toDouble()
                advance()
            }
            else -> advance()
        }
        return doubleArrayOf(0.0, 0.0, 0.0)
    }

    private fun advance() {
        send(MissionItemReached(currentSeq))
        currentSeq++
        holdUntilMs = null
    }

    private fun toLocal(lat: Double, lon: Double): Pair<Double, Double> {
        val n = (lat - options.homeLatDeg) * METERS_PER_DEG
        val e = (lon - options.homeLonDeg) * METERS_PER_DEG * cos(Math.toRadians(options.homeLatDeg))
        return n to e
    }

    private fun latDeg() = options.homeLatDeg + north / METERS_PER_DEG
    private fun lonDeg() = options.homeLonDeg + east / (METERS_PER_DEG * cos(Math.toRadians(options.homeLatDeg)))

    // ---------------------------------------------------------------- outbound

    private fun heartbeat() = Heartbeat(
        type = MavType.MAV_TYPE_QUADROTOR,
        autopilot = MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA,
        baseMode = MavModeFlag.MAV_MODE_FLAG_CUSTOM_MODE_ENABLED or (if (armed) MavModeFlag.MAV_MODE_FLAG_SAFETY_ARMED else 0),
        customMode = mode.toLong(),
        systemStatus = if (armed) MavState.MAV_STATE_ACTIVE else MavState.MAV_STATE_STANDBY,
        mavlinkVersion = 3,
    )

    private fun attitude() = Attitude(
        timeBootMs = bootMs,
        roll = roll.toFloat(),
        pitch = pitch.toFloat(),
        yaw = yaw.toFloat(),
    )

    private fun globalPosition() = GlobalPositionInt(
        timeBootMs = bootMs,
        lat = (latDeg() * 1e7).toInt(),
        lon = (lonDeg() * 1e7).toInt(),
        alt = ((options.homeAltMslM + alt) * 1000).toInt(),
        relativeAlt = (alt * 1000).toInt(),
        vx = (vn * 100).toInt(),
        vy = (ve * 100).toInt(),
        vz = (vd * 100).toInt(),
        hdg = ((Math.toDegrees(yaw) + 360) % 360 * 100).toInt(),
    )

    private fun vfrHud() = VfrHud(
        airspeed = hypot(vn, ve).toFloat(),
        groundspeed = hypot(vn, ve).toFloat(),
        heading = ((Math.toDegrees(yaw) + 360) % 360).toInt(),
        throttle = throttle,
        alt = (options.homeAltMslM + alt).toFloat(),
        climb = (-vd).toFloat(),
    )

    private fun gpsRaw() = GpsRawInt(
        timeUsec = bootMs * 1000,
        fixType = 3,
        lat = (latDeg() * 1e7).toInt(),
        lon = (lonDeg() * 1e7).toInt(),
        alt = ((options.homeAltMslM + alt) * 1000).toInt(),
        eph = 80,
        epv = 120,
        vel = (hypot(vn, ve) * 100).toInt(),
        cog = ((Math.toDegrees(yaw) + 360) % 360 * 100).toInt(),
        satellitesVisible = 14,
    )

    private fun remainingPct(): Int = (100 - batteryMah / BATTERY_CAPACITY_MAH * 100).toInt().coerceIn(0, 100)

    private fun voltage(): Double = 12.8 + 4.0 * remainingPct() / 100.0

    private fun currentA(): Double = if (armed) 8.0 + hypot(vn, ve) * 0.8 else 0.4

    private fun sysStatus() = SysStatus(
        onboardControlSensorsPresent = SENSORS,
        onboardControlSensorsEnabled = SENSORS,
        onboardControlSensorsHealth = SENSORS,
        load = 250,
        voltageBattery = (voltage() * 1000).toInt(),
        currentBattery = (currentA() * 100).toInt(),
        batteryRemaining = remainingPct(),
    )

    private fun batteryStatus(): BatteryStatus {
        val cell = (voltage() / 4 * 1000).toInt()
        return BatteryStatus(
            id = 0,
            batteryFunction = MavBatteryFunction.MAV_BATTERY_FUNCTION_ALL,
            type = MavBatteryType.MAV_BATTERY_TYPE_LIPO,
            temperature = 3100,
            voltages = IntArray(10) { if (it < 4) cell else 0xFFFF },
            currentBattery = (currentA() * 100).toInt(),
            currentConsumed = batteryMah.toInt(),
            energyConsumed = -1,
            batteryRemaining = remainingPct(),
            timeRemaining = 0,
        )
    }

    private fun home() = HomePosition(
        latitude = (options.homeLatDeg * 1e7).toInt(),
        longitude = (options.homeLonDeg * 1e7).toInt(),
        altitude = (options.homeAltMslM * 1000).toInt(),
        q = floatArrayOf(1f, 0f, 0f, 0f),
    )

    private fun missionCurrent() = MissionCurrent(seq = currentSeq, total = missionFor(MISSION).size, missionState = if (paused) 4 else 3)

    private fun rcChannels() = RcChannels(
        timeBootMs = bootMs,
        chancount = 8,
        chan1Raw = 1500, chan2Raw = 1500, chan3Raw = if (armed) 1500 else 1000, chan4Raw = 1500,
        chan5Raw = 1100, chan6Raw = 1500, chan7Raw = 1000, chan8Raw = 1000,
        chan9Raw = 0xFFFF, chan10Raw = 0xFFFF, chan11Raw = 0xFFFF, chan12Raw = 0xFFFF, chan13Raw = 0xFFFF,
        chan14Raw = 0xFFFF, chan15Raw = 0xFFFF, chan16Raw = 0xFFFF, chan17Raw = 0xFFFF, chan18Raw = 0xFFFF,
        rssi = 230,
    )

    private fun text(severity: Int, text: String) = send(Statustext(severity = severity, text = text))

    private fun send(message: MavMessage) = emit(writer.encode(message))

    private fun paramValue(name: String): Float = params.firstOrNull { it.name == name }?.value ?: 0f

    /** Current simulated altitude above home, for tests. */
    val altitudeM: Double get() = alt
    val isArmed: Boolean get() = armed
    val modeNumber: Int get() = mode
    val horizontalDistanceFromHomeM: Double get() = sqrt(north * north + east * east)

    internal data class SimParam(val name: String, val value: Float, val type: Int)

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
        private const val METERS_PER_DEG = 111_319.49
        private const val MISSION = 0
        private const val FORCE_ARM = 2989f
        private const val FORCE_DISARM = 21196f

        /** Gyro, accel, mag, baro, GPS, RC, motor outputs, AHRS, battery present/enabled/healthy. */
        private const val SENSORS: Long = 0x0221802FL

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

        private val MODE_NAMES = mapOf(
            MODE_STABILIZE to "STABILIZE", 1 to "ACRO", MODE_ALT_HOLD to "ALT_HOLD", MODE_AUTO to "AUTO",
            MODE_GUIDED to "GUIDED", MODE_LOITER to "LOITER", MODE_RTL to "RTL", MODE_CIRCLE to "CIRCLE",
            MODE_LAND to "LAND", 13 to "SPORT", MODE_POSHOLD to "POSHOLD", MODE_BRAKE to "BRAKE", 21 to "SMART_RTL",
        )

        private val ARMABLE_MODES = setOf(MODE_STABILIZE, 1, MODE_ALT_HOLD, MODE_GUIDED, MODE_LOITER, 13, MODE_POSHOLD)

        internal fun defaultParams(): List<SimParam> {
            val i8 = MavParamType.MAV_PARAM_TYPE_INT8
            val i16 = MavParamType.MAV_PARAM_TYPE_INT16
            val i32 = MavParamType.MAV_PARAM_TYPE_INT32
            val f = MavParamType.MAV_PARAM_TYPE_REAL32
            return listOf(
                SimParam("SYSID_THISMAV", 1f, i16),
                SimParam("SYSID_MYGCS", 255f, i16),
                SimParam("FRAME_CLASS", 1f, i8),
                SimParam("FRAME_TYPE", 1f, i8),
                SimParam("ARMING_CHECK", 1f, i32),
                SimParam("ANGLE_MAX", 3000f, i16),
                SimParam("BATT_MONITOR", 4f, i8),
                SimParam("BATT_CAPACITY", 5200f, i32),
                SimParam("BATT_LOW_VOLT", 14.0f, f),
                SimParam("BATT_CRT_VOLT", 13.2f, f),
                SimParam("BATT_FS_LOW_ACT", 2f, i8),
                SimParam("BATT_FS_CRT_ACT", 1f, i8),
                SimParam("FS_THR_ENABLE", 1f, i8),
                SimParam("FS_THR_VALUE", 975f, i16),
                SimParam("FS_GCS_ENABLE", 1f, i8),
                SimParam("FS_EKF_ACTION", 1f, i8),
                SimParam("FS_EKF_THRESH", 0.8f, f),
                SimParam("FENCE_ENABLE", 0f, i8),
                SimParam("FENCE_TYPE", 7f, i8),
                SimParam("FENCE_ACTION", 1f, i8),
                SimParam("FENCE_ALT_MAX", 100f, f),
                SimParam("FENCE_RADIUS", 300f, f),
                SimParam("RTL_ALT", 1500f, i32),
                SimParam("RTL_LOIT_TIME", 5000f, i32),
                SimParam("LAND_SPEED", 50f, i16),
                SimParam("PILOT_SPEED_UP", 250f, i16),
                SimParam("WPNAV_SPEED", 500f, f),
                SimParam("WPNAV_SPEED_UP", 250f, f),
                SimParam("WPNAV_SPEED_DN", 150f, f),
                SimParam("WPNAV_RADIUS", 200f, f),
                SimParam("WPNAV_ACCEL", 100f, f),
                SimParam("FLTMODE_CH", 5f, i8),
                SimParam("FLTMODE1", 0f, i8),
                SimParam("FLTMODE2", 2f, i8),
                SimParam("FLTMODE3", 5f, i8),
                SimParam("FLTMODE4", 6f, i8),
                SimParam("FLTMODE5", 9f, i8),
                SimParam("FLTMODE6", 3f, i8),
                SimParam("RCMAP_ROLL", 1f, i8),
                SimParam("RCMAP_PITCH", 2f, i8),
                SimParam("RCMAP_THROTTLE", 3f, i8),
                SimParam("RCMAP_YAW", 4f, i8),
                SimParam("RC1_MIN", 1100f, i16),
                SimParam("RC1_MAX", 1900f, i16),
                SimParam("RC1_TRIM", 1500f, i16),
                SimParam("RC2_MIN", 1100f, i16),
                SimParam("RC2_MAX", 1900f, i16),
                SimParam("RC2_TRIM", 1500f, i16),
                SimParam("RC3_MIN", 1100f, i16),
                SimParam("RC3_MAX", 1900f, i16),
                SimParam("RC3_TRIM", 1100f, i16),
                SimParam("RC4_MIN", 1100f, i16),
                SimParam("RC4_MAX", 1900f, i16),
                SimParam("RC4_TRIM", 1500f, i16),
                SimParam("ATC_RAT_RLL_P", 0.135f, f),
                SimParam("ATC_RAT_RLL_I", 0.135f, f),
                SimParam("ATC_RAT_RLL_D", 0.0036f, f),
                SimParam("ATC_RAT_PIT_P", 0.135f, f),
                SimParam("ATC_RAT_PIT_I", 0.135f, f),
                SimParam("ATC_RAT_PIT_D", 0.0036f, f),
                SimParam("ATC_RAT_YAW_P", 0.18f, f),
                SimParam("ATC_RAT_YAW_I", 0.018f, f),
                SimParam("COMPASS_USE", 1f, i8),
                SimParam("COMPASS_OFS_X", 12f, i16),
                SimParam("COMPASS_OFS_Y", -7f, i16),
                SimParam("COMPASS_OFS_Z", 30f, i16),
                SimParam("INS_ACCOFFS_X", 0.01f, f),
                SimParam("INS_ACCOFFS_Y", -0.02f, f),
                SimParam("INS_ACCOFFS_Z", 0.05f, f),
                SimParam("MOT_SPIN_ARM", 0.1f, f),
                SimParam("MOT_SPIN_MIN", 0.15f, f),
                SimParam("MOT_BAT_VOLT_MAX", 16.8f, f),
                SimParam("MOT_BAT_VOLT_MIN", 13.2f, f),
                SimParam("LOG_BITMASK", 176126f, i32),
                SimParam("GPS_TYPE", 1f, i8),
                SimParam("EK3_ENABLE", 1f, i8),
                SimParam("AHRS_EKF_TYPE", 3f, i8),
                SimParam("SERIAL0_BAUD", 115f, i32),
            )
        }
    }
}
