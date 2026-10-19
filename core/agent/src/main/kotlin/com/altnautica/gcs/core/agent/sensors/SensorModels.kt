package com.altnautica.gcs.core.agent.sensors

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Agent sensor, clock and storage payloads. A route whose service is absent
// answers `present: false` with empty values; nothing here invents a reading.
// `sigma_ns` of -1 means unmeasured.

/** `GET /api/v1/clock`. */
@Serializable
data class ClockStatus(
    val present: Boolean = false,
    /** `gnss` | `hub_freerun` | `host_monotonic` | `sim`: the timebase the next session would use. */
    val timebase: String? = null,
    val locked: Boolean = false,
    @SerialName("sigma_ns") val sigmaNs: Long = -1,
    @SerialName("t_ns") val tNs: Long? = null,
    @SerialName("utc_ns") val utcNs: Long? = null,
    val sources: List<ClockSource> = emptyList(),
)

@Serializable
data class ClockSource(
    val id: String,
    /** `fc` | `hub` | `gnss_pps` | `sim`. */
    val kind: String? = null,
    val locked: Boolean = false,
    @SerialName("offset_ns") val offsetNs: Long? = null,
    @SerialName("skew_ppm") val skewPpm: Double? = null,
    @SerialName("sigma_ns") val sigmaNs: Long = -1,
    @SerialName("last_update_ns") val lastUpdateNs: Long? = null,
)

/** `GET /api/v1/sensors`. */
@Serializable
data class SensorsStatus(
    val present: Boolean = false,
    @SerialName("rig_id") val rigId: String? = null,
    @SerialName("calib_version") val calibVersion: String? = null,
    val hub: SensorHub? = null,
    val fc: SensorFc? = null,
    val sensors: List<RigSensor> = emptyList(),
    val gnss: SensorGnss? = null,
)

@Serializable
data class SensorHub(
    val connected: Boolean = false,
    val transport: String? = null,
    val fw: String? = null,
    val board: String? = null,
    @SerialName("proto_ver") val protoVer: Int? = null,
    @SerialName("uptime_s") val uptimeS: Long? = null,
    @SerialName("crc_errors") val crcErrors: Long? = null,
    val dropped: Long? = null,
)

@Serializable
data class SensorFc(
    val connected: Boolean = false,
    val autopilot: String? = null,
    @SerialName("highres_imu") val highresImu: Boolean? = null,
)

@Serializable
data class RigSensor(
    val id: String,
    /** `camera` | `imu` | `lidar` | `gnss` | `thermal` | `baro` | `mag`. */
    val kind: String? = null,
    val role: String? = null,
    /** `hub` | `fc` | `vision` | `gnss` | `lidar` | `sim`. */
    val source: String? = null,
    @SerialName("rate_hz") val rateHz: Double? = null,
    @SerialName("expected_hz") val expectedHz: Double? = null,
    @SerialName("last_t_ns") val lastTNs: Long? = null,
    @SerialName("age_ms") val ageMs: Double? = null,
    @SerialName("sigma_ns") val sigmaNs: Long = -1,
)

@Serializable
data class SensorGnss(
    val fix: String? = null,
    val sats: Int? = null,
    val hdop: Double? = null,
    val rtk: String? = null,
)

/** `GET /api/v1/storage`. */
@Serializable
data class StorageStatus(
    val present: Boolean = false,
    @SerialName("capture_root") val captureRoot: String? = null,
    val volumes: List<StorageVolume> = emptyList(),
    val capture: CaptureStorage? = null,
    @SerialName("write_probe") val writeProbe: WriteProbe? = null,
)

@Serializable
data class StorageVolume(
    val mount: String,
    val device: String? = null,
    val fs: String? = null,
    @SerialName("total_bytes") val totalBytes: Long? = null,
    @SerialName("free_bytes") val freeBytes: Long? = null,
    val rotational: Boolean? = null,
    @SerialName("is_capture") val isCapture: Boolean = false,
)

@Serializable
data class CaptureStorage(
    @SerialName("used_bytes") val usedBytes: Long? = null,
    @SerialName("budget_bytes") val budgetBytes: Long? = null,
    @SerialName("budget_pct") val budgetPct: Int? = null,
    @SerialName("floor_free_bytes") val floorFreeBytes: Long? = null,
    val sessions: Int? = null,
    val uploaded: Int? = null,
    @SerialName("evict_unuploaded") val evictUnuploaded: Boolean? = null,
    /** `ok` | `near_full` | `full` (`full` refuses new sessions with `E_STORAGE_FULL`). */
    val state: String? = null,
)

@Serializable
data class WriteProbe(
    val mbps: Double? = null,
    @SerialName("measured_at_ns") val measuredAtNs: Long? = null,
)
