package com.altnautica.gcs.core.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

// All values are SI units. `tMs` is the receive time on the
// `SystemClock.elapsedRealtime()` clock, in milliseconds.

data class Attitude(
    val rollRad: Float,
    val pitchRad: Float,
    val yawRad: Float,
    val tMs: Long,
)

data class PositionFix(
    val latDeg: Double,
    val lonDeg: Double,
    val altMslM: Float,
    val altRelM: Float,
    val vxMs: Float,
    val vyMs: Float,
    val vzMs: Float,
    val tMs: Long,
)

data class Vfr(
    val airspeedMs: Float,
    val groundspeedMs: Float,
    val headingDeg: Float,
    val throttlePct: Int,
    val climbMs: Float,
    val tMs: Long,
)

data class BatteryState(
    val id: Int,
    val voltageV: Float?,
    val currentA: Float?,
    /** Null when the flight controller reports the remaining charge as unknown. */
    val remainingPct: Int?,
    val timeToReserveS: Int?,
    val tMs: Long,
)

enum class GpsFix { NoGps, NoFix, Fix2D, Fix3D, DGps, RtkFloat, RtkFixed }

data class GpsState(
    val fix: GpsFix,
    val satellites: Int?,
    val hdop: Float?,
    val tMs: Long,
)

data class FlightMode(val customMode: Long, val name: String)

data class HeartbeatState(
    val armed: Boolean,
    val mode: FlightMode,
    val vehicleType: Int,
    val autopilot: Int,
    val tMs: Long,
)

data class HomePosition(
    val latDeg: Double,
    val lonDeg: Double,
    val altMslM: Float,
    val tMs: Long,
)

data class WindState(
    val directionDeg: Float,
    val speedMs: Float,
    val tMs: Long,
)

data class StatusText(
    val severity: Int,
    val text: String,
    val tMs: Long,
)

data class EkfState(
    val flags: Int,
    val velVar: Float,
    val posHorizVar: Float,
    val posVertVar: Float,
    val compassVar: Float,
    val tMs: Long,
)

data class VibrationState(
    val x: Float,
    val y: Float,
    val z: Float,
    val clipping: Long,
    val tMs: Long,
)

data class LinkQuality(
    val rssiDbm: Int?,
    val lqPct: Int?,
    val heartbeatAgeMs: Long,
)

data class MissionProgress(
    val currentSeq: Int,
    val total: Int,
)

/**
 * Autopilot self-reported health: `HEARTBEAT.system_status` (MAV_STATE) and the
 * `SYS_STATUS` sensor bitmasks (MAV_SYS_STATUS_SENSOR), which carry RC-receiver health.
 */
data class SystemHealth(
    val systemStatus: Int,
    val sensorsPresent: Long,
    val sensorsEnabled: Long,
    val sensorsHealthy: Long,
    val tMs: Long,
)

/** `FENCE_STATUS`: breach state (FENCE_BREACH) and cumulative breach count. */
data class FenceStatus(
    val breachStatus: Int,
    val breachCount: Int,
    val tMs: Long,
)

/**
 * `OBSTACLE_DISTANCE` sectors. Distances are in cm; 0 or a value above [maxCm]
 * means no reading in that sector. [northAligned] is true for NED frames and
 * false for the body frame.
 */
data class ObstacleSectors(
    val distancesCm: List<Int>,
    val incrementDeg: Float,
    val angleOffsetDeg: Float,
    val minCm: Int,
    val maxCm: Int,
    val northAligned: Boolean,
    val tMs: Long,
)

/** `LOCAL_POSITION_NED`: position and velocity in the EKF local frame (north, east, down), metres. */
data class LocalPosition(
    val northM: Float,
    val eastM: Float,
    val downM: Float,
    val vnMs: Float,
    val veMs: Float,
    val vdMs: Float,
    val tMs: Long,
)

private val NO_LOCAL_POSITION: StateFlow<LocalPosition?> = MutableStateFlow(null)
private val NO_VTOL_TRANSITION: StateFlow<Boolean> = MutableStateFlow(false)
private val NO_LANDED_STATE: StateFlow<Boolean?> = MutableStateFlow(null)

/** One flow per telemetry topic so consumers only observe what they draw. */
interface VehicleTelemetry {
    val attitude: StateFlow<Attitude?>
    val position: StateFlow<PositionFix?>
    val vfr: StateFlow<Vfr?>
    val battery: StateFlow<BatteryState?>
    val gps: StateFlow<GpsState?>
    val heartbeat: StateFlow<HeartbeatState?>
    val home: StateFlow<HomePosition?>
    val wind: StateFlow<WindState?>
    val ekf: StateFlow<EkfState?>
    val vibration: StateFlow<VibrationState?>
    val linkQuality: StateFlow<LinkQuality?>
    val missionProgress: StateFlow<MissionProgress?>
    val systemHealth: StateFlow<SystemHealth?>
    val fence: StateFlow<FenceStatus?>
    val obstacles: StateFlow<ObstacleSectors?>
    val statusText: SharedFlow<StatusText>

    /** `LOCAL_POSITION_NED`; sources without a local frame keep it null. */
    val localPosition: StateFlow<LocalPosition?> get() = NO_LOCAL_POSITION

    /** True while a VTOL airframe is transitioning (`EXTENDED_SYS_STATE.vtol_state` 1 or 2). */
    val vtolTransitioning: StateFlow<Boolean> get() = NO_VTOL_TRANSITION

    /**
     * On the ground by the FC's own `EXTENDED_SYS_STATE.landed_state`: true for ON_GROUND, false
     * for in air, taking off or landing; null when never received or undefined.
     */
    val onGround: StateFlow<Boolean?> get() = NO_LANDED_STATE
}
