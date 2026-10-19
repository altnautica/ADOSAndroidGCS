package com.altnautica.gcs.core.agent.capture

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// World Engine capture routes (extension `com.altnautica.world-engine`).

/** `GET atlas/capture/state`. */
@Serializable
data class CaptureState(
    /** `idle` | `recording` | `paused` | `closing`. */
    val state: String = "idle",
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("started_at_ns") val startedAtNs: Long? = null,
    @SerialName("duration_s") val durationS: Double? = null,
    val site: String? = null,
    val floor: Int? = null,
    val room: String? = null,
    val segment: Int? = null,
    val frames: CaptureFrames? = null,
    val pose: CapturePose? = null,
    val coverage: CaptureCoverage? = null,
    val health: List<CaptureHealth> = emptyList(),
    val exposure: CaptureExposure? = null,
    val storage: CaptureStorageLeft? = null,
)

@Serializable
data class CaptureFrames(
    val total: Long? = null,
    @SerialName("dropped_pct") val droppedPct: Double? = null,
    @SerialName("per_camera") val perCamera: List<CameraFrames> = emptyList(),
)

@Serializable
data class CameraFrames(val id: String, val fps: Double? = null, val dropped: Long? = null)

@Serializable
data class CapturePose(
    /** `fc_ekf` | `vio` | `lidar` | `replay` | `none`. */
    val source: String? = null,
    val health: String? = null,
    @SerialName("tracked_features") val trackedFeatures: Int? = null,
    @SerialName("drift_hint_m") val driftHintM: Double? = null,
)

@Serializable
data class CaptureCoverage(
    val voxels: Long? = null,
    @SerialName("room_pct") val roomPct: Double? = null,
    @SerialName("loop_closed") val loopClosed: Boolean? = null,
    @SerialName("distance_from_start_m") val distanceFromStartM: Double? = null,
)

/** One health chip: id `blur|texture|flicker|corridor|storage|clock`, level `ok|warn|bad`. */
@Serializable
data class CaptureHealth(val id: String, val level: String = "ok", val value: Double? = null)

/** Exposure state; also the body and answer of `PUT atlas/capture/exposure`. */
@Serializable
data class CaptureExposure(
    val locked: Boolean = false,
    @SerialName("exposure_us") val exposureUs: Long? = null,
    val gain: Double? = null,
    /** `walking` | `slow` | `short`. */
    val mode: String? = null,
    /** False until the agent has set the sensor's exposure/gain; [locked] is the operator's request. */
    val applied: Boolean = false,
)

@Serializable
data class CaptureStorageLeft(
    @SerialName("free_bytes") val freeBytes: Long? = null,
    @SerialName("est_remaining_s") val estRemainingS: Long? = null,
)

/** Optional `POST atlas/capture/start` body. */
@Serializable
data class CaptureStartRequest(
    val site: String? = null,
    val floor: Int? = null,
    val room: String? = null,
    @SerialName("operator_anchor") val operatorAnchor: OperatorAnchor? = null,
)

@Serializable
data class OperatorAnchor(
    @SerialName("lat_deg") val latDeg: Double,
    @SerialName("lon_deg") val lonDeg: Double,
    @SerialName("h_ellipsoid_m") val hEllipsoidM: Double? = null,
    @SerialName("heading_deg") val headingDeg: Double? = null,
)

@Serializable
data class CaptureStartResponse(val ok: Boolean = false, @SerialName("session_id") val sessionId: String? = null)

@Serializable
data class CaptureMarkRequest(val room: String, val floor: Int)

@Serializable
data class CaptureMarkResponse(val ok: Boolean = false, val segment: Int? = null)

/** `GET atlas/sessions`. */
@Serializable
data class CaptureSessions(val sessions: List<CaptureSession> = emptyList())

@Serializable
data class CaptureSession(
    @SerialName("session_id") val sessionId: String,
    @SerialName("started_at_utc") val startedAtUtc: String? = null,
    @SerialName("duration_s") val durationS: Double? = null,
    @SerialName("size_bytes") val sizeBytes: Long? = null,
    val frames: Long? = null,
    val rooms: List<String> = emptyList(),
    val floors: List<Int> = emptyList(),
    val timebase: String? = null,
    @SerialName("anchor_source") val anchorSource: String? = null,
    val uploaded: Boolean = false,
    val upload: SessionUpload? = null,
)

@Serializable
data class SessionUpload(
    /** `idle` | `queued` | `uploading` | `committed` | `failed`. */
    val state: String = "idle",
    @SerialName("bytes_sent") val bytesSent: Long? = null,
    @SerialName("bytes_total") val bytesTotal: Long? = null,
    val node: String? = null,
    val error: String? = null,
)

@Serializable
data class ReconstructRequest(val preset: String)

@Serializable
data class ReconstructResponse(
    val ok: Boolean = false,
    val node: String? = null,
    @SerialName("job_id") val jobId: String? = null,
)
