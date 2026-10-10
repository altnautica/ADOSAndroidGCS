package com.altnautica.gcs.core.model

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
    val statusText: SharedFlow<StatusText>
}
