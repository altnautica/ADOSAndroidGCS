package com.altnautica.gcs.core.link.vehicle

import com.altnautica.gcs.core.link.mavlink.ElapsedClock
import com.altnautica.gcs.core.link.mavlink.MavlinkLink
import com.altnautica.gcs.core.link.mavlink.targetMessages
import com.altnautica.gcs.core.mavlink.enums.MavModeFlag
import com.altnautica.gcs.core.mavlink.messages.Attitude as AttitudeMsg
import com.altnautica.gcs.core.mavlink.messages.BatteryStatus
import com.altnautica.gcs.core.mavlink.messages.EkfStatusReport
import com.altnautica.gcs.core.mavlink.messages.FenceStatus as FenceStatusMsg
import com.altnautica.gcs.core.mavlink.messages.GlobalPositionInt
import com.altnautica.gcs.core.mavlink.messages.GpsRawInt
import com.altnautica.gcs.core.mavlink.messages.Heartbeat
import com.altnautica.gcs.core.mavlink.messages.HomePosition as HomePositionMsg
import com.altnautica.gcs.core.mavlink.messages.MissionCurrent
import com.altnautica.gcs.core.mavlink.messages.ObstacleDistance
import com.altnautica.gcs.core.mavlink.enums.MavFrame
import com.altnautica.gcs.core.mavlink.messages.RadioStatus
import com.altnautica.gcs.core.mavlink.messages.Statustext
import com.altnautica.gcs.core.mavlink.messages.SysStatus
import com.altnautica.gcs.core.mavlink.messages.VfrHud
import com.altnautica.gcs.core.mavlink.messages.Vibration
import com.altnautica.gcs.core.mavlink.messages.Wind
import com.altnautica.gcs.core.mavlink.messages.WindCov
import com.altnautica.gcs.core.mavlink.modes.FlightModeTables
import com.altnautica.gcs.core.model.Attitude
import com.altnautica.gcs.core.model.BatteryState
import com.altnautica.gcs.core.model.EkfState
import com.altnautica.gcs.core.model.FenceStatus
import com.altnautica.gcs.core.model.GpsFix
import com.altnautica.gcs.core.model.GpsState
import com.altnautica.gcs.core.model.HeartbeatState
import com.altnautica.gcs.core.model.HomePosition
import com.altnautica.gcs.core.model.LinkQuality
import com.altnautica.gcs.core.model.MissionProgress
import com.altnautica.gcs.core.model.ObstacleSectors
import com.altnautica.gcs.core.model.PositionFix
import com.altnautica.gcs.core.model.StatusText
import com.altnautica.gcs.core.model.SystemHealth
import com.altnautica.gcs.core.model.VehicleTelemetry
import com.altnautica.gcs.core.model.VibrationState
import com.altnautica.gcs.core.model.Vfr
import com.altnautica.gcs.core.model.WindState
import kotlin.math.atan2
import kotlin.math.hypot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Per-topic telemetry decoded from the target autopilot's MAVLink stream. */
class MavlinkVehicleTelemetry(
    scope: CoroutineScope,
    private val link: MavlinkLink,
    private val clock: ElapsedClock,
) : VehicleTelemetry {

    private val _attitude = MutableStateFlow<Attitude?>(null)
    private val _position = MutableStateFlow<PositionFix?>(null)
    private val _vfr = MutableStateFlow<Vfr?>(null)
    private val _battery = MutableStateFlow<BatteryState?>(null)
    private val _gps = MutableStateFlow<GpsState?>(null)
    private val _heartbeat = MutableStateFlow<HeartbeatState?>(null)
    private val _home = MutableStateFlow<HomePosition?>(null)
    private val _wind = MutableStateFlow<WindState?>(null)
    private val _ekf = MutableStateFlow<EkfState?>(null)
    private val _vibration = MutableStateFlow<VibrationState?>(null)
    private val _linkQuality = MutableStateFlow<LinkQuality?>(null)
    private val _missionProgress = MutableStateFlow<MissionProgress?>(null)
    private val _systemHealth = MutableStateFlow<SystemHealth?>(null)
    private val _fence = MutableStateFlow<FenceStatus?>(null)
    private val _obstacles = MutableStateFlow<ObstacleSectors?>(null)
    private val _statusText = MutableSharedFlow<StatusText>(extraBufferCapacity = 64)

    override val attitude: StateFlow<Attitude?> = _attitude.asStateFlow()
    override val position: StateFlow<PositionFix?> = _position.asStateFlow()
    override val vfr: StateFlow<Vfr?> = _vfr.asStateFlow()
    override val battery: StateFlow<BatteryState?> = _battery.asStateFlow()
    override val gps: StateFlow<GpsState?> = _gps.asStateFlow()
    override val heartbeat: StateFlow<HeartbeatState?> = _heartbeat.asStateFlow()
    override val home: StateFlow<HomePosition?> = _home.asStateFlow()
    override val wind: StateFlow<WindState?> = _wind.asStateFlow()
    override val ekf: StateFlow<EkfState?> = _ekf.asStateFlow()
    override val vibration: StateFlow<VibrationState?> = _vibration.asStateFlow()
    override val linkQuality: StateFlow<LinkQuality?> = _linkQuality.asStateFlow()
    override val missionProgress: StateFlow<MissionProgress?> = _missionProgress.asStateFlow()
    override val systemHealth: StateFlow<SystemHealth?> = _systemHealth.asStateFlow()
    override val fence: StateFlow<FenceStatus?> = _fence.asStateFlow()
    override val obstacles: StateFlow<ObstacleSectors?> = _obstacles.asStateFlow()
    override val statusText: SharedFlow<StatusText> = _statusText.asSharedFlow()

    /** BATTERY_STATUS for battery 0 wins over SYS_STATUS while it keeps arriving. */
    private var batteryStatusAtMs: Long = Long.MIN_VALUE
    private var systemStatus: Int = 0
    private var sensors: SysStatus? = null
    private var rssiDbm: Int? = null

    /** Mission item count learnt by the mission service when MISSION_CURRENT does not carry it. */
    @Volatile
    var knownMissionCount: Int? = null

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) { link.targetMessages().collect { handle(it.message) } }
        scope.launch {
            while (isActive) {
                val hb = _heartbeat.value
                if (hb != null) {
                    _linkQuality.value = LinkQuality(rssiDbm, null, clock.nowMs() - hb.tMs)
                }
                delay(LINK_QUALITY_PERIOD_MS)
            }
        }
    }

    private fun handle(msg: Any) {
        val now = clock.nowMs()
        when (msg) {
            is Heartbeat -> {
                val family = link.target.value?.family ?: FlightModeTables.family(msg.autopilot, msg.type)
                _heartbeat.value = HeartbeatState(
                    armed = (msg.baseMode and MavModeFlag.MAV_MODE_FLAG_SAFETY_ARMED) != 0,
                    mode = FlightModeTables.decode(family, msg.customMode),
                    vehicleType = msg.type,
                    autopilot = msg.autopilot,
                    tMs = now,
                )
                systemStatus = msg.systemStatus
                publishHealth(now)
                _linkQuality.value = LinkQuality(rssiDbm, null, 0)
            }
            is AttitudeMsg -> _attitude.value = Attitude(msg.roll, msg.pitch, msg.yaw, now)
            is GlobalPositionInt -> _position.value = PositionFix(
                latDeg = msg.lat / 1e7,
                lonDeg = msg.lon / 1e7,
                altMslM = msg.alt / 1000f,
                altRelM = msg.relativeAlt / 1000f,
                vxMs = msg.vx / 100f,
                vyMs = msg.vy / 100f,
                vzMs = msg.vz / 100f,
                tMs = now,
            )
            is VfrHud -> _vfr.value = Vfr(msg.airspeed, msg.groundspeed, msg.heading.toFloat(), msg.throttle, msg.climb, now)
            is SysStatus -> {
                sensors = msg
                publishHealth(now)
                if (now - batteryStatusAtMs > BATTERY_STATUS_PRIORITY_MS) {
                    _battery.value = BatteryState(
                        id = 0,
                        voltageV = if (msg.voltageBattery == UINT16_MAX) null else msg.voltageBattery / 1000f,
                        currentA = if (msg.currentBattery < 0) null else msg.currentBattery / 100f,
                        remainingPct = msg.batteryRemaining.takeIf { it in 0..100 },
                        timeToReserveS = null,
                        tMs = now,
                    )
                }
            }
            is BatteryStatus -> if (msg.id == 0) {
                batteryStatusAtMs = now
                val cells = msg.voltages.filter { it != UINT16_MAX } +
                    msg.voltagesExt.filter { it != 0 && it != UINT16_MAX }
                _battery.value = BatteryState(
                    id = msg.id,
                    voltageV = if (cells.isEmpty()) null else cells.sum() / 1000f,
                    currentA = if (msg.currentBattery < 0) null else msg.currentBattery / 100f,
                    remainingPct = msg.batteryRemaining.takeIf { it in 0..100 },
                    timeToReserveS = msg.timeRemaining.takeIf { it > 0 },
                    tMs = now,
                )
            }
            is GpsRawInt -> _gps.value = GpsState(
                fix = gpsFix(msg.fixType),
                satellites = msg.satellitesVisible.takeIf { it != 255 },
                hdop = if (msg.eph == UINT16_MAX) null else msg.eph / 100f,
                tMs = now,
            )
            is HomePositionMsg -> _home.value = HomePosition(msg.latitude / 1e7, msg.longitude / 1e7, msg.altitude / 1000f, now)
            is Wind -> _wind.value = WindState(msg.direction, msg.speed, now)
            is WindCov -> if (!msg.windX.isNaN() && !msg.windY.isNaN()) {
                // WIND_COV is the velocity the air moves toward (NED); the topic reports where it comes from.
                val from = Math.toDegrees(atan2(-msg.windY.toDouble(), -msg.windX.toDouble())).toFloat()
                _wind.value = WindState((from + 360f) % 360f, hypot(msg.windX, msg.windY), now)
            }
            is EkfStatusReport -> _ekf.value = EkfState(
                msg.flags,
                msg.velocityVariance,
                msg.posHorizVariance,
                msg.posVertVariance,
                msg.compassVariance,
                now,
            )
            is Vibration -> _vibration.value = VibrationState(
                msg.vibrationX,
                msg.vibrationY,
                msg.vibrationZ,
                msg.clipping0 + msg.clipping1 + msg.clipping2,
                now,
            )
            is RadioStatus -> {
                // SiK-style radios report RSSI in 0..254 units; 255 means unknown.
                rssiDbm = if (msg.rssi == 255) null else (msg.rssi / 1.9 - 127).toInt()
            }
            is MissionCurrent -> _missionProgress.value = MissionProgress(
                currentSeq = msg.seq,
                total = if (msg.total in 1 until UINT16_MAX) msg.total else knownMissionCount ?: 0,
            )
            is FenceStatusMsg -> _fence.value = FenceStatus(msg.breachStatus, msg.breachCount, now)
            is ObstacleDistance -> _obstacles.value = ObstacleSectors(
                distancesCm = msg.distances.toList(),
                incrementDeg = if (msg.incrementF != 0f) msg.incrementF else msg.increment.toFloat(),
                angleOffsetDeg = msg.angleOffset,
                minCm = msg.minDistance,
                maxCm = msg.maxDistance,
                // GLOBAL and LOCAL_NED sectors are north-aligned; BODY_FRD (and unset) follow the nose.
                northAligned = msg.frame == MavFrame.MAV_FRAME_GLOBAL || msg.frame == MavFrame.MAV_FRAME_LOCAL_NED,
                tMs = now,
            )
            is Statustext -> _statusText.tryEmit(StatusText(msg.severity, msg.text, now))
        }
    }

    private fun publishHealth(now: Long) {
        val s = sensors
        _systemHealth.value = SystemHealth(
            systemStatus = systemStatus,
            sensorsPresent = s?.onboardControlSensorsPresent ?: 0L,
            sensorsEnabled = s?.onboardControlSensorsEnabled ?: 0L,
            sensorsHealthy = s?.onboardControlSensorsHealth ?: 0L,
            tMs = now,
        )
    }

    private fun gpsFix(type: Int): GpsFix = when (type) {
        0 -> GpsFix.NoGps
        1 -> GpsFix.NoFix
        2 -> GpsFix.Fix2D
        3, 7, 8 -> GpsFix.Fix3D
        4 -> GpsFix.DGps
        5 -> GpsFix.RtkFloat
        6 -> GpsFix.RtkFixed
        else -> GpsFix.NoFix
    }

    private companion object {
        const val UINT16_MAX = 0xFFFF
        const val BATTERY_STATUS_PRIORITY_MS = 5_000L
        const val LINK_QUALITY_PERIOD_MS = 500L
    }
}
